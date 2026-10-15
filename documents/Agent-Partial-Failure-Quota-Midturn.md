# When tokens run out in the middle of an agent turn: handling partial failure in Varta

> The problem: an agent turn = **several LLM calls with tool calls in between**. Quota (or any failure) can stop the turn **after a tool has already changed something in the real world**. This doc explains what happens today, why it's dangerous, and how to design for it (Varta + Godric).

---

## 1. The problem, step by step

User (in the [ORG_REFER_TAG] app, chatting with `DisputeAgent`):
> "My ₹2,000 payment for txn T123 failed but money was debited. Please raise a dispute."

```
Turn 1 (one user message → one agent.execute())
──────────────────────────────────────────────────────────────────────────────────────────────
LLM call 1  (Godric: quota OK)   → "call getTransaction(T123)"
   tool     getTransaction(T123)  → {status: FAILED, debited: true, amount: 2000}          read-only ✅
LLM call 2  (Godric: quota OK)   → "call raiseDispute(T123, reason=DEBITED_NOT_CREDITED)"
   tool     raiseDispute(...)     → {disputeId: "D-778", eta: "48h"}                       ⚠️ SIDE EFFECT: dispute row created in DB
LLM call 3  (Godric: quota EXHAUSTED) → GodricException RATE_LIMIT  ❌
──────────────────────────────────────────────────────────────────────────────────────────────
Result to user: "Something went wrong, please try again."
```

The user does what any user does, **retries the same message**:

```
Turn 1 (retry), a brand-new execute(), no memory of the first attempt
LLM call 1 → getTransaction(T123)
LLM call 2 → raiseDispute(T123)  →  {disputeId: "D-779"}   ❌ DUPLICATE DISPUTE
LLM call 3 → "Your dispute D-779 has been raised"
```

**Three problems at once:**
1. **Duplicate side effect:** two disputes (D-778, D-779). Ops confusion, possibly a double refund.
2. **Wrong message:** the user was told "error" although the dispute was actually raised.
3. **Wasted tokens:** the retry pays again for calls 1 and 2, during a quota shortage, which makes the shortage worse.

### Why it happens in Varta today (from the code)
- `GodricAgentConfigService.processChat()` calls `agent.execute(...)` **synchronously**. The whole tool loop lives inside one call, and if it fails, **the intermediate messages (including "D-778 created") are lost**.
- `buildAgentInput` sets `sessionId("s1")` / `userId("ss")` (hardcoded) and passes **no old messages**, so there's no memory between attempts.
- Tools are `TemplatizedHttpTool` HTTP calls with **no idempotency key**, so the host service can't tell a retry from a new request.

> Say this honestly in the interview: *"Today a retry restarts the turn. That's the gap. Here's how I'd close it."*

---

## 2. The core insight

| Thing | Safe to retry? | Why |
| --- | --- | --- |
| **LLM call** | ✅ Yes | No side effects. The worst case is extra tokens or a slightly different answer |
| **Read-only tool** (GET) | ✅ Yes | No side effects |
| **Mutating tool** (POST raiseDispute, blockCard, refund) | ❌ **No, unless made idempotent** | It changes the real world |

So the strategy is:
> **Make side effects idempotent, record progress after every step, and *resume* a turn instead of restarting it.**
> It's the same idea as workflow engines (Temporal/Step Functions), sagas, and Stripe's idempotency keys, applied to agent loops.

---

## 3. Where can a turn break? (a failure map)

| Failure point | What has already happened | Correct behaviour |
| --- | --- | --- |
| **Before any tool** (LLM call 1 → 429) | Nothing | Simple: tell the user "busy, retry in N s", or auto-retry after a short wait |
| **After read-only tools only** | Only reads | Retry or resume. Reads can be repeated or reused from the record |
| **After a mutating tool, before the final answer** (Scenario C) | **Real side effect** | **Never say "error"**. Report what was done; resume to finish; never redo the action |
| **During a mutating tool** (HTTP timeout: did it succeed?) | **Unknown** | The idempotency key makes a retry safe; or look it up by business key (`getDisputeByTxn`) |
| **After the final LLM answer, output guardrail fails** | Side effect may be done | Answer with a fixed template message about what was done, not the blocked LLM text |

