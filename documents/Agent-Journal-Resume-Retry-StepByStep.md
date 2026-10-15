# Journal, resume and retry for AI agents, step by step

> Ignore leases. This doc explains **only** how an agent survives failures in the middle of a turn: what the **journal** is, what **resume** does, and the different kinds of **retry**. It uses one example throughout.

---

## Part 1: The one idea you need first

### An LLM has no memory. The agent's whole state is just a LIST of messages.

Every agent framework (Sentinel AI, LangChain4j AiServices, OpenAI SDK) runs this loop inside `execute()`:

```java
List<Message> messages = [system, user];                // ← THIS LIST IS THE ENTIRE STATE
while (true) {
    AssistantMessage reply = llm.call(messages);        // send the whole list every time
    messages.add(reply);
    if (!reply.hasToolCalls()) return reply.text();     // no tool needed → final answer
    for (ToolCall call : reply.toolCalls()) {
        String result = tools.run(call);                // execute the tool
        messages.add(new ToolResultMessage(call.id(), result));
    }
}                                                       // loop: the LLM now sees the tool results
```

**What this means:**
- The LLM call on step N only needs `messages` up to step N. **If you have the list, you can continue from anywhere**, on any server, at any time.
- Today the list lives **only in RAM, inside `execute()`**. If anything throws, the list is gone, and with it the knowledge that "D-778 was created".

### So:
- **Journal** = *save that list (plus a little status) to a database after every step.*
- **Resume** = *load the list, look at the last message, and do the next step.*
- **Retry** = *repeat one failed step* (and know which steps are safe to repeat).

Analogy: a **video game save point**. Without save points, dying at the boss means replaying the whole level, *including the door you already opened* (a duplicate dispute). With save points, you restart right before the boss.

---

## Part 2: The example turn, with the journal after every step

User → `DisputeAgent`: *"My ₹2,000 payment T123 failed but money was debited. Raise a dispute."*
The client app sends `turnId = "t-4"` (a UUID created when the user hits Send; **the Retry button reuses the same turnId**).

Journal store: Aerospike/Redis/DB, key = `sessionId:turnId` = `sess-91:t-4`.

### Step 0: turn starts → save
```json
{ "turn": "sess-91:t-4", "status": "RUNNING", "version": 1,
  "messages": [
    {"role":"system","content":"You are DisputeAgent..."},
    {"role":"user","content":"My ₹2,000 payment T123 failed ... raise a dispute"}
  ],
  "actions": {} }
```

### Step 1: LLM call 1 → reply asks for a tool → save
```json
  "messages": [ system, user,
    {"role":"assistant","tool_calls":[{"id":"call_A","name":"getTransaction","args":{"txnId":"T123"}}]}
  ]
```

### Step 2: run the read tool `getTransaction` → save
```json
  "messages": [ system, user, assistant(call_A),
    {"role":"tool","tool_call_id":"call_A","content":"{status:FAILED, debited:true, amount:2000}"}
  ]
```

### Step 3: LLM call 2 → reply asks for the MUTATING tool → save
```json
  "messages": [ ..., {"role":"assistant","tool_calls":[{"id":"call_B","name":"raiseDispute",
                       "args":{"txnId":"T123","reason":"DEBITED_NOT_CREDITED"}}]} ]
```

### Step 4a: BEFORE calling raiseDispute → save an "intent" (write-ahead)
```json
  "actions": { "raiseDispute|T123|DEBITED_NOT_CREDITED": {
                 "idempotencyKey": "[ORG_REFER_TAG]", "state": "STARTED" } }
```
### Step 4b: call the host service with `Idempotency-Key: [ORG_REFER_TAG]` → D-778 → save
```json
  "messages": [ ..., {"role":"tool","tool_call_id":"call_B","content":"{disputeId:D-778, eta:48h}"} ],
  "actions": { "raiseDispute|T123|DEBITED_NOT_CREDITED": {
                 "idempotencyKey": "[ORG_REFER_TAG]", "state": "DONE",
                 "result": {"disputeId":"D-778","eta":"48h"},
                 "userSummary": "Dispute D-778 raised for T123; resolution in 48h" } }
```
> **Why both 4a and 4b?** If the server crashes **after sending** the HTTP request but **before saving** the result, the journal says `STARTED`, which means *"maybe it happened"*. Then we know we must retry **with the same idempotency key**, so the host service returns D-778 instead of creating D-779. Without 4a, we wouldn't even know the call was attempted.

