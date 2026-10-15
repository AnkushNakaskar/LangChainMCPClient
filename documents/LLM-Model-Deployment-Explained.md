# How an LLM is actually deployed in production (and how it fits with Godric)

> **What comes from code vs. what is general knowledge:**
> - ✅ **From code** (Godric + `machina-model-1.6.14.jar`): in-house models are served through **Machina**; `ModelServerType` = **`MLFLOW` | `VLLM`**; Godric's chat API supports only **VLLM** for Machina LLMs; a Machina **usecase** has **routes** with selection = Simple / Random / **Weighted** / **A-B (Ablaze)**, `RoutingMode` = **LIVE | SHADOW**, and `approvedBy` (maker-checker); an online route's target is a **SERVICE** with `serviceDiscoveryName` + **`traefikUrl`**; Godric calls it over **direct HTTPS :443**, `{endpoint}/v1/chat/completions`; Godric's comment: *"Most entities are on GPU"*. `DeploymentProvider` = ROSEY | SAMSARA | ARTIFACTORY.
> - ⚠️ **General industry knowledge (verify internally):** exact GPU types, the orchestrator (Kubernetes/Drove/other), what "Rosey" is, how weights are stored, and the vLLM flags used.

---

## Part 1: The mental model in one sentence

> **A self-hosted LLM in production is "just" a containerized HTTP service, but the container runs an inference server (vLLM), loads model weights of several GBs into GPU memory, and scales on GPU capacity instead of CPU.**

You already know how a REST service is deployed. Here's what changes:

|  | Normal Java REST service | LLM service (vLLM) |
| --- | --- | --- |
| Image contents | JAR + JRE (~300 MB) | CUDA + PyTorch + vLLM (~8–10 GB) **+ model weights** (8B model ≈ 16 GB, 70B ≈ 140 GB) |
| Hardware | CPU + RAM | **GPU** (A100/H100/L40S/A10…), GPU memory is the bottleneck |
| Startup time | seconds | **minutes** (download/load weights to the GPU, warm-up) |
| One request takes | ms | **seconds** (generates token by token) |
| Concurrency | thread pool | **continuous batching**: many requests share each GPU step |
| Main memory user | heap | **KV cache** (grows with context length × concurrent requests) |
| Scale signal | CPU %, RPS | **queue length, KV-cache usage %, time-to-first-token** |
| Cost | cheap per pod | **one GPU ≈ cost of dozens of CPU pods**, so idle replicas are expensive |
| API | your own | **OpenAI-compatible** `/v1/chat/completions` (that's why Godric can treat it like OpenAI) |

---

## Part 2: Three ways a model gets to "production"

```
                          ┌───────────────────────── GODRIC (gateway) ─────────────────────────┐
Varta / apps ─────────►  │  LLM entity spec decides where the call goes                          │
                          └───────┬───────────────────────┬───────────────────────────┬──────────┘
                                  │                       │                           │
             A. External API      │    B. Self-hosted LLM │      C. Small models       │
        OpenAiLLMSpec /           │   MachinaLLMSpec       │   (classifiers, embedding, │
        AzureOpenAiLLMSpec        │   serverType=VLLM      │    rerank, whisper)        │
                                  ▼                       ▼                           ▼
                     OpenAI / Azure OpenAI       Machina usecase → route →     Machina (MLFLOW server type,
                     (nothing to deploy;          traefikUrl → vLLM pods         often via a sidecar; or vLLM)
                      buy capacity)               on GPU nodes                   e.g. ProtectAI DeBERTa (guardrail)
```

### A. External API (OpenAI / Azure OpenAI): "deploy" = configure capacity
- In **Azure OpenAI**, you create a **deployment** inside your Azure resource: pick model + version (e.g. `gpt-4o-2024-08-06`), a region, and **capacity** (tokens-per-minute quota, or **PTU**, provisioned throughput units, for guaranteed capacity). That deployment name is exactly the `deployment` field in Godric's `AzureOpenAiLLMSpec`, and the URL becomes `/openai/deployments/{deployment}/chat/completions?api-version=…`.
- You manage: **keys** (Godric's `SecretKeyProvider`), **capacity/quota**, **region/data residency**, and **model version upgrades** (providers retire versions).
- You *don't* manage GPUs.

### B. Self-hosted open-weight LLM (Llama, Qwen, Granite, Mistral…) via Machina + vLLM
This is the "real deployment" you're asking about. Steps in Part 3.

### C. Small specialized models
Classifiers (ProtectAI prompt-injection DeBERTa, used by Godric's guardrail through the **MODEL_GATEWAY** remote), embedding models, re-rankers, speech-to-text. These are smaller (100M–1B params), often CPU or small GPUs, served via **MLflow** (`serverType=MLFLOW`, with a sidecar whose response is mapped by `responseExtractionSpec`), or by vLLM/other servers.

---

## Part 3: Deploying a self-hosted LLM, step by step

Example: deploy **Llama-3.1-8B-Instruct** (or Granite Guardian for guardrails) for the Varta DisputeAgent.

### Step 1: Pick the model and do the GPU math
**Weights memory** = parameters × bytes per parameter:
| Model | FP16/BF16 (2 bytes) | FP8 (1 byte) | INT4 (AWQ/GPTQ, ~0.5 byte) |
| --- | --- | --- | --- |
| 8B | ~16 GB | ~8 GB | ~5 GB |
| 70B | ~140 GB (needs 2× 80 GB GPUs) | ~70 GB | ~40 GB |

**KV cache** (the memory for the conversation being processed), per token:
`2 (K and V) × layers × kv_heads × head_dim × bytes`
For Llama-3.1-8B: `2 × 32 × 8 × 128 × 2 bytes ≈ 128 KB per token`
→ one full **32k-token** conversation ≈ **4 GB** of KV cache.

**On one 80 GB GPU:** with `gpu_memory_utilization=0.9`, about 72 GB is usable. Minus 16 GB of weights, about **56 GB is left for KV cache**. That's about **14 concurrent full-32k conversations**, or **hundreds of short (2k-token) requests**.
> This is why **context length is a cost decision**, and why your `CompactingToolProvider` and memory windows matter in production, not just on Ollama.

### Step 2: Get and store the weights
- Download from Hugging Face (check the **licence**: Llama licence, Apache-2.0 for Qwen/Granite/Mistral).
- Store in an **internal artifact/object store**, versioned and checksummed. Prod clusters usually **have no internet access**. Machina's `ARTIFACTORY` deployment provider/target suggests this kind of artifact store.
- Fine-tuned outputs (a full model or **LoRA adapters**) are stored the same way, as new versions.

### Step 3: Build the serving container
```dockerfile
FROM vllm/vllm-openai:<pinned-version>          # CUDA + PyTorch + vLLM, OpenAI-compatible server
# Option 1: bake weights into the image (simple, but a 20+ GB image and slow pulls)
# Option 2 (common): download weights at startup from the artifact store to local NVMe / a shared cache
ENTRYPOINT ["python", "-m", "vllm.entrypoints.openai.api_server"]
```
Run with flags like:
```bash
--model /models/llama-3.1-8b-instruct      # local path of the weights
--served-model-name llama-3.1-8b            # the "model" name the API expects
--tensor-parallel-size 1                    # split the model across N GPUs (e.g. 2 or 4 for 70B)
--max-model-len 32768                       # max context: memory vs capability trade-off
--gpu-memory-utilization 0.90               # fraction of GPU memory vLLM may use
--max-num-seqs 64                           # max concurrent sequences in a batch
--enable-prefix-caching                     # reuse KV cache for a shared prefix (system prompt + tool schemas!)
--enable-auto-tool-choice --tool-call-parser llama3_json   # OpenAI-style tool calling (needed for agents)
--enable-lora --lora-modules dispute-lora=/adapters/dispute-v3   # serve fine-tuned adapters on the same base
```
It exposes: `POST /v1/chat/completions`, `/v1/completions`, `/v1/embeddings`, `GET /v1/models`, `/health`, `/metrics` (Prometheus).

### Step 4: Schedule it on GPU nodes
- GPU nodes need NVIDIA drivers + the container toolkit. The container asks the orchestrator for GPUs (in Kubernetes: `resources.limits: nvidia.com/gpu: 1`; [ORG_REFER_TAG]'s orchestrator has an equivalent, so verify which one).
- Pin to a GPU type (an 80 GB A100/H100 for big models, L4/A10 for small ones). For 70B, a pod with 2–4 GPUs on **one node** (tensor parallelism needs fast GPU-to-GPU links).
- **Readiness probe on `/health`** only after the weights are loaded. Otherwise the load balancer sends traffic to a pod that needs another 3 minutes to start.

### Step 5: Expose it
The pods sit behind a **load balancer / ingress**. At [ORG_REFER_TAG], Machina stores a **`traefikUrl`** for the service target, and Godric calls it over **HTTPS port 443** directly (the code comment says GPU models only support direct HTTP, not service-discovery-based calls).

### Step 6: Register it in Machina (the model platform)
A **usecase** = a stable, logical name for "the model that does X", decoupled from any specific deployment:
```
Usecase { namespace: "genai", id: "llama31_8b_chat",
          routes: [ { target: SERVICE{serviceDiscoveryName, traefikUrl: v3-deployment}, weight: 90 },
                    { target: SERVICE{..., traefikUrl: v4-deployment}, weight: 10 } ],
          routeSelectionConfig: WEIGHTED | RANDOM | SIMPLE | AB(Ablaze experiment),
          routing mode per route: LIVE | SHADOW,
          approvedBy: "..."  }                     ← maker-checker
```
Machina's **route selection** decides which deployment serves a call. Godric calls `performRouteSelection(namespace, usecaseId)` and takes the LIVE route's service target.

### Step 7: Register it in Godric
```json
{ "namespace": "genai", "id": "llama31_8b",
  "spec": { "type": "MACHINA", "namespace": "genai", "usecaseId": "llama31_8b_chat",
            "serverType": "VLLM",
            "roles": { ... }, "inputModalities": ["TEXT"], "outputModalities": ["TEXT"],
            "generationParams": { "temperature": 0.1, "max_tokens": 800 } },
  "authConfig": { ... who may invoke ... } }
```
Then add it as a **target in a Godric gateway** (with guardrails), set an **alias**, and set **quotas**.

### Step 8: Clients use it, and don't know any of the above
Varta calls `POST godric/chat/completions {"model": "genai:GW_disputes"}`.

**The full production call path:**
```
Varta ─► Godric (auth, quota, gateway routing, guardrails)
       ─► MachinaModelUtils.getTargetEndpoint(ns, usecaseId)      (cached per usecase)
          └─ Machina route selection (weighted / A-B / LIVE)
       ─► https://<traefikUrl>/v1/chat/completions
       ─► Traefik ─► one of N vLLM pods ─► GPU
       ◄─ tokens (sync or SSE) ◄─ usage counted by Godric
```
**Three layers of indirection, three levels of "swap without client changes":**
1. **Godric alias/gateway:** swap *which model* (OpenAI ↔ self-hosted Llama).
2. **Machina usecase routes:** swap *which deployment/version* of that model (v3 → v4 canary).
3. **Load balancer:** swap *which pod* (scaling, restarts).

---

## Part 4: What vLLM does inside (interview depth)

| Feature | What it is | Why you care |
| --- | --- | --- |
| **Continuous batching** | New requests join the running batch at every generation step, instead of waiting for the batch to finish | High GPU utilization; much more throughput than one-request-at-a-time |
| **PagedAttention** | KV cache stored in fixed-size "pages" like OS virtual memory | Almost no wasted memory, so more concurrent requests per GPU |
| **Prefix caching** | Reuses the computed KV cache for an identical prompt prefix | **Huge for agents**: the system prompt + tool schemas (thousands of tokens) are the same on every call → faster time-to-first-token, less GPU work |
| **Tensor parallelism** | Splits each layer across GPUs | Needed when the model doesn't fit on one GPU (70B) |
| **Quantization** (AWQ/GPTQ/FP8) | Fewer bits per weight | 2–4× less memory; small quality loss |
| **Multi-LoRA serving** | One base model + many small fine-tuned adapters, chosen per request by the `model` field | **Fine-tuning connection**: 10 teams' fine-tunes on one GPU deployment instead of 10 deployments |
| **Speculative decoding** | A small draft model guesses tokens, the big model verifies | Lower latency |
| **OpenAI-compatible API + tool-call parsers** | The same request/response format as OpenAI | Godric/Varta/LangChain4j work unchanged |

**The metrics you'd watch** (vLLM `/metrics`):
- **TTFT** (time to first token): prefill cost, dominated by prompt length
- **TPOT / ITL** (time per output token): decode speed, what users feel while streaming
- **Throughput** (output tokens/s per GPU)
- `vllm:num_requests_running`, **`vllm:num_requests_waiting`** (queue), **`vllm:gpu_cache_usage_perc`** (KV-cache pressure), GPU utilization, preemptions

---

## Part 5: Scaling and capacity planning

1. **Load test** each model + GPU type (e.g. vLLM's `benchmark_serving`) with **realistic prompts**. For agents, that means long prompts (system + tool schemas + history) and short outputs.
   → Output: "**one replica handles about X output tokens/s and Y concurrent requests at p95 TTFT < 1 s**". Measure it; don't guess.
2. **Replicas** = peak tokens/s ÷ per-replica tokens/s + headroom (N+1 for failures and deploys).
3. **Autoscale on queue length / KV-cache usage**, not CPU (CPU is idle while the GPU is busy).
4. **Cold start is minutes**, so scale **ahead** of traffic (predictive / time-of-day), keep a **minimum warm pool**, and pre-pull images and weights onto nodes.
5. **Connect to Godric quotas:** the sum of client token quotas for a model should be ≤ the **measured capacity** of its deployment. Quotas are how you share fixed GPU capacity fairly. *(This links the deployment story to the quota story.)*
6. **Separate online from batch:** Godric's offline jobs use `MetricsBasedThroughputStrategy` (they read live metrics and slow down when online traffic is high), so batch inference never starves chatbots on the same GPUs.

---

## Part 6: Rolling out a new model version (e.g. a fine-tuned v4)

```
1. Offline eval   : run v4 on a fixed eval set (accuracy, tool-calling correctness, safety, latency) vs v3
2. Deploy v4      : new vLLM deployment (new weights version), warm, health-checked
3. SHADOW         : Machina route for v4 in RoutingMode.SHADOW → gets mirrored traffic, its answers are NOT returned
                    → compare outputs/latency/errors offline
4. Canary         : WEIGHTED route 95/5 → 75/25 (or an A-B experiment via Ablaze), watch:
                    TTFT, error rate, tool-call parse failures, guardrail failure rate (Godric GuardrailEvent),
                    token usage per request, user feedback
5. Promote        : 100% v4; keep v3 warm for a while
6. Rollback       : change the route weights (config, seconds), no client deploy
```
Godric can also do the canary **one level up** (gateway targets: gpt-4o vs self-hosted Llama) with its routes list.

---

## Part 7: Production concerns specific to LLM serving

| Concern | What to do |
| --- | --- |
| GPU node failure | N+1 replicas, spread across nodes/zones; Godric retries/fallback to another target (or an external provider) |
| Long requests during deploys | **Graceful drain**: stop sending new requests, let in-flight generations finish (they can take 30–60 s) before killing the pod |
| Context-length abuse | `--max-model-len`, Godric `max_tokens` defaults, quotas |
| Noisy neighbour | One giant prompt can fill the KV cache → cap prompt size per client; use separate deployments for batch and online |
| Model/weights integrity | Checksums, signed artifacts, a fixed vLLM version, reproducible images |
| Data security | Self-hosting keeps **PII inside the company** (a big reason fintechs self-host); external providers need PII redaction + data-processing agreements |
| Observability | vLLM metrics + Godric `llm-latency`, `llm_token_usage` tagged per llmId/clientId + `inferenceId` for tracing |
| Cost | GPU utilization dashboards, scale-to-min at night, quantization, smaller models for easy tasks (routing by task) |

---

## Part 8: "How would I find out how it's deployed in prod?" (a practical checklist)

1. **Godric:** fetch the LLM entity (`GET` the llm entity for `namespace:llmId`). Is the spec `MACHINA` (self-hosted) or `OPENAI`/`AZURE_OPENAI` (external)? For Machina, note `namespace`, `usecaseId`, `serverType` (VLLM/MLFLOW).
2. **Machina:** look up that usecase. See the **routes**, weights, LIVE/SHADOW, and the **service target** (`serviceDiscoveryName`, `traefikUrl`).
3. **Orchestrator / deployment console:** find the app behind that service name. See the **image** (vLLM version), **GPU type and count**, **replicas**, **start command / flags** (`--max-model-len`, `--tensor-parallel-size`, quantization, LoRA).
4. **Ask the model itself:** `curl https://<traefikUrl>/v1/models` lists the served model names (and max context length).
5. **Dashboards:** vLLM metrics (queue, KV-cache %, TTFT) and Godric metrics (latency, tokens per client).
6. **Azure portal** (for external models): the deployment name, model version, region, TPM/PTU capacity.

---

## Part 9: How to talk about it in the interview (honest framing)

Be clear about **which layer you built** and **which you consumed**:

> "In our setup there are three layers. **Model serving** is owned by our ML platform, Machina: open-weight models like Llama or Granite are served with **vLLM** on GPU nodes, behind Traefik, registered as Machina *usecases* with weighted, A-B, or shadow routes between deployment versions. **The gateway**, Godric, registers each model as an LLM entity: a Machina usecase, or an external OpenAI/Azure deployment. It adds auth, token quotas, guardrails, routing, and metering, behind one OpenAI-compatible API. **The agent layer**, Varta, which I built, only knows a Godric model name.
>
> That layering means a model upgrade is a route change in Machina, a model *swap* is an alias change in Godric, and neither needs a client deploy. For capacity, we size GPUs from load tests: weights plus KV cache decide how many concurrent requests fit, so context length is a cost lever. We autoscale on queue depth and KV-cache usage rather than CPU, and Godric's token quotas share that fixed capacity across teams. New versions go shadow, then canary, then full, with rollback by route weights."

### Likely follow-up questions
| Q | Short answer |
| --- | --- |
| Why vLLM? | Continuous batching + PagedAttention = high throughput; OpenAI-compatible; tool-call parsers; multi-LoRA; prefix caching |
| Why self-host at all? | Data stays inside (PII/compliance), cost at high volume, latency control, fine-tuning freedom; external APIs for the hardest reasoning tasks |
| What limits concurrency on a GPU? | KV-cache memory: context length × concurrent sequences, after the weights |
| Why is autoscaling hard? | Minutes-long cold start + expensive idle GPUs → predictive scaling + warm pool |
| How does fine-tuning reach prod? | Train a LoRA → version it in the artifact store → load it as an adapter on the existing vLLM base (`--lora-modules`) or a new deployment → Machina shadow/canary route → Godric LLM entity/alias |
| What about TTFT for agents? | Long prompts (tool schemas) → prefix caching, trimming tools per agent, compacting tool results |
