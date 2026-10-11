# Sprint AI Agent

An AI agent that answers questions about the **active Jira sprint** of a project (default: `DIS`).
It reads live Jira data through a Jira MCP server and turns it into a sprint report: status counts,
who owns what, high-priority items and blockers, parent epics, and recommendations.

## How it works

```
curl ──► POST /sprint/chat
           │
           ▼
   SprintAIAgentResource ──► SprintAIAgentService ──► SprintAIAgent (LangChain4j AI service)
                                                         │            ▲
                                       LLM (Ollama)  ◄───┘            │ tool results (compacted)
                                                         │            │
                                                         ▼            │
                                        ManagedJiraMcpClient ──► Jira MCP server (mcp-atlassian)
                                                                         │
                                                                         ▼
                                                                  Jira Cloud REST API
```

1. The model gets the system prompt from `SprintAIAgent` plus your question.
2. It calls the Jira tools it needs, typically:
   - `jira_get_agile_boards` – find the board of the project
   - `jira_get_sprints_from_board` – find the active sprint (name, dates, goal)
   - `jira_search` – list the sprint's issues using
     `project = DIS AND sprint in openSprints()`
   - `jira_get_issue` – details of a single issue when asked
3. `CompactingToolProvider` strips noisy fields from each tool result (avatar URLs, account ids,
   emails, colours) so the result fits the context of a local model.
4. The model writes the final Markdown report.

| Class | Responsibility |
|---|---|
| `agents/SprintAIAgent` | System prompt and agent interface |
| `service/SprintAIAgentService` | Builds the agent with the Jira tools, logs progress, maps the answer |
| `resource/SprintAIAgentResource` | `POST /sprint/chat` endpoint |
| `mcp_clients/ManagedJiraMcpClient` | Connects to the Jira MCP server at startup, exposes its tools |
| `mcp_clients/CompactingToolProvider` | Shrinks tool results before they reach the model |
| `config/JiraMcpClientConfig` | `jiraMcpClient` block of `application.yml` |

## Prerequisites

