# Interview prep: ServiceNow / Moveworks Staff Software Engineer (Core Platform)

> **What this role cares about:** platform building blocks for a *conversational AI* product: reliable, secure, scalable services, REST/RPC, microservices, latency and monitoring, and working well across teams. ML is listed as "nice to have".
> **How to position yourself:** you're a **platform engineer who built an agent platform** (Varta), not a data scientist. Sell it as abstraction, reliability, security, and cost control.

---

## 1. Your 60-second Varta pitch (memorize this)

> "At [ORG_REFER_TAG] we have hundreds of services, each with fixed APIs. Every new data question, like *how many refunds are linked to disputes raised in October*, needed a new endpoint or a manual join by on-call or PSE. I built **Varta**, a framework that turns existing JAX-RS endpoints into LLM-callable tools, with **no new APIs**.
> It works in two ways:
> 1. A **Maven plugin** that scans annotated resource methods at build time, writes `varta-tools.json`, and registers agents on **AgentHub** in the deploy phase. Nothing extra runs in the service.
> 2. A **Dropwizard bundle** that embeds agents in the service and exposes `POST /varta/agents/{agentName}/chat`. It's built on **Sentinel AI**, our Java agent runtime, and every model call goes through **Godric**, our LLM gateway, which handles quotas, guardrails, and audit.
>
> Each tool call goes back through the service's own HTTP layer, so it keeps the same **Olympus `@RolesAllowed`/`@AccessAllowed`** auth. The LLM can't do anything the user couldn't already do. Separately, I built LangChain4j prototypes, including an MCP server and client, to compare the open-source patterns we want to standardize on."

**Concrete numbers to have ready** (fill in your real values): services onboarded, tools exposed, p95 latency, tokens per answer, on-call and PSE tickets reduced.

---

## 2. Architecture (be ready to draw it)

```
User / Ops UI
    │  POST /varta/agents/{agent}/chat   (Olympus bearer token)
    ▼
Host Service (Dropwizard) ── VartaAgentBundle
    │   GodricAgentConfigService: yml → agents → HttpToolBox (TemplatizedHttpTool per method)
    │   VartaAgent (Sentinel AI): systemPrompt, temperature 0.1, seed 42, OlympusTokenExtension
    ▼
Godric LLM Gateway  (OpenAI-compatible)  ── quota per (namespace, llmId, clientId)
    │                                     ── input/output guardrails (prompt-injection, Granite Guardian)
    │                                     ── audit events (LLMEvent, GuardrailEvent, QuotaIngestor)
    ▼
Model (hosted)  ──tool_call──►  back into Host Service's own REST endpoint (auth inherited)
```

**Design decisions and why you made them:**

| Decision | Reason |
| --- | --- |
| Use existing REST endpoints as tools (no new code paths) | Security inheritance, zero housekeeping APIs, single source of truth |
| Build-time scanning (Maven Mojo) vs runtime reflection | No startup cost, a reviewable artifact (`varta-tools.json`), and a maker-checker step before agents go live |
| `@JsonSubTypes` polymorphic request → **one tool per subtype** | LLMs can't fill in polymorphic schemas reliably. A flat schema per subtype gives much better tool-call accuracy |
| Unique generated tool names, ≤64 chars, no overloads | OpenAI function-calling limits |
| `temperature 0.1`, `seed 42` | Analytics and ops answers need to be deterministic and reproducible |
| Gateway (Godric) instead of calling the provider directly | Central place for quotas, keys, routing, guardrails, and audit. Services never hold provider keys (`apiKey("IGNORED")`) |
| Hystrix / bulkhead config in the bundle | A slow LLM or tool can't exhaust the host service's threads |

---

## 3. "How did you deploy it?"

Make it clear there are **three things being deployed**: the framework, the agents, and the model.

1. **The framework** is a library. `agent-core`, `agent-bundle`, and `agent-maven-plugin` are published as Maven artifacts through CI (`[CI] Updating poms for 1.0.4-SNAPSHOT`). Services add the bundle and a `varta:` block to `application.yml`.
2. **Agents** are deployed in one of two ways:
   - *AgentHub mode:* the `scan-tools` goal runs in `process-classes` and produces `varta-tools.json`. The `register-agents` goal runs in `deploy` and calls POST/PUT `/api/agent` on AgentHub. **Maker-checker** approval happens before an agent is live.
   - *Embedded mode:* agents load at service startup (`GodricAgentConfigService.init`) and roll out with the service's normal container deployment (canary → full). Rollback means rolling back the service or turning off the agent config.
3. **The model** isn't hosted by Varta. It's behind **Godric**, which routes to the hosted model. For local development and the LangChain4j prototypes I ran **Ollama** (OpenAI-compatible `/v1`) with quantized GGUF models (Llama-3.2-1B Q4, Qwen 9B), and set the context window in the model tag (`ollama create qwen3.5-9b-32k --parameter num_ctx 32768`), because Ollama ignores `num_ctx` sent over the OpenAI endpoint.