---

## 4. The solution: 8 mechanisms (layered)

### 4.1 Classify every tool by side effect (at build time)
Varta already scans tool methods with the Maven plugin (`scan-tools` → `varta-tools.json`). Add metadata:

```java
@VartaTool(description = "Raise a dispute for a transaction",
           sideEffect = SideEffect.NON_IDEMPOTENT,      // READ_ONLY | IDEMPOTENT | NON_IDEMPOTENT
           requiresConfirmation = true,
           idempotencyFields = {"txnId", "reason"})     // business identity of the action
@POST @Path("/disputes")
public Dispute raiseDispute(RaiseDisputeRequest req) { ... }
```
Default heuristic: `GET` = READ_ONLY, `PUT/DELETE` = IDEMPOTENT, `POST` = NON_IDEMPOTENT unless annotated. **The framework then treats each class differently**: retry freely, cache, or protect.

### 4.2 Idempotency keys, done correctly ⚠️ (an interview detail)

**Trap:** you might think *"use the LLM's `tool_call_id` as the key"*. **That's wrong.** On a retry, the LLM generates a **new** tool_call_id (`call_abc` → `call_xyz`), so the dedupe never matches.

**Correct:** derive the key from **what the action is**, not from the LLM's run:
```
idempotencyKey = sha256( userId + agentName + toolName + canonicalJson(args[idempotencyFields]) )
                 → e.g. sha256("user42|DisputeAgent|raiseDispute|{txnId:T123,reason:DEBITED_NOT_CREDITED}")
```
Varta sends it as an HTTP header when it calls the tool:
```
POST /disputes
Idempotency-Key: [ORG_REFER_TAG]
Authorization: O-Bearer <end-user token>
```
**On the host-service side (two layers):**
1. **Idempotency filter** (generic, Stripe-style): store `key → response` for 24h. The same key arrives → **return the stored response (D-778)** without running the method again. Concurrent duplicate → wait or return 409.
2. **Domain invariant** (strongest): a DB unique constraint like *"one OPEN dispute per txnId"*. Even if a key differs (e.g. the LLM paraphrased `reason`), the business rule prevents a duplicate and returns the existing dispute.

> *"The framework key catches retries; the domain constraint catches everything else. Never trust the LLM to remember it already did something."*

### 4.3 Turn journal: record progress after every step
Persist the turn as it runs (Aerospike/Redis/DB, keyed by `sessionId + turnId`):
```json
{
  "sessionId": "sess-91", "turnId": "t-4", "userId": "user42", "agent": "DisputeAgent",
  "userMessage": "My ₹2,000 payment for T123 failed ... raise a dispute",
  "status": "SUSPENDED",                      // RUNNING | SUSPENDED | COMPLETED | FAILED
  "suspendReason": { "type": "RATE_LIMIT", "window": "perMinute", "retryAfterSec": 20 },
  "messages": [ ...system, user, assistant(tool_call getTransaction), tool(result),
                assistant(tool_call raiseDispute), tool({disputeId:"D-778"}) ... ],
  "completedActions": [
    { "tool": "raiseDispute", "idempotencyKey": "[ORG_REFER_TAG]", "result": {"disputeId": "D-778", "eta": "48h"},
      "userSummary": "Dispute D-778 raised for T123; resolution in 48h" }
  ],
  "tokensUsed": 9800
}
```
Hook points: write after **each LLM response** and **each tool result**. In Sentinel AI, use an agent extension / tool-box wrapper (or `AgentOutput.allMessages` plus the session store). Check the exact Sentinel AI hooks available.

