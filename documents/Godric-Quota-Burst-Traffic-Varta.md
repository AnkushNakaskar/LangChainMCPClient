# Godric token quota: how it works under burst traffic (with Varta examples)

> Based on `QuotaManagerImpl`, `QuotaResponse`, and `ChatCompletionDelegate` in the Godric code. The numbers (lag, limits, token sizes) are **illustrative**. Replace them with real values if you know them.

---

## 1. The idea in plain words

Think of it like an **electricity meter, not a prepaid recharge**:
- Godric **doesn't deduct tokens before** a call. It lets the call happen, reads the actual usage from the LLM response, and **records it afterwards**.
- A separate pipeline keeps a **running total** per (model, client) for the last 1 minute, 5 minutes, and 1 day.
- Before each new call, Godric **looks at the total**: *"Is anything left in all 3 windows?"* If not, it rejects.

Because the total is updated **with some delay**, there's a short period where Godric is looking at a slightly old reading. **That delay is the source of every burst problem below.**

---

## 2. The data loop, step by step

```
                (A) WRITE PATH: after each LLM call
┌──────────┐  usage{prompt=4200, completion=300, total=4500}
│   LLM    │──────────────────────────────┐
└──────────┘                              ▼
                       ChatCompletionDelegate.logLLMMetrics()
                       → quotaManager.logUsage(ns, llmId, clientId, usage)
                       → QuotaIngestor → LLMEvent ─► Kafka topic          (~ms)
                                                       │
                                                       ▼
                                                Pinot real-time table      (ingest lag: ~seconds)
                                                       │  SUM(totalTokens) WHERE llmId, clientId
                                                       │  AND time > now-1m / now-5m / now-1d
                                                       ▼
                                          Yoda entities (feature store):
                                            tokenPerMin   ["gpt4o|dispute-service"] = 86,000
                                            tokenPer5Min  ["gpt4o|dispute-service"] = 310,000
                                            tokenPerDay   ["gpt4o|dispute-service"] = 2,400,000

                (B) READ PATH: before each LLM call
Request ─► assertAvailability(ns, llmId, clientId)
            1. allotted = client override ?: LLM default          (from config)
            2. consumed = yoda.getMultipleEntities(3 keys)         (network call, every request, no local cache)
            3. remaining = allotted - consumed                     (per window)
            4. if ANY window remaining <= 0  → throw RATE_LIMIT ("rate limit exceeded", context=remaining)
            5. if step 2 throws              → log "rate-limit-system-failure" and ALLOW  (fail-open)
```

### What "lag" means
The total time from *"LLM returned usage"* to *"Yoda shows it"* is roughly:
```
lag = Kafka publish + Pinot ingestion/segment + Yoda refresh/query  ≈ a few seconds to tens of seconds
```
During the lag, **requests that are already in flight or arrive quickly don't see each other's usage**.

### Three important details from the code
1. **`isExhausted()` is `remaining <= 0`.** If 1 token is left, a request that will use 8,000 tokens is **still allowed**. There's no estimation of request size.
2. **The key is `llmId|clientId`.** `clientId` is the **Olympus identity of the calling service**, not the end user. Namespace isn't in the key.
3. **The quota is on the target LLM, not the gateway.** If a gateway routes randomly to gpt-4o or llama, each has its own budget.

---

## 3. Setting up the Varta example

- **Host service:** `dispute-service` embeds the Varta agent `DisputeAgent`.
- Varta calls Godric with **dispute-service's Olympus identity** → `clientId = dispute-service`.
- **Model:** `gpt4o` (via a Godric gateway).
- **Quota config** (illustrative):
  ```yaml
  llmClientLevelQuota:
    gpt4o:
      - clientId: dispute-service
        quota: { tokensPerMinute: 100000, tokensPerFiveMinute: 400000, tokensPerDay: 5000000 }
  ```
- **What one user message costs:** an agent "turn" is **not one LLM call**:
  ```
  User: "Why was my refund for txn T123 not credited?"
    LLM call 1: system prompt + tool schemas + history + question → decides: call getTransaction(T123)   ≈ 4,000 in + 100 out
    Tool runs (dispute-service REST, user's token)
    LLM call 2: everything above + tool result (JSON)              → decides: call getRefundStatus(R9)   ≈ 5,500 in + 100 out
    Tool runs
    LLM call 3: everything above + 2nd tool result                 → final answer                         ≈ 6,500 in + 250 out
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────
  1 user message ≈ 3 LLM calls ≈ 16,500 tokens
  ```
  > **The key point for agents:** prompt tokens grow with every tool round-trip, because the whole conversation is re-sent each time. So **100k tokens/min is only about 6 agent turns per minute**, not "100 chats".

---

## 4. Scenarios

### Scenario A: normal traffic (everything works)
4 users/min × 16.5k = **66k tokens/min**. All windows stay under the limit; `remaining > 0`, so every call passes. The dashboard gauge `llm_quota.tokenPerMinute.remaining{clientId=dispute-service}` hovers around 34k.