### Step 5: LLM call 3 → final answer → save + finish
```json
  "status": "COMPLETED",
  "messages": [ ..., {"role":"assistant","content":"Your dispute D-778 is raised. Resolution within 48 hours."} ],
  "finalReply": "Your dispute D-778 is raised. Resolution within 48 hours."
```

---

## Part 3: Resume, the "what do I do next?" table

Resume is **not magic**. Load the journal and look at the **last message**:

| Last message in the journal | What it means | Next action on resume |
| --- | --- | --- |
| `user` | LLM hasn't answered yet | **Call the LLM** |
| `assistant` **with** tool_calls, some tool results missing | Tools were requested; some didn't finish | **Run only the missing tools**. For mutating tools, check `actions`: `DONE` → reuse the stored result (don't call); `STARTED` → call again **with the same idempotency key**; absent → call normally |
| `tool` (all requested results present) | Tools finished; the LLM hasn't seen the results | **Call the LLM** |
| `assistant` **without** tool_calls | The turn already finished | **Return the saved `finalReply`**. Don't call anything |

```java
AgentReply resume(Journal j) {
    while (true) {
        Message last = j.lastMessage();
        if (last.isAssistant() && !last.hasToolCalls()) return j.complete(last.text());   // done

        if (last.isAssistant()) {                                // tools pending
            for (ToolCall c : j.missingToolResults(last)) {
                j.append(toolResult(c, runToolSafely(j, c)));    // save after EACH tool
            }
            continue;
        }
        j.append(llmCallWithRetry(j.messages()));                // last is user/tool → ask LLM, save
    }
}
```
**Starting a new turn and resuming one are the same code.** A new turn is just a journal with `[system, user]`. That's why this design is simple: there's only one loop, and it always "resumes".

---

## Part 4: Four different kinds of "retry" (they're NOT the same thing)

| Level | What is retried | Where / when | Safe because | Example trigger |
| --- | --- | --- | --- | --- |
| **R1: retry the LLM call** | Only the one failed LLM call, with **the same messages** | Inside the same request, a few attempts, backoff 0.5s → 1s → 2s + jitter, total ≤ ~8s | LLM calls have **no side effects** (only cost) | Godric/provider 5xx, timeout, 429 per-minute |
| **R2: retry a tool call** | One tool call | Inside the same request | Read tool: harmless. Mutating tool: **same idempotency key** → same result | Tool 5xx / timeout / connection reset |
| **R3: resume the turn** | Continue from the journal | **Later**: the user presses Retry (same turnId), a background job, or another server | Journal + idempotency | R1/R2 gave up, pod crashed, daily quota |
| **R4: give up gracefully** | Nothing is retried; answer from the journal | Immediately when R1/R2 give up | Uses only saved facts | Anything unrecoverable *right now* |

### Which errors get which retry

| Failure on LLM call 3 | R1? | Then |
| --- | --- | --- |
| Provider/Godric **5xx** | ✅ 2–3 times | still failing → R4 now, R3 later |
| **Timeout** | ✅ (tokens may be charged twice: a cost, not a correctness problem) | → R4 / R3 |
| **429 per-minute** | ✅ wait a few seconds | → R4 / R3 |
| **429 per-day** | ❌ pointless | → R4 now; R3 after the reset |
| **Godric down** (connection refused) | ✅ brief | → R4 / R3 |
| **Input guardrail FAILED** | ❌ (the input is the problem) | Refuse politely; if an action is already `DONE`, still tell the user about it |
| **Output guardrail FAILED** | ✅ **once**, adding an instruction: *"Your previous answer was blocked for {reason}; answer only with facts from the tool results."* | Still blocked → R4 (the template is safe text by design) |