### 4.4 Resume, not restart
When the turn is retried (by the user or automatically):
```
load journal(sess-91, t-4)  → status SUSPENDED
agent.execute(input = same user message, oldMessages = journal.messages)
→ the LLM sees: "raiseDispute → D-778 created" already in the history
LLM call 3 (only this call is repeated) → "Your dispute D-778 is raised; resolution in 48 hours."
```
- Only **one** LLM call is spent on resume (not three). This **saves tokens exactly when quota is tight**.
- Safety net: a **tool-result cache inside the turn**. If the LLM still asks for `raiseDispute(T123)` again, the framework sees a matching idempotency key in `completedActions` and returns the stored result **without calling the service**.

### 4.5 Graceful fallback: answer without the LLM
If call 3 can't run at all (daily quota gone, or Godric down), **don't show an error**. Build a fixed-template reply from the journal:
```
"✅ I've raised dispute D-778 for transaction T123. Resolution is expected within 48 hours.
 I'm unable to give more details right now. You can track it under My Disputes."
```
- To make this possible, mutating tools return a `userSummary` field (or Varta uses a per-tool template in the annotation).
- **Rule: once a side effect has happened, the user must hear about it, with or without the LLM.**

### 4.6 Check the budget before starting the turn (prevention)
Don't start a turn you probably can't finish:
```java
long estTurnCost = avgCallsPerTurn * (promptTokensEstimate + maxTokens);    // e.g. 3 × (5,500 + 500) = 18k
QuotaResponse q = godric.getQuota(namespace, llmId);                        // GET /v1/quota/{ns}/{llmId}
if (min(q.remaining.perMinute, q.remaining.perFiveMinute, q.remaining.perDay) < estTurnCost) {
    return "We're experiencing high demand, please try again in a minute.";  // nothing started, nothing half-done
}
```
- This is a **heuristic**, because Godric's numbers lag (eventually consistent), so you **still need** 4.2–4.5.
- **Platform improvement (Staff-level):** a Godric **turn lease**: `POST /v1/quota/reserve {estimate: 18000, ttl: 60s}` → `leaseId`. LLM calls carrying the `leaseId` draw from the reserved budget and can't be rate-limited mid-turn; the unused part is released at the end. This **turns "can fail at any step" into "fail only at the start"**, which is far easier to handle.
  ⚠️ A lease for *every* turn has real problems (estimation, latency, hoarding). See **§9 for the hybrid "lazy lease" design**, which is the recommended version.

### 4.7 Smart retry of the *LLM call* inside the turn
LLM calls are safe to retry, so handle the 429 **at the call**, not by failing the whole turn. Godric's error `context` contains `remaining` per window:

| Exhausted window | Action |
| --- | --- |
| `perMinute` only | Wait (backoff + jitter, cap the total wait at about 10 s because a user is waiting), **retry the same call** |
| `perFiveMinute` | Try a **fallback model** (call 3 is just "write the final answer"; a cheaper model or another gateway target can do it). If there's none → suspend + async (4.8) |
| `perDay` | No retry. **Suspend**, give the fixed-template reply (4.5) |

Retrying the same call (with the same messages) is safe, and it **continues the turn in place**, so no resume is needed.

### 4.8 Asynchronous completion for long waits
If the wait is longer than a user should sit through:
```
HTTP 202 → "Your dispute D-778 is raised. I'll send you the full details shortly."
journal.status = SUSPENDED(retryAt = now + 60s)
scheduler / delayed Kafka message → resume turn at retryAt → push the final answer (notification / chat poll / websocket)
```
This is a **durable-execution** pattern. It's the same idea as Temporal: each step is recorded, and the workflow resumes after a crash or throttle.