**Likely follow-up: "How would you self-host a model at scale?"**
- Serving: **vLLM** or TGI (continuous batching, PagedAttention KV-cache) on GPU nodes in K8s; an OpenAI-compatible API so clients don't change.
- Autoscale on **queue depth / tokens per second / GPU utilization**, not CPU. Keep a warm pool, because model load time is long.
- Quantization (AWQ/GPTQ/INT8) to fit on cheaper GPUs; tensor parallelism for large models.
- Route by model: a small, cheap model for routing and classification, a large one for reasoning. Fall back to another model or provider when one fails.
- Release safely: shadow traffic → canary with an **eval gate** (golden set) → full rollout. Version the prompt and model together.

---

## 4. "Did you fine-tune?" (answer honestly, it's a strong answer)

**Don't claim fine-tuning you didn't do.** Say:

> "We deliberately **didn't fine-tune**. Our problem is *grounded tool-use over live data*, not new knowledge or a new style. The facts change every second, so fine-tuning would bake in stale data. We got accuracy from **better tool descriptions, a flattened schema, a strict system prompt, low temperature, and limiting which tools the model sees**. Those changes are cheap, versionable, and easy to roll back."

The levers you actually used, in order of impact:
1. **Tool metadata quality:** method descriptions and `@P` param descriptions, plus a *description override* mechanism (roadmap §8.2) so operators can improve descriptions without code changes.
2. **Schema shaping:** one tool per JSON subtype, and only the needed parameters exposed.
3. **Prompt constraints:** in your `MovieAssistance` you found that a 1B model echoed the JSON schema back as arguments (`{"type":"object","properties":...}`), so you added "Never pass type/required/properties". That's a nice story to tell.
4. **Determinism:** temperature 0 to 0.1, a fixed seed.
5. **Tool-set narrowing:** an allowlist (`allowedTools` for the Jira MCP), since fewer tools means better selection.

**When you *would* fine-tune** (shows judgment):
- A consistent output format or domain jargon that prompting can't fix.
- Making a **small model** good at tool routing, to cut cost and latency. Use **LoRA/QLoRA (PEFT)** on logged, *human-labelled* tool-call traces (question → correct tool + args).
- Pipeline: collect traces → label/clean → mask PII → SFT with LoRA → evaluate against the golden set (tool-selection accuracy, argument exactness, hallucination rate) → canary.
- Other options: **DPO/RLHF** from thumbs-up/down feedback, and **distilling** a big model into a small router.
- Know the trade-off: RAG = *knowledge*, fine-tuning = *behaviour/format*, tools = *live data*.

---

## 5. Guardrails (expect deep questions)

Answer in **layers**. Interviewers like defense in depth.

| Layer | What you have / built | Where |
| --- | --- | --- |
| **AuthN/AuthZ** | Olympus token required on chat. Every tool call goes back through the service's endpoint, so `@RolesAllowed` is enforced *per tool call*. The LLM gets no extra privilege | `VartaAgentResource`, `OlympusTokenExtension` |
| **Input guardrails** | Godric runs *input* guardrails before the model: **ProtectAI prompt-injection** classifier, **IBM Granite Guardian** (harm, jailbreak), and Java rule engine rules | `GuardrailManagerImpl.evaluateInput`, `ProtectAiPromptInjectionRule`, `InputGraniteGuardianRule` |
| **Tool guardrails** | Allowlist of exposed methods (only the FQNs configured in yml), read-only tool sets (Jira `allowedTools`), `maxToolCallingRoundTrips=5`, parameter typing | yml config, `LLMConfig` |
| **Output guardrails** | Godric *output* evaluation (Granite Guardian), plus the prompt rule "Never invent... not present in a tool result" (grounding) | `evaluateOutput`, system prompts |
| **Policy as config** | Guardrails are entities per *namespace/guardrailId* with exec spec: **mode** (e.g. PARALLEL), **timeoutMs**, **skipOnFailure** (fail-open vs fail-closed) | `GuardrailExecSpec` |
| **Audit** | Every guardrail decision is emitted as a `GuardrailEvent` (gateway, client, inferenceId, INPUT/OUTPUT) | `EventIngestor` |

**Key trade-off to discuss: fail-open vs fail-closed.**
In Godric, a guardrail **timeout returns `SKIPPED`**, and an error returns `SKIPPED` or `FAILED` depending on `skipOnFailure`. Fail-open protects latency and availability, fail-closed protects safety. Choose per use case: for an internal read-only ops bot, fail-open with audit is fine. For PII or actions that write, fail-closed.

**Latency of guardrails:** run them **in parallel** with each other (`ParallelGuardrailEngine`) and give each a timeout budget. Optionally run the input check in parallel with the first LLM call, and cancel the call if the check fails.