- Java 17+ and Maven
- [Ollama](https://ollama.com) running locally with the model configured in `llm.modelName`
  (a 32k context window is needed; see the comment in `application.yml`)
- [`uv`](https://docs.astral.sh/uv/) (for `uvx`) **or** Docker, to run the Jira MCP server
- A Jira Cloud account with access to the project

## Setup

### 1. Create a Jira API token

1. Open <https://id.atlassian.com/manage-profile/security/api-tokens>.
2. Click **Create API token** (the plain one, *not* "with scopes" – scoped tokens need a different
   API URL that the MCP server does not use).
3. Copy the token. Treat it like a password: never commit it, paste it in chats, or put it in
   `application.yml`.

### 2. Start the Jira MCP server

The credentials live only in the environment of the MCP server, never in this application.

```bash
export JIRA_URL=https://<your-site>.atlassian.net
export JIRA_USERNAME=<your-email>
read -s JIRA_API_TOKEN && export JIRA_API_TOKEN   # paste the token; it is not echoed
export READ_ONLY_MODE=true                         # the agent can only read, never edit tickets
export ALLOW_GLOBAL_CRED_FALLBACK=true             # let HTTP callers use the credentials above
export TOOLSETS=all

uvx mcp-atlassian --transport streamable-http --host 127.0.0.1 --port 9000 -vv
```

Wait for `Uvicorn running on http://127.0.0.1:9000`.

> `--host 127.0.0.1` matters: together with `ALLOW_GLOBAL_CRED_FALLBACK=true`, anyone who can reach
> the port can query Jira as you. Keep it bound to localhost.

Docker alternative (same variables, pass the token from your shell rather than typing it):

```bash
docker run --rm -p 127.0.0.1:9000:9000 \
  -e JIRA_URL -e JIRA_USERNAME -e JIRA_API_TOKEN \
  -e READ_ONLY_MODE=true -e ALLOW_GLOBAL_CRED_FALLBACK=true -e TOOLSETS=all \
  ghcr.io/sooperset/mcp-atlassian:latest --transport streamable-http --host 0.0.0.0 --port 9000
```

### 3. Configure the application

`src/main/resources/application.yml`:

```yaml
jiraMcpClient:
  enabled: true
  url: http://localhost:9000/mcp
  key: jira-mcp-server
  projectKey: DIS            # project the agent reports on
  timeoutSeconds: 60
  allowedTools:              # read-only tools exposed to the model; empty = all
    - jira_search
    - jira_get_issue
    - jira_get_agile_boards
    - jira_get_sprints_from_board
    - jira_get_sprint_issues
    - jira_get_board_issues
```

Optional settings in `JiraMcpClientConfig`:

- `droppedResultFields` – JSON fields removed from tool results (defaults: `avatar_url`,
  `account_id`, `email`, `color`, `browse_url`, `self`, `total`, `start_at`).
- `headers` – extra HTTP headers for the MCP server. Do not put credentials here in a committed file.

Every MCP client that is `enabled` must be reachable at startup, otherwise the application refuses
to start. Set `mcpClient.enabled: false` if the Git MCP server is not running.

### 4. Run the application

Run `com.langchain.central.LangchainApp` from the IDE. On startup the log shows:

```
Connected to Jira MCP server http://localhost:9000/mcp and discovered N tools: [...]
```

## Usage

```bash
curl -X POST localhost:8090/sprint/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"What are the details of the current DIS sprint and how is it progressing? Give status counts, blockers and who owns what."}'
```

The response is an `AIResponse`: `response` holds the Markdown report and `tools` lists every Jira
tool the model called with its arguments and (compacted) result.

Example questions:

- "Summarise the current sprint: status counts, blockers and who owns what."
- "Which issues are still To Do or QA In-Progress, and who owns them?"
- "What is assigned to Ankush Nakaskar in this sprint?"
- "Give me the details and latest comments of DIS-1333."
- "Which epics does this sprint contribute to?"

### Following progress

A full report takes a few minutes on a local 9B model: three tool calls, then the model reads
~20k tokens of sprint data before writing. The endpoint answers only once the report is complete,
so follow the application log meanwhile:

```
[sprint:default] 0s Asking the model about project DIS: ...
[sprint:default] 2s Calling Jira tool jira_get_agile_boards with {"project_key":"DIS"}
[sprint:default] 2s Jira tool jira_get_agile_boards returned 60 characters
[sprint:default] 5s Calling Jira tool jira_get_sprints_from_board with {"board_id":"1417","state":"active"}
[sprint:default] 9s Calling Jira tool jira_search with {"jql":"project = DIS AND sprint in openSprints() ..."}
[sprint:default] 10s Jira tool jira_search returned 30127 characters
[sprint:default] 95s Model started writing the answer
[sprint:default] 240s Answer complete: 4800 characters, 22854 tokens in total
```

The long gap before "Model started writing the answer" is the model reading the sprint data; it is
not hung. `llm.logRequests` / `llm.logResponses` dump the raw LLM HTTP traffic (every token and full
tool results) and are only worth enabling for deep debugging.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| App fails at startup with `ConnectException` in `ManagedGitAssistanceMcpClient` | Git MCP server not running – start it or set `mcpClient.enabled: false` |
| App fails at startup in `ManagedJiraMcpClient` | Jira MCP server not running on `jiraMcpClient.url` |
| MCP server answers `401 Authentication required: no Atlassian credentials were provided` | Start it with `ALLOW_GLOBAL_CRED_FALLBACK=true` |
| MCP server logs Jira authentication failures | Wrong email/token, or a scoped token – create a plain API token |
| `Jinja Exception: No user query found in messages` | Prompt exceeded the model's context and Ollama dropped the question. Add fields to `droppedResultFields`, ask about fewer issues, or use a larger context model |
| `GET /mcp 406 Not Acceptable` in the MCP server log | Harmless – that is a browser opening the URL |

## Limitations

- Counts in the report are produced by the LLM and a small local model can miscount; verify
  important numbers in Jira.
- `jira_search` returns at most 50 issues per call, so very large sprints are only partially covered.
- The agent has no conversation memory; each request is answered on its own.
- Read-only by design: `READ_ONLY_MODE=true` on the server and `allowedTools` on the client.
