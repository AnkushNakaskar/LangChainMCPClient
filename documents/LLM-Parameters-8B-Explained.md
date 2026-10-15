# What does "8B" mean? LLM parameters explained from scratch

> Background knowledge for the interview. Figures are public numbers for Llama‑3‑8B and similar models and are rounded. None of this comes from [ORG_REFER_TAG] code.

**One line:** **B = billion parameters.** A parameter is one learned number (a weight) inside the model. "8B" means the model is made of about **8,000,000,000 numbers** that were tuned during training. To run the model, every one of those numbers must sit in GPU memory.

---

## 1. Start tiny: what is a "parameter"?

Say you want to predict the price of a house from its size:

```
price = w × size + b
```

- `w` and `b` start random. Training looks at many real houses and nudges `w` and `b` until the predictions are good.
- After training, say `w = 5000` and `b = 200000`.
- **This model has 2 parameters.** The "knowledge" of the model *is* those 2 numbers.

Now predict from 3 inputs (size, rooms, distance):

```
price = w1×size + w2×rooms + w3×distance + b      → 4 parameters
```

A neural network does this thousands of times, in layers:

```
layer: 4096 inputs → 4096 outputs  = a 4096 × 4096 weight matrix
                                   = 16,777,216 parameters (16.8M) in ONE matrix
```

Stack a few hundred such matrices and you reach **billions**. That is all "8B" is: **the total count of numbers in all the weight matrices.**

> Analogy for Java engineers: the model file is basically a huge `float[]` of 8 billion entries plus a fixed algorithm (the transformer) that multiplies your input through it. The code is small. The numbers are what matter.

---

## 2. Where the 8 billion actually live (Llama‑3‑8B)

Public config: vocab = 128,256 tokens, hidden size = 4096, 32 layers, MLP size = 14,336, 32 query heads, 8 KV heads (GQA), head dim = 128.

| Part | Math | Parameters |
| --- | --- | --- |
| Token embedding table (token id → vector) | 128,256 × 4096 | ≈ 525 M |
| **Per layer: attention** Q, K, V, O | 4096×4096 + 4096×1024 + 4096×1024 + 4096×4096 | ≈ 42 M |
| **Per layer: MLP** (gate, up, down) | 3 × 4096 × 14,336 | ≈ 176 M |
| Per layer total |  | ≈ 218 M |
| × 32 layers |  | ≈ 6.98 B |
| Output head (vector → score per token) | 4096 × 128,256 | ≈ 525 M |
| **Total** |  | **≈ 8.03 B** ✅ |

Takeaways:
- About **80% of parameters are in the MLP blocks**, which roughly store "knowledge". Attention moves information between tokens.
- "Bigger model" = wider matrices (hidden size) + more layers.

---

## 3. Why the parameter count matters to an engineer: memory

Each parameter is stored in some number format:

| Precision | Bytes / param | 8B model | 70B model |
| --- | --- | --- | --- |
| FP32 | 4 | 32 GB | 280 GB |
| **FP16 / BF16** (normal serving) | 2 | **16 GB** | **140 GB** |
| FP8 / INT8 | 1 | 8 GB | 70 GB |
| INT4 (AWQ/GPTQ/GGUF Q4) | 0.5 | ~4–5 GB | ~35–40 GB |

**Rule of thumb: memory for weights ≈ params (in B) × bytes per param = GB.**

So:
- **8B in BF16 = 16 GB** → fits on one 24 GB GPU (barely) or comfortably on one 80 GB A100/H100.
- **70B in BF16 = 140 GB** → does **not** fit on one 80 GB GPU → must be split across 2–4 GPUs (*tensor parallelism*, `--tensor-parallel-size 4` in vLLM).
- **Quantize** 70B to INT4 (~40 GB) → fits on one 80 GB GPU, with a small quality loss.

### But weights are not the only thing: the KV cache

For each token in each active conversation, the model stores Keys and Values for every layer:

```
KV per token = 2 (K and V) × 32 layers × 8 KV heads × 128 dims × 2 bytes
             = 131,072 bytes ≈ 128 KB per token   (Llama‑3‑8B, BF16)
```

| Scenario | KV cache |
| --- | --- |
| 1 chat with 4k tokens | 0.5 GB |
| 1 chat with 32k tokens | 4 GB |
| 50 concurrent chats × 8k tokens | 50 GB |

On an 80 GB GPU: 16 GB weights + ~56 GB free for KV cache. **That free memory decides how many users you can serve at once.** This is why **context length drives cost**, and why vLLM's PagedAttention (which allocates KV memory in small pages) matters.

---

## 4. Why it matters: speed and cost

- **Compute:** generating 1 token costs about **2 × params FLOPs** → 8B ≈ 16 GFLOPs per token, 70B ≈ 140 GFLOPs.
- **Speed is usually memory bound, not compute bound.** For each new token the GPU must read *all* weights from memory once.
  - H100 bandwidth ≈ 3.35 TB/s. Reading 16 GB takes ≈ 5 ms → at most **~200 tokens/s for a single stream** of an 8B model.
  - 70B (140 GB, split over GPUs) → noticeably slower per stream.
  - **Batching** many users together re-uses one weight read for all of them. That is how vLLM gets high throughput.
- **Practical impact:** 8B is about 10× cheaper and faster than 70B. Bigger is smarter but costs more per token and needs more GPUs.