### Scenario B: burst traffic (sale day / outage: everyone asks "where is my refund?")
**Assume lag = 15 s.** At 10:00:00, 40 users send a message within 5 seconds.

```
Time      What Godric sees in Yoda (consumed/min)   Decision           Real usage happening
10:00:00  0                                         ALLOW all          40 turns start → 40 × call-1 (≈4.1k each)
10:00:03  0  (lag, usage not visible yet)           ALLOW all          40 × call-2 (≈5.6k each)
10:00:06  0                                         ALLOW all          40 × call-3 (≈6.75k each)
10:00:10  0                                         ALLOW              real total ≈ 660k tokens  ← 6.6× the limit!
10:00:15  ~164k (first batch becomes visible)       REJECT (remaining = -64k)
10:00:20  ~660k                                     REJECT
...       every request gets RATE_LIMIT until the 1-min window rolls past the burst
10:01:10  0 again                                    ALLOW              the next wave comes in → overshoot again
```

**What happened:**
1. **Overshoot:** about 660k tokens used against a 100k/min limit, because all 120 calls were checked against an old reading of "0 consumed".
2. **Lockout after the burst:** consumed is now far above the limit, so **everyone is blocked**, including users who weren't part of the burst, until the window moves on.
3. **5-minute and daily windows:** the 660k also counts in the 5-minute window (limit 400k), so even after the 1-minute window clears, **the 5-minute window keeps blocking for up to 5 minutes**. This is the "sawtooth": a burst, then a long lockout, then another burst.

### Scenario C: an agent turn half-finished ⚠️ (specific to agents)
A user's turn passes call 1 and call 2 (the tool `raiseDispute` **actually ran** and created a dispute). Then the quota becomes visible as exhausted, so **call 3 gets `RATE_LIMIT`**.
- Without handling: the user sees *"error"*, retries, and the agent **raises a second dispute**.
- **So Varta needs:** idempotency keys on mutating tools, a friendly fallback (*"Your dispute D-778 was created; I'm busy right now, details shortly"*), and saving the tool results to session memory so a retry continues instead of repeating.

### Scenario D: one abusive user (noisy neighbour)
The quota is per **service** (`dispute-service`), not per end user. One user with a script sends 500 messages in a minute and uses up the whole budget, so **every other customer** of DisputeAgent gets rate-limited.
→ **Godric can't fix this; it only knows the service.** Varta must add **per-user** limits (it knows the userId from the user's Olympus token).

### Scenario E: the quota system itself is down
Yoda or Pinot is unreachable, so `getConsumptionStats` throws, `rate-limit-system-failure` is logged, and the request is **allowed**. If this happens **during** Scenario B, there's **no limit at all**. Availability wins, but you're exposed to cost and to provider-side 429s. Mitigation: a local fallback limiter on each Godric node (see §6).

### Scenario F: gateway with two targets
The gateway routes 50/50 at random between `gpt4o` and `llama-70b` (Machina). gpt4o's quota runs out, but llama's doesn't. Random routing **doesn't know about quota**, so about 50% of calls still go to gpt4o and fail.
→ Improvement: **quota-aware routing / fallback**: if the chosen target is exhausted (or returns 429/5xx), try the next target.

### Scenario G: windows catching different patterns
| Pattern | Which window blocks |
| --- | --- |
| A 10-second spike of 150k | **per minute** |
| Steady 90k/min for 5 min = 450k (each minute is under 100k!) | **per 5 minutes** (400k) |
| A batch job at 60k/min all day = 86M | **per day** (5M) after about 83 minutes |
So three windows = **burst control + sustained-load control + cost budget**.

---

## 5. The design trade-offs, explained

| Trade-off | What it means | Why it's acceptable | When it's not |
| --- | --- | --- | --- |
| **Post-paid / eventually consistent** | Count after the call, aggregate with a lag | The hot path is a cheap read, no locking and no central counter to contend on; the provider's real usage numbers are used (exact, not estimated) | Strict budgets, small limits, heavy bursts (Scenario B overshoot) |
| **Fail-open** | Quota infra down → allow | An LLM platform outage is worse than a temporary cost overrun | Expensive external models during a burst (Scenario E) |
| **Token-based** | Limit on tokens, not requests | Tokens = cost and provider capacity; one 30k-token request ≠ one 200-token request | Doesn't stop a flood of tiny requests or high concurrency (add a request/concurrency limit at the edge) |
| **Identity from Olympus** | `clientId` comes from the authenticated principal | Can't be spoofed with a header; good attribution | It's per *service*, not per *end user* (Scenario D) |
| **`debugMode` / per-window disable flags** | Compute and emit metrics without enforcing | Shadow mode: tune limits on real traffic before turning them on | — |

---

## 6. How to fix bursts: the improved design (Staff-level answer)