| Failure on a **tool** | What to do |
| --- | --- |
| **Business error 4xx** (e.g. `409 dispute already exists: D-778`) | **Not a retry case.** Give the error back to the LLM **as the tool result**, so it can say *"A dispute D-778 already exists for this transaction"* |
| **5xx / timeout on a read tool** | R2 (retry 2×); still failing → give the LLM "tool unavailable" as the result, or R4 |
| **5xx / timeout on a mutating tool** | R2 **with the same idempotency key** (otherwise you don't know whether it happened). Still failing → leave the state as `STARTED`, R4: *"I'm confirming your dispute request; you'll get an update shortly"*, R3 later |

---

## Part 5: Four failure stories, end to end

### Story A: Godric returns 503 on LLM call 3 (a blip)
```
Step 5: LLM call 3 → 503
R1: wait 0.5s → call 3 again with the SAME messages → 200 OK → "Your dispute D-778 is raised..."
```
The user notices nothing (about 0.5 s slower). **Journal not even needed here, R1 handled it.**

### Story B: per-day quota exhausted on LLM call 3
```
Step 5: LLM call 3 → 429 (perDay) → R1 skipped (pointless)
R4: journal.actions has DONE raiseDispute → build the reply from userSummary:
    "✅ Dispute D-778 raised for T123; resolution in 48h. I can't give more details right now."
journal.status = SUSPENDED(reason=QUOTA_DAY)
Next day, user presses Retry (turnId t-4):
R3: load journal → last message = tool(call_B, D-778) → table says "call the LLM"
    → LLM call 3 ONLY → "Your dispute D-778 is in progress..."  → COMPLETED
```
No duplicate dispute. Only **1** LLM call is spent on resume (not 3).

### Story C: the Varta pod crashes right after sending raiseDispute
```
Step 4a saved: actions[raiseDispute|T123] = STARTED, key [ORG_REFER_TAG]
HTTP POST /disputes sent → host service CREATED D-778 and stored key [ORG_REFER_TAG] → D-778
💥 pod dies before 4b (result never saved)
The user's app times out → user presses Retry (turnId t-4) → lands on ANOTHER pod
R3: load journal → last = assistant(call_B raiseDispute), result missing
    → actions says STARTED → call raiseDispute again WITH key [ORG_REFER_TAG]
    → host idempotency store: "seen this key" → returns stored {D-778}   ✅ (no D-779)
    → save tool result → last = tool → call LLM → final answer → COMPLETED
```
**This is the case that truly needs both the journal (to know we were mid-action) and the idempotency key (to make re-doing it safe).**

### Story D: the output guardrail blocks the final answer
```
Step 5: LLM call 3 → "Dispute D-778 raised. Also, you're definitely getting ₹2,000 back tomorrow!"
Godric output guardrail (faithfulness) → FAILED (promise not supported by the tool results)
R1 once with the extra instruction → "Dispute D-778 raised; resolution expected within 48h." → PASSED ✅
(if blocked again → R4 template from userSummary, which is safe text written by developers)
```

### Story E (bonus): the user types the same message again as a NEW message
New turnId `t-5`, so it's not a resume. But:
1. The **session history** includes turn t-4's messages, so the LLM *sees* D-778 already exists and replies *"Dispute D-778 is already in progress."*
2. Even if the LLM ignores that and calls `raiseDispute(T123, DEBITED_NOT_CREDITED)` again, the **idempotency key is built from the business action** (user + tool + txnId + reason), not from turnId/tool_call_id. **Same key, so it returns D-778.**
3. If the LLM changed `reason`, the host service's **DB rule "one OPEN dispute per txnId"** still blocks it and returns `409 D-778 exists`, which goes back to the LLM as the tool result.

---

## Part 6: The template fallback (R4) in detail

**Goal:** an honest reply without the LLM, built **only from saved facts**.

```java
AgentReply fallback(Journal j, FailureReason why) {
    List<Action> done    = j.actions(DONE);
    List<Action> pending = j.actions(STARTED);
    if (done.isEmpty() && pending.isEmpty())
        return reply("I'm getting a lot of requests right now. Please try again in a minute.");   // nothing happened, simple
    StringBuilder sb = new StringBuilder();
    done.forEach(a -> sb.append("✅ ").append(a.userSummary()).append("\n"));      // "Dispute D-778 raised for T123; resolution in 48h"
    pending.forEach(a -> sb.append("⏳ ").append(a.tool().pendingText()).append("\n")); // "Confirming your dispute request; you'll be notified"
    sb.append("I can't share more details right now. You can track this under My Disputes.");
    return reply(sb.toString());
}
```
- `userSummary` comes from the tool response (the host service returns it) or from a per-tool template in the `@VartaTool` annotation: `"Dispute {disputeId} raised for {txnId}"`.
- **Never "Something went wrong" when `done` isn't empty.** That's what makes the user retry and cause duplicates.

---

## Part 7: Practical details people ask about

| Question | Answer |
| --- | --- |
| **Where to store the journal?** | A fast KV store with TTL (Aerospike/Redis), one record per turn, ~24h TTL. Completed turns are copied into **session memory** (conversation history) |
| **Isn't saving after every step slow?** | ~1–3 ms per write vs ~1–2 s per LLM call, so <1% overhead. Writes can be batched for read-only steps (lose a read = just redo it); **the intent and result of mutating tools must be saved synchronously** |
| **Size?** | Tool results can be large → store **compacted** results (like `CompactingToolProvider`) and cap them; this also saves prompt tokens |
| **Two resumers at once?** (user double-clicks Retry; a background job runs too) | **Optimistic locking:** each save is `compare-and-set(version)`. Or an owner lock: `status=RUNNING, owner=pod-7, heartbeatAt`; another pod may take over only if the heartbeat is stale (>30s). Plus idempotency keys as the last safety net |
| **How does the server know it's the "same turn"?** | The **client sends the turnId**; the Retry button reuses it. A completed turnId → return the saved `finalReply` instantly (also handles double submits) |
| **Will the LLM answer differently on resume?** | Maybe slightly (that's fine). It *can't* undo or redo the action, because the action's result is already in the messages and protected by idempotency. Low temperature (Varta uses 0.1, seed 42) helps consistency |
| **Can resume use a different model?** | Yes. The messages are standard OpenAI-format, so resume can use a fallback model/gateway. A useful trick for quota or provider outages |

---

## Part 8: How this maps onto Varta's code

Today (`GodricAgentConfigService.processChat`):
```java
return resolveAgent(agentName).execute(buildAgentInput(authToken, input)).getData();   // loop hidden inside; nothing saved
// sessionId("s1"), userId("ss") hardcoded; no old messages passed
```
Two ways to add journal/resume:

| Option | How | Pros / cons |
| --- | --- | --- |
| **A. Hook into Sentinel AI** | Wrap the **toolbox** (save intent → call with Idempotency-Key → save result) and the **model** (save each reply, R1 retry). Resume = call `execute()` with the journal's messages as the **previous/old messages** of the input (check Sentinel AI's `AgentInput` fields / session extension for the exact API) | Least code; depends on what hooks the framework exposes |
| **B. Varta owns the loop** | Varta calls the model with tool definitions and runs tools itself (the Part 1 loop + the Part 3 resume table) | Full control over save points, retry, and fallback; more code to maintain |

Required either way: real `sessionId`/`userId`/`turnId` from the request (not `"s1"`/`"ss"`), the end-user token passed to tools, and `@VartaTool(sideEffect=..., idempotencyFields=..., userSummary=...)` metadata from the Maven scan.

---

## Part 9: Interview answer (60 seconds)

> "An agent's state is just its message list. The LLM is stateless, and the loop appends the model's reply, then the tool results, then the next reply. So I make that list durable: a **turn journal** saved after every step, keyed by a client-generated turnId. For mutating tools I write an **intent record before the call** and the result after, and every call carries an **idempotency key derived from the business action**, so a repeat returns the original result.
>
> Failures are handled at three levels. **First, retry the step in place**: LLM calls have no side effects, so 5xx, timeouts, or a per-minute 429 just get backoff retries with the same messages. **Second, if that gives up, answer honestly from the journal**: 'Dispute D-778 raised', never a generic error once something has happened. **Third, resume later**: load the journal, look at the last message, and do the next step. If the last message is a tool result, call the LLM; if a tool call has no result, re-run only that tool with the same idempotency key; if there's a final answer, return it. A new turn and a resumed turn are the same loop, so there's one code path. Duplicates are blocked three ways: the journal, the idempotency key, and a domain constraint like one open dispute per transaction."