### 4.9 Confirmation step: split the turn at the side effect (human-in-the-loop)
For `requiresConfirmation = true` tools:
```
Turn A:  LLM call 1 → getTransaction ; LLM call 2 → proposes raiseDispute(T123)
         Varta STOPS the turn and asks: "Raise a dispute for ₹2,000 on T123? [Confirm] [Cancel]"
Turn B:  user clicks Confirm → Varta runs raiseDispute DIRECTLY (deterministic code, NO LLM needed)
         → a template reply "Dispute D-778 raised" (optionally 1 LLM call to rephrase it nicely)
```
Benefits:
- The mutation runs **outside the LLM loop**, so a quota failure can't interrupt it between "did the action" and "told the user".
- The user explicitly approved the action, which is also a **guardrail against prompt injection** (an injected "raise refund" needs a human click).
- The confirm button carries the stored tool call (arguments are already fixed), so the LLM **can't change the arguments** between proposal and execution.

### 4.10 Act last; compensate if needed
- Tell the agent in the prompt (and enforce it with ordering checks): *gather information first, perform at most one mutation, as late as possible.* This reduces the number of LLM calls after a side effect.
- For **multi-mutation** flows (raise dispute **and** block card), use a **saga**: every mutating tool declares a compensating action (`cancelDispute`, `unblockCard`). If a required later step fails permanently, run the compensations in reverse order, or mark the turn `NEEDS_ATTENTION` for an ops queue.

---

## 5. The same scenario, with the fixes

```
Turn t-4 starts
  pre-check: remaining.perMinute = 30k ≥ 18k estimate → start              (4.6)
  journal RUNNING
  LLM 1 → getTransaction(T123)            journal += messages              (4.3)
  LLM 2 → proposes raiseDispute(T123)     requiresConfirmation → stop turn  (4.9)
  → user: "Raise a dispute for ₹2,000 on T123?"  [Confirm]

Turn t-5 (user clicks Confirm)
  Varta runs raiseDispute directly, Idempotency-Key=[ORG_REFER_TAG] → D-778      (4.2)
  journal.completedActions += D-778
  LLM 3 (rephrase answer) → 429 RATE_LIMIT, window = perMinute            (4.7)
     backoff 2s, 4s → still 429 → fallback model? none configured
     → template reply: "✅ Dispute D-778 raised for T123. Resolution in 48h."  (4.5)
  journal COMPLETED (degraded)

User taps "retry" anyway, or sends the same message again
  raiseDispute key [ORG_REFER_TAG] → host idempotency filter returns stored D-778   (4.2)
  DB unique(txnId, OPEN) also protects                                        (4.2)
  → "Your dispute D-778 is already raised and in progress."  ✅ no duplicate
```

---

## 6. Who owns what

| Responsibility | Godric (platform) | Varta (agent framework) | Host service (tool owner) |
| --- | --- | --- | --- |
| Report precise 429 info (`remaining` per window, `Retry-After`) | ✅ |  |  |
| Turn lease / token reservation | ✅ (proposed) | uses it |  |
| Fallback target on 429 | ✅ gateway routing (proposed) | or a fallback model in config |  |
| Tool side-effect classification |  | ✅ annotation + Maven scan | declares it |
| Idempotency key generation + header |  | ✅ |  |
| Idempotency store + domain uniqueness |  |  | ✅ |
| Turn journal, resume, in-turn tool cache |  | ✅ |  |
| Template fallback reply |  | ✅ | provides `userSummary` |
| Confirmation step (HITL) |  | ✅ |  |
| Async completion / scheduler |  | ✅ |  |
| Compensation actions |  | orchestrates | ✅ implements |

---

## 7. Sketch: how this could look in Varta (illustrative)