---

## 5. What size gives you (rough intuition)

| Size | Example models | Typical use | Hardware (BF16) |
| --- | --- | --- | --- |
| < 1B | DeBERTa (~184M), BERT classifiers | Classification: injection detection, toxicity, intent | CPU or small GPU |
| 1–3B | Llama‑3.2‑1B/3B, Phi | On-device, simple extraction, routing | 1 small GPU |
| **7–8B** | Llama‑3‑8B, Mistral‑7B, Qwen‑7B | Summaries, intent, simple tool calling, **good target for fine-tuning** | 1 GPU |
| 30–70B | Llama‑3‑70B, Qwen‑72B | Strong reasoning, reliable multi-step tool use | 2–4 GPUs |
| 400B+ / frontier | Llama‑3.1‑405B, GPT‑4‑class | Hardest reasoning, planning | Multi-GPU / multi-node, or an API |

**More parameters = more capacity to store facts and patterns** → better reasoning, fewer hallucinations, better instruction following. But returns diminish, and a **fine-tuned 8B often beats a general 70B on one narrow task** (e.g. classifying dispute types).

---

## 6. Things that confuse people (interview traps)

1. **Parameters ≠ context window.** "8B, 128k context" means 8B numbers, and it can *read* up to 128k tokens. They are independent.
2. **Parameters ≠ training data.** Llama‑3‑8B has 8B params but was trained on ~15 **trillion** tokens. Data quality and amount matter as much as size. (Chinchilla rule: ~20 training tokens per parameter is "compute optimal"; modern models train far beyond that so they are better at a small size.)
3. **MoE (Mixture of Experts): total vs active parameters.** Mixtral **8×7B** has ~47B total parameters, but only ~13B are **active** per token (a router picks 2 of 8 expert MLPs).
   - Memory cost = **total** (all experts must be loaded) → ~94 GB in BF16.
   - Speed/compute cost ≈ **active** → runs about as fast as a ~13B model.
4. **Quantization names.** "Llama‑3‑70B‑AWQ" or "Q4_K_M" = the same model compressed to ~4 bits. Same parameter count, less memory, slightly lower quality.
5. **Distillation.** A small model trained to imitate a big one (e.g. an 8B learning from a 70B's answers). Cheaper to serve while keeping much of the quality.

---

## 7. Connection to fine-tuning (why LoRA exists)

- **Full fine-tuning** updates all 8B numbers. With the Adam optimizer in mixed precision you need ~**16 bytes per parameter** (weights + gradients + optimizer state) → **~128 GB for 8B**, i.e. several GPUs, and you get a whole new 16 GB model file per fine-tune.
- **LoRA:** freeze the 8B, and next to a big 4096×4096 matrix add two thin matrices A (4096×16) and B (16×4096):
  - Original matrix: 16.8M params (frozen).
  - LoRA adds: 2 × 16 × 4096 = **131k params** (trained), less than 1%.
  - Adapter file is tens of MB instead of 16 GB.
  - **vLLM can serve one base model plus many LoRA adapters** (`--enable-lora`). For example, one adapter for "dispute classification" and another for "merchant FAQ tone", all on the same GPU.
- **QLoRA:** the frozen base is kept in 4-bit → you can fine-tune an 8B on a single 24 GB GPU.

---

## 8. Mapping to Varta / Godric (how to talk about it)

Pick the **smallest model that passes the eval** for each job:

| Job in the system | Sensible size | Why |
| --- | --- | --- |
| Godric input guardrail (prompt-injection classifier like ProtectAI's DeBERTa) | ~0.2B | Classification only, must add ~0 latency, runs in parallel with the LLM |
| Intent routing / "which agent?" | 1–8B (or fine-tuned 8B) | Short output, high volume, cheap |
| Varta main agent: multi-step tool calling over disputes/payments | 70B‑class or a frontier API model | Tool-call reliability and reasoning matter more than cost |
| Summarizing a tool result for the user | 8B | Simple and high volume |

**Cost lever story:** "Not every LLM call needs the biggest model. Routing simple steps to an 8B and keeping the 70B or frontier model for planning cuts tokens and GPU cost a lot. Godric makes this a config change, because the LLM entity or alias is swapped without changing Varta's code."

---

## 9. Quick interview Q&A

**Q: What does 8B mean?**
8 billion learned weights. In BF16 that is ~16 GB of GPU memory just for the weights, plus KV cache per token of context.

**Q: Can you run a 70B on one 80 GB GPU?**
Not in BF16 (140 GB). Either split it across GPUs with tensor parallelism, or quantize it to INT4/FP8.

**Q: Why is generation slow even on a powerful GPU?**
Decoding is memory-bandwidth bound: every token reads all the weights. Batching (continuous batching in vLLM) amortizes that read across users.

**Q: What limits concurrency on a model server?**
KV-cache memory: GPU memory minus weights, divided by (tokens per request × KV bytes per token). Long contexts mean fewer concurrent users.

**Q: Bigger model or fine-tuned smaller model?**
Start with a big model to prove quality and build an eval set. Then try a fine-tuned or LoRA 8B on the narrow task. If it passes the eval, ship the cheaper one, with the big model as a fallback.

**Q: What is "8×7B"?**
Mixture of Experts. You pay memory for all ~47B parameters, but compute only for ~13B active per token.