### 6.1 Reserve before, settle after (pre-paid)
```
Before call:
  estimate = countTokens(messages) + max_tokens            // tokenizer, or chars/4 as a fallback
  for each window w in {1m, 5m, 1d}:
      key = "quota:{llmId}:{clientId}:{w}:{bucket}"         // bucket = current minute / 5-min / day
  atomically (Redis Lua / Aerospike operate()):
      if any(current[w] + estimate > limit[w]) → reject 429 with Retry-After
      else INCRBY each key by estimate, set TTL
After call:
  delta = actual.totalTokens - estimate                    // usually negative (max_tokens is an upper bound)
  INCRBY each key by delta                                 // give back unused tokens
  still emit LLMEvent → Kafka → Pinot  (for dashboards, billing, audit)
```
- **No more overshoot:** the 120 burst calls in Scenario B see each other's reservations **immediately**.
- Capping `max_tokens` keeps estimates tight (otherwise reservations are too pessimistic).
- Keep Pinot for analytics and reconciliation, and Redis/Aerospike for enforcement.
- Use a **sliding-window or token-bucket** algorithm instead of fixed buckets, to avoid a double burst at the window boundary.

### 6.2 Local limiter as a first line (and as the fail-open fallback)
Each Godric node keeps an in-memory token bucket of `limit / N nodes`. It's cheap and needs no network. If Redis or Yoda is down, use the local bucket instead of failing fully open.

### 6.3 Cut the per-request Yoda call
Today every request does a 3-key Yoda read. Cache `consumed` locally for about 1 second per key: it's already lagging anyway, and this removes a network hop from every LLM call under heavy load.

### 6.4 Better 429 behaviour
- Return **`Retry-After`** based on which window ran out (a per-minute limit → retry within seconds; a daily limit → don't retry).
- Clients use **exponential backoff with jitter**. Never retry instantly, or you get a thundering herd.
- **Quota-aware fallback routing** in the gateway (Scenario F).
- **Priority classes:** customer-facing agents get reserved capacity ahead of batch jobs (Godric already has `MetricsBasedThroughputStrategy` for offline jobs, the same idea).

---

## 7. What Varta should do (agent side), with burst in mind

| Control | Why | Example |
| --- | --- | --- |
| **Per-user rate limit** | Godric only limits the service (Scenario D) | 10 messages/min/user, 200/day/user |
| **Max concurrent sessions per agent** | Smooth the burst before it reaches Godric | Bounded executor + queue; over capacity → "high load, please retry" |
| **Turn budget pre-check** | Avoid half-finished turns (Scenario C) | Call `GET /v1/quota/{ns}/{llmId}`; if remaining < ~20k (one turn), don't start the turn |
| **Cap tool round-trips** | Each round-trip re-sends the whole context | `maxToolCallingRoundTrips = 5` |
| **Shrink context** | Prompt tokens are most of the cost | Compact tool JSON (like `CompactingToolProvider`), memory window of N messages, summarize old turns |
| **Cap `max_tokens`** | Bounds output and makes reservations accurate | `max_tokens: 500` via gateway `generationParams` |
| **Idempotent mutating tools** | A 429 in the middle of a turn + a retry ≠ a duplicate dispute | Idempotency key = sessionId + toolCallId |
| **Graceful 429 handling** | User experience | Read the error's `context.remaining`: short window → retry with backoff; daily → "assistant unavailable today" |
| **Cache** | Burst = many identical questions | Cache FAQ-style answers (not user-specific ones) |
| **Circuit breaker on RATE_LIMIT** | Stop hammering Godric | After N 429s, pause for the `Retry-After` time |
| **Real sessionId/userId** | Needed for all of the above | Fix the hardcoded `"s1"`/`"ss"` |

---

## 8. How to explain it in the interview (2 minutes)

> "Godric enforces **token** quotas per model and per calling service over three windows: per minute for bursts, per 5 minutes for sustained load, and per day for cost. Every LLM response's usage is published as an event to Kafka, aggregated in Pinot, and exposed through Yoda. Before each call we read the consumed totals and reject with RATE_LIMIT if any window is exhausted. If the quota system is down we fail open.
>
> This keeps the hot path cheap and uses exact provider token counts, but it's **eventually consistent**. In a burst, say 40 Varta users at once, each agent turn making about 3 LLM calls of 4–7k tokens, all those calls are checked against an old reading of zero, so we can overshoot the limit severalfold. Then everyone gets locked out until the windows roll over. For agents it's worse, because a 429 can land in the **middle of a turn after a tool has already done something**.
>
> To fix it on the platform side, I'd **reserve estimated tokens atomically** (prompt + max_tokens) in Redis or Aerospike before the call and settle the difference after, use a local token bucket as the fallback instead of failing fully open, return Retry-After, and add quota-aware fallback routing. On the Varta side: per-user rate limits, because Godric only knows the service; a concurrency cap; a pre-check of the turn budget; idempotent mutating tools; capped tool round-trips and compacted context, because each round-trip re-sends the whole conversation."