```java
public final class ResilientAgentExecutor {

    public AgentReply chat(String agent, AgentUser user, String sessionId, String message) {
        Turn turn = journal.loadOrCreate(sessionId, message, user);          // resume if SUSPENDED
        if (turn.isCompleted()) return turn.reply();                          // duplicate submit → same answer

        if (!budget.canAfford(agent, estimator.turnCost(agent, turn))) {      // 4.6
            return AgentReply.busy(retryAfter(...));
        }
        try {
            AgentOutput out = agents.get(agent).execute(AgentInput.builder()
                    .request(message)
                    .oldMessages(turn.messages())                             // 4.4 resume
                    .requestMetadata(meta(sessionId, user))                   // real ids, not "s1"/"ss"
                    .build());
            return journal.complete(turn, out);
        } catch (RateLimitException e) {                                      // after 4.7 in-call retries
            journal.suspend(turn, e.window(), e.retryAfter());
            if (turn.hasCompletedActions()) return AgentReply.fromTemplate(turn.completedActions()); // 4.5
            return AgentReply.busy(e.retryAfter());
        }
    }
}

// Wraps every tool call made by the LLM loop
final class GuardedToolInvoker {
    Object invoke(Turn turn, ToolSpec tool, Map<String, Object> args) {
        if (tool.sideEffect() == READ_ONLY) return record(turn, http.call(tool, args));
        String key = Idempotency.key(turn.userId(), tool, args);              // 4.2 (business identity, not tool_call_id)
        Optional<Object> done = turn.completedAction(key);
        if (done.isPresent()) return done.get();                              // 4.4 in-turn cache
        if (tool.requiresConfirmation() && !turn.isConfirmed(key)) {
            throw new ConfirmationRequired(tool, args);                       // 4.9
        }
        Object result = http.call(tool, args, Map.of("Idempotency-Key", key));
        journal.recordAction(turn, key, tool, result);                        // persist BEFORE the next LLM call
        return result;
    }
}
```
The key ordering rule: **persist the action result before making the next LLM call.** Then nothing is lost, whichever step fails next.

---

## 8. Interview answer (90 seconds)

> "An agent turn isn't atomic. It's a sequence of LLM calls with tool calls in between, and quota or any failure can hit **after a tool has changed something**. If you just retry, the LLM re-plans and raises a **second dispute**, and the user was told 'error' even though the first one succeeded.
>
> I handle it the way you'd handle any distributed workflow. First, **classify tools by side effect**: reads are retried freely, and mutations get an **idempotency key derived from the business action** (user + tool + key args), not from the LLM's tool_call_id, which changes on every retry. The host service enforces it with a key store plus a domain constraint like 'one open dispute per transaction'. Second, **record every step in a turn journal** and **resume, not restart**: pass the recorded messages back so the LLM sees the dispute already exists, and only the final call is spent again. Third, **retry the LLM call itself** on per-minute limits, with backoff or a fallback model, because LLM calls have no side effects. If it still can't finish, give a **template reply from the journal**, because once a side effect has happened the user must be told. Finally, prevention: a **pre-turn budget check**, ideally a **token lease from Godric** so a turn can only fail at the start, and a **confirmation step** for mutating tools, so the action runs as plain code outside the LLM loop. That also guards against prompt injection."

---

## 9. Follow-up: "A lease per turn adds latency and you can't know the size upfront. Can we use a hybrid?"

**Short answer: yes. A lease on every turn is the wrong design.** The right question isn't *"how many tokens will this turn need?"* but *"what must be guaranteed?"*. The only thing that must be guaranteed is: **once a side effect happens, we can finish and tell the user.** Everything before that point is safe to fail.

### 9.1 First, a clarification
- A lease is about **LLM tokens**, not tool calls. A read-only tool consumes **no** Godric tokens itself. But its **result** is fed back into the next LLM prompt, so reads *indirectly* grow token usage.
- So "read-only" really means: **if this turn fails, nothing in the real world changed, so it can simply be retried or restarted.** A failure there costs only tokens and a bit of latency, never correctness.
- So **read-only turns don't need a lease at all.**

### 9.2 Problems with a full upfront lease