**Prompt injection via tool output** (indirect injection), a classic question: data returned by an API ("ignore previous instructions...") is untrusted. Mitigations: treat tool output as data, not instructions; keep tools read-only or least-privilege; require human-in-the-loop for any write; run output guardrails; never give the agent more permission than the user has (you already do this).

**PII:** mask or redact before the prompt and in logs; classify data per tool (roadmap §8.1 `data classification`); set retention policies for conversation logs.

---

## 6. Token control, usage, and abuse prevention

**What exists today:**
- **Gateway quota:** Godric `QuotaManager` per `(namespace, llmId, clientId)` with `allotted / consumed / remaining` stats (`GET /v1/quota/{namespace}/{llmId}`), and `QuotaIngestor` for consumption events. **Each caller is identified by Olympus ID**, so quota is per caller and can't be bypassed.
- **Per-request caps** (LangChain4j work): `maxOutputTokens` / `maxTokens`, `maxToolCallingRoundTrips=5` (LangChain4j's default is about 100, which can loop forever with a small model), `reasoningEffort: none` (stops a thinking model from spending the whole budget on reasoning and returning an empty answer).
- **Context control:** `MessageWindowChatMemory.withMaxMessages(n)` per `@MemoryId` session, which evicts whole turns so a tool result is never orphaned.
- **Tool output compaction:** `CompactingToolProvider` strips avatar URLs, account IDs, emails, and colours from Jira JSON. **About 95 KB → a fraction of that**. Before this, Ollama silently dropped the oldest messages, *including the user's question*. That's a great war story.
- **Telemetry:** `TokenUsage` from `ChatResponse` is mapped into `AIResponse` along with latency.

**What you'd design next (Gap doc §4.8):**
- **Rate limit** per user, tenant, and agent: a token bucket in Redis for *requests/min* **and** *tokens/min* (TPM). Return 429 with `Retry-After`.
- **Token budget per request**: estimate prompt tokens (tokenizer) before calling, and reject or trim if over budget. Summarize older history instead of only windowing it.
- **Concurrency limits**: max in-flight sessions per user, plus a bulkhead per agent.
- **Abuse detection**: repeated identical queries, very long inputs, high refusal or guardrail-hit rate per user → throttle, then block, then alert.
- **Cost controls**: cache answers for identical queries (semantic cache), cache prompt prefixes, route simple intents to a small model, set budget alerts per tenant.
- **Metrics**: prompt, completion, and total tokens per request; tokens per *successful* answer; tool calls per request; refusal rate; quota-exhaustion events (roadmap §11.2).

---

## 7. LangChain4j: concepts to know cold (from AI Services docs + your code)

| Concept | One-liner | Your code |
| --- | --- | --- |
| **AiServices** | Declare an interface, and LangChain4j builds a proxy that turns calls into chat requests and parses the result | `AiServices.builder(GitAssistance.class).chatModel(...).tools(...)` |
| `@SystemMessage` / `@UserMessage` / `@V` | Prompt templates; `{{var}}` filled from `@V` params | `SprintAIAgent` with `{{projectKey}}`, `{{userQuery}}` |
| `@MemoryId` + `chatMemoryProvider` | One memory per user/session, so conversations don't leak into each other | `MessageWindowChatMemory` per sessionId |
| **ChatMemory** types | `MessageWindowChatMemory` (N messages) vs `TokenWindowChatMemory` (N tokens, needs a tokenizer). Use a persistent `ChatMemoryStore` (Redis/DB) in production | you use message window |
| `@Tool` / `@P` | A Java method becomes a function spec; LangChain4j runs the loop (call → execute → feed back) | `MovieToolService`, `GitToolService` |
| `maxToolCallingRoundTrips` (`maxSequentialToolsInvocations`) | Upper limit on the agent loop | 5 |
| **ToolProvider** | Supplies tools dynamically per request (e.g. from MCP) | `CompactingToolProvider` decorator |
| **Result<T>** | Content + `tokenUsage()` + `sources()` + `toolExecutions()` + `finishReason()` | used in `LangChainService` |
| **Streaming** | `TokenStream` with `onPartialResponse`, `beforeToolExecution`, `onToolExecuted`, `onCompleteResponse` | `GitStreamingAssistance` |
| **Structured output** | Return a POJO/enum from the AI Service method → JSON schema / response format | worth mentioning for "response contracts" |
| **Guardrails** | `InputGuardrail` / `OutputGuardrail` interfaces in AiServices (`@InputGuardrails`, `@OutputGuardrails`); output guardrails can **retry / reprompt** | the LangChain4j equivalent of Godric |
| **RAG** | `contentRetriever` / `retrievalAugmentor` (embedding store + model) | Varta roadmap §13 |
| **MCP** | `McpToolProvider` over `StreamableHttpMcpTransport`; your server uses the MCP Java SDK on Jersey `POST /mcp` with progress-token streaming | LangChainMcpServer / Client |
| **Agentic** (`langchain4j-agentic`) | `@Agent` with `outputKey`; sequential, parallel, and supervisor workflows | `SprintAIAgent` |
| Moderation | `@Moderate` + `ModerationModel` |  |

**Questions they may ask about it:**
- *Why cache AiService instances?* They hold the HTTP client and the memory of every session. Building one per request loses the conversation and leaks clients (your `computeIfAbsent(model|tools)`).
- *Memory in a horizontally scaled service?* An in-memory store breaks with multiple pods. Use a `ChatMemoryStore` backed by Redis/DB keyed by memoryId, or sticky sessions.
- *Why MCP?* It's a standard tool protocol, so the same tool server works with any agent or framework. Tools are discovered at runtime. It also separates the tool owner from the agent owner. **Varta's next step = expose service tools as MCP.**

---

## 8. Be prepared for gaps (own them, don't hide them)

An interviewer reading your code would notice these. Present them as **known items with a fix plan**:
- `OlympusTokenExtension` returns a placeholder token and `AgentRequestMetadata` uses a fixed `sessionId("s1")`. The real design is to *propagate the caller's token* (`customParams(TOKEN, authToken)`) and use a real session/user ID. Otherwise all users share memory and privilege.
- `UnSecureOkHttpClient`: if it disables TLS verification, it's only for internal/dev; production needs proper trust stores.
- No streaming chat API yet, no golden-set eval in CI, no per-user rate limiting in Varta itself (it relies on Godric quota). These are all on your roadmap or Gap doc. Talk about them as **Phase 2/3**.

This shows Staff-level ownership: *"I wrote the Gap ReadMe for leadership: SLOs, eval gating, prompt versioning, quotas, incident runbooks."*

---

## 9. Likely questions, with short answers

1. **"Walk me through a request end to end."** Auth → resolve agent → build input → Sentinel agent → Godric (quota check, input guardrail) → model returns tool_call → TemplatizedHttpTool calls the service endpoint with the user's token → result back to the model → output guardrail → response. Mention timeouts and Hystrix at each hop.
2. **"How do you prevent hallucination?"** Grounding-only prompt, low temperature, tools as the only source of facts, refusal reason codes (missing context, insufficient permissions, low confidence), output validation, eval set with hallucination cases.
3. **"How do you test an LLM system?"** Golden questions → assert *tool selected + args*, not exact text; LLM-as-judge for answer quality; prompt-injection test cases; run in CI before release; track pass rate per prompt/model version.
4. **"Latency is 8s, fix it."** Stream tokens; fewer tools in context (pre-rank by intent); compact tool output; parallel tool calls; smaller model for routing; cache metadata; tune connection pools and timeouts; run guardrails in parallel.
5. **"An LLM provider is down."** Gateway fallback model, circuit breaker, a degraded response ("can't answer now"), queue non-urgent requests; SLO and error-budget alerting.
6. **"Multi-tenant fairness?"** Per-tenant TPM/RPM token buckets, a separate bulkhead per agent, priority queues, quota visibility via an API.
7. **"How do you version prompts and tools?"** Store prompt and tool-description overrides in config with versions; record version on every trace; canary new versions behind the eval gate; one-click rollback.
8. **"Observability?"** OTel spans per hop (chat → model → each tool), with inferenceId correlation; metrics: p50/p95, tokens, tool-call count, fallback/refusal rate; feedback (thumbs) linked to the trace.
9. **"Why build Varta instead of using LangChain?"** Java/Dropwizard stack, inherited Olympus security, an internal gateway, zero new APIs. You still adopt LangChain-style *concepts* (tool, memory, retriever, callbacks) without locking in to one framework (roadmap §9).
10. **System design: "Design a Moveworks-style enterprise assistant."** Intent routing → plugin/agent registry (like AgentHub) → tool execution with user-delegated auth → RAG over KB → guardrails → conversation state store → async actions (ticket creation) with human-in-the-loop → analytics. Varta maps onto this directly, so draw it from your system.

---

## 10. Non-AI Staff topics (the JD weights these heavily)

- Distributed systems: idempotency, retries with backoff and jitter, circuit breakers, bulkheads, back-pressure, exactly-once vs at-least-once.
- REST vs gRPC: streaming, schema evolution, deadlines.
- Scaling: caching layers, sharding (you have the `dropwizard-db-sharding-bundle` context), queues.
- Behavioural (STAR): influencing a roadmap across teams (the Varta roadmap/Gap doc), a deadline with dependencies, disagreeing in design review, mentoring.

Good luck. Lead with **platform thinking**: abstraction, safety by default, measurable, cost-aware.