| # | Problem | Why it hurts |
| --- | --- | --- |
| 1 | **You can't know the size upfront** | The LLM decides how many tool rounds (1? 5?), tool results vary (200 tokens vs 20k), and the output length varies. Any upfront number is a guess |
| 2 | **Over-reserving = capacity hoarding** | Reserving the worst case (5 rounds × 8k = 40k) on a 100k/min quota means only **2 concurrent turns**, while most turns actually use 10k. You get **false 429s** while real usage is low |
| 3 | **Under-reserving = the original problem comes back** | The turn runs past its lease → rate-limited mid-turn anyway |
| 4 | **Extra latency on every turn** | One more network call before the first token. Small (a Redis/Aerospike reservation is ~1–3 ms vs LLM ~1–2 s), but it's on **every** turn, and it's a new hot-path dependency |
| 5 | **Leaked leases** | The agent node crashes or the user leaves, so the reservation is held until the TTL expires, which wastes capacity during exactly the bursts when it's scarce |
| 6 | **Needs a strongly consistent counter** | Today's quota is eventually consistent (Pinot/Yoda). Leases need atomic reserve/decrement (Redis/Aerospike), which is new infra with its own failure mode (lease store down → fail-open or fail-closed?) |
| 7 | **Window semantics** | A lease taken at 10:00:55 is used at 10:01:05, so which 1-minute window does it belong to? Fixed windows plus leases get messy |
| 8 | **Routing mismatch** | Quota is per **target LLM**, but the gateway picks the target per call at random. A lease on gpt-4o is useless if the next call is routed to llama |
| 9 | **Fairness** | A long, slow turn holds capacity that short turns could use |

### 9.3 Hybrid design: "optimistic by default, lease only at the point of no return"

```
                    ┌──────────────── READ-ONLY PHASE (optimistic, no lease) ────────────────┐
User msg ─► LLM 1 ─► getTransaction ─► LLM 2 ─► getRefundStatus ─► LLM 3 → proposes raiseDispute
                     429 here? → retry the LLM call (backoff / fallback model), or restart the turn.
                     Nothing changed in the real world, so it's always safe.
                    └──────────────────────────────────────────────────────────────────────────┘
                                                            │  POINT OF NO RETURN
                                                            ▼
                             ① acquire a SMALL lease for the "completion tail" (exact size, see 9.4)
                                ├─ granted → ② run raiseDispute (Idempotency-Key) → ③ LLM final answer using the lease
                                └─ denied  → DON'T run the mutation. "I can raise this dispute; high load
                                             right now. [Confirm when ready]" or queue it. Nothing half-done.
```

**Rule: never start a side effect you can't afford to finish reporting.**

How this answers each concern:
- **"How do you know upfront?"** You **don't need to**. You lease **lazily**, at the moment the LLM proposes a mutating tool. By then you know almost everything (9.4).
- **"Latency on read-only calls?"** **Zero.** Read-only turns (most traffic: "where is my refund?") never touch the lease service. Only turns that mutate pay ~1–3 ms, once, next to a multi-second LLM loop.
- **Which agents need it?** Known **statically**: the Maven plugin scan (`varta-tools.json`) tells whether an agent has *any* `NON_IDEMPOTENT`/mutating tool. Agents with only read tools have lease logic **turned off entirely**.

### 9.4 Sizing the lease exactly at the point of no return

At the moment the LLM proposes `raiseDispute`, you know:
```
lastUsage.prompt_tokens + lastUsage.completion_tokens   = current context size   (exact, from the last response)
+ toolResultCap                                         = max tool result tokens (Varta truncates/compacts results anyway)
+ max_tokens                                            = cap on the final answer (gateway generationParams)
───────────────────────────────────────────────────────
lease = (context + toolResultCap + max_tokens) × reportCalls     (reportCalls usually 1, at most 2)
e.g.   (6,000 + 1,000 + 500) × 1 ≈ 7.5k   (vs a 40k worst-case upfront guess)
```
- **Guarantee only the "reporting" call**, not unlimited extra rounds. If the LLM wants more tool rounds after the mutation, those are **best-effort**. If they're throttled, the template fallback (4.5) still reports what was done.
- This works because `maxToolCallingRoundTrips`, `max_tokens`, and the tool-result cap together make the worst case **finite and known**.

### 9.5 Making the lease cheaper still (latency, consistency, routing)

| Problem | Fix |
| --- | --- |
| Extra network hop | **Piggyback the reservation on the LLM call that proposes the mutation.** Varta sends `X-Godric-Reserve-On-Tools: raiseDispute,blockCard`. If the response contains such a tool call, Godric atomically reserves the tail and returns `X-Godric-Lease: L-123` **in the same response**. No extra round trip |
| Alternative with no lease API at all | **Reserved completion headroom ("priority lane")**: Godric sets aside e.g. 10–15% of each client's quota that **only** calls marked `X-Turn-Phase: post-side-effect` (or carrying a lease) may use. Normal calls hit 429 at 85–90%; completion calls can still go through. No per-turn state, no TTLs, no leaks. Cap the lane with its own sub-quota and monitor the ratio, so it can't be abused as a quota bypass |
| Leaked leases | Short **TTL** (e.g. 30–60 s) + explicit **release** on turn end + reconcile `actual − reserved` after each call. Crash → auto-expiry |
| Strong-consistency store needed | Only for the small tail leases. Keep the Pinot path for analytics/billing. If the lease store is down → **don't run the mutation** (fail-closed for mutations only); read-only traffic is unaffected |
| Window boundary | Count a lease against the window **where it is used**, or implement leases as a separate **token bucket** (continuous refill), with no fixed windows |
| Gateway routing mismatch | **Sticky target per turn**: pin the target chosen on the first call (also gives consistent model behaviour within a turn), or lease at the **gateway level** with fallback across targets |
| Hoarding / fairness | Leases are small (one tail call) and short-lived, so hoarding is bounded: at most (concurrent mutating turns × ~7.5k) |

### 9.6 Even with a lease, keep the other layers
A lease protects only against **quota**. The final call can still fail because of a provider 5xx, a timeout, Godric being down, or an output guardrail blocking the answer. So **idempotency keys (4.2), the journal (4.3), and the template fallback (4.5) stay**. The lease reduces how often you reach the fallback; it doesn't replace it. **Defence in depth.**

### 9.7 The full hybrid, summarized

| Phase | Mechanism | Latency cost | Failure handling |
| --- | --- | --- | --- |
| Agent has no mutating tools | Nothing | 0 | Retry LLM call / restart turn |
| Read-only phase of any turn | Optimistic (today's Godric quota) | 0 | Retry LLM call with backoff / fallback model; restart is safe |
| LLM proposes a mutation | **Lazy, exact-size tail lease** (piggybacked) **or** priority lane | ~0–3 ms, once | Lease denied → don't mutate; ask to confirm later / queue |
| Mutation | Idempotency key + domain uniqueness | the tool's own latency | A retry returns the same result |
| Completion | Uses the lease / priority lane | 0 | Still fails? → template reply from the journal |

### 9.8 Interview answer (45 seconds)
> "I wouldn't lease every turn. You can't size it upfront, because the model decides how many tool rounds to make, and over-reserving hoards capacity and causes false 429s. Instead it's hybrid. Read-only phases run optimistically with no extra latency, because failing there is harmless: we just retry the LLM call or restart. The only moment that needs a guarantee is the **point of no return**, when the model proposes a mutating tool. At that moment I know the context size exactly from the last usage, plus the capped tool result and `max_tokens`. So I reserve a small, exact lease for just the reporting call, ideally piggybacked on the same LLM response so there's no extra round trip, or with a reserved 'completion lane' in the quota. If the lease is denied, I don't run the mutation. If the reporting call still fails for other reasons, idempotency and the template fallback cover it. Agents with no mutating tools skip all of this, and we know that statically from the tool scan."
