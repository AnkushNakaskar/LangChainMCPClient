# Easy RAG in the Sprint AI Agent

The Sprint AI agent (`POST /sprint/chat`) can answer questions from local documents. At startup the
application reads every file in a configured folder, splits it into small chunks, embeds each chunk with a
local embedding model in Ollama and keeps the vectors in an **in-memory** embedding store. On every
question, the chunks closest to the question are retrieved and injected into the prompt before it reaches
the LLM.

It follows the LangChain4j [Easy RAG](https://docs.langchain4j.dev/tutorials/rag#easy-rag) tutorial, with one
difference: the embedding model is served by Ollama instead of the in-process model, so the same local
server provides both chat and embeddings.

## How it works

```
                       STARTUP (indexing, once)
 documentsPath ──► FileSystemDocumentLoader ──► DocumentSplitters.recursive(300, 30)
   (*.md, ...)                                         │
                                                       ▼
                                   OpenAiEmbeddingModel (Ollama /v1/embeddings)
                                                       │
                                                       ▼
                                         InMemoryEmbeddingStore<TextSegment>

                       REQUEST (retrieval, per question)
 curl ──► POST /sprint/chat ──► SprintAIAgentService ──► SprintAIAgent (AiServices)
                                                             │
                     EmbeddingStoreContentRetriever ◄────────┤ 1. embed question, find top-N chunks
                                                             │ 2. append chunks to the user message
                                   LLM (Ollama chat) ◄───────┘ 3. model answers from them
```

1. `LangchainApp.run(...)` calls `EasyRagService.initialize()` once at startup. It is called explicitly
   because Guice does not run `@PostConstruct` methods.
2. `EasyRagService` loads all files under `rag.documentsPath` (including subfolders), splits them into
   segments of up to 300 characters with a 30-character overlap, embeds them and stores them in memory.
3. `SprintAIAgentService` builds the `SprintAIAgent` with
   `.contentRetriever(easyRagService.contentRetriever())`. This happens only when indexing succeeded, so
   a RAG failure does not stop the endpoint from working.
4. For each request LangChain4j embeds the prompt, retrieves the `rag.maxResults` most similar chunks
   scoring at least `rag.minScore`, and adds them to the user message under
   `Answer using the following information:`.

| Class | Responsibility |
|---|---|
| `rag/EasyRagService` | Loads, splits, embeds and stores the documents; exposes the `ContentRetriever` |
| `config/RagConfig` | `rag` block of `application.yml` |
| `service/SprintAIAgentService` | Attaches the retriever to the Sprint agent |
| `LangchainApp` | Triggers indexing at startup and registers `SprintAIAgentResource` |

The store is in memory, so the documents are indexed again on every restart, and changes to the folder are
only picked up after a restart.

## Prerequisites

- Java 17+ and Maven
- [Ollama](https://ollama.com) running on `http://localhost:11434`
- The chat model from `llm.modelName` (e.g. `qwen3.5-9b-32k`)
- An embedding model. Any GGUF embedding model from Hugging Face can be pulled into Ollama:

```bash
ollama pull hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0        # better quality, ~640 MB
# or
ollama pull hf.co/nomic-ai/nomic-embed-text-v1.5-GGUF:Q8_0   # smaller and faster, ~140 MB
```

Check that the model can embed before starting the app:

```bash
curl -s http://localhost:11434/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0","input":"hello"}' | head -c 200
```

The response must contain an `"embedding":[...]` array.

## Configuration

`src/main/resources/application.yml`:

```yaml
rag:
  enabled: true
  # folder with the knowledge documents; every file in it (and its subfolders) is indexed
  documentsPath: /Users/ankush.nakaskar/Office/newCode/personal_projects/LangChainMCPClient/test_doc
  # must match the name shown by `ollama list`
  embeddingModelName: hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0
  maxResults: 5     # chunks injected into the prompt per question
  minScore: 0.0     # 0.0..1.0; raise it (e.g. 0.6) to drop weak matches
```

The embedding call uses `llm.baseUrl`, `llm.apiKey`, `llm.timeoutSeconds` and the `llm.logRequests` /
`llm.logResponses` flags, so it goes to the same Ollama server as the chat model.

To index the full knowledge base, point `documentsPath` at the `documents` folder instead of `test_doc`.

## Run

Start `com.langchain.central.LangchainApp`. When indexing succeeds, the log shows:

```
INFO  [EasyRagService]: documents are embeded are [Metadata { metadata = {file_name=..., absolute_directory_path=...} }]
INFO  [EasyRagService]: Easy RAG initialized with 1 documents from '.../test_doc' using embedding model 'hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0'.
```

If indexing fails, `Easy RAG initialization failed. Retrieval remains disabled.` is logged. The application
still starts, but answers come without document context.

## Usage

The app listens on plain **HTTP** (not HTTPS), and the JSON body must not contain `//` comments.

```bash
curl --request POST \
  --url http://localhost:8090/sprint/chat \
  --header 'Content-Type: application/json' \
  --data '{
  "prompt": "What is the name of the the project Owner?",
  "sessionId": "default",
  "useTools": true
}'
```

| Field | Meaning |
|---|---|
| `prompt` | The question. It is embedded and used to search the documents |
| `sessionId` | Echoed in the response and the log lines (`[sprint:default]`) |
| `useTools` | Accepted for payload compatibility; the Sprint endpoint does not read it |

`test_doc/Godric-GenAI-Gateway-Explained.md` contains:

```
## : What is the name of the the project Owner?
   Owner name is : Ankush Nakaskar , He is software engineer at Phonepe
```

The retriever finds that chunk, so the model answers with **Ankush Nakaskar**. The response is an
`AIResponse`: `response` holds the answer, and `promptEvalCount` / `totalTokenCount` include the injected
chunks.

To see exactly which chunks were added to the prompt, set `llm.logRequests: true` and look for
`Answer using the following information:` in the logged request.

## Jira tools and RAG together

`SprintAIAgentService` currently has `.toolProvider(jiraMcpClient.toolProvider())` commented out, so
`/sprint/chat` answers **only** from the documents. Re-enabling that line gives the model both the Jira
tools and the retrieved chunks. Keep in mind that:

- the `SprintAIAgent` system prompt tells the model to always fetch Jira data first, so a document question
  may still trigger Jira calls;
- the Jira MCP server must be running when `jiraMcpClient.enabled: true`, otherwise the application does
  not start.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `model "..." not found, try pulling it first` | `rag.embeddingModelName` is not in `ollama list`. Pull it, and copy the name exactly |
| `Post "http://127.0.0.1:<port>/tokenize": EOF` | Ollama's model runner crashed while embedding. Usually the model does not support embeddings in your Ollama version or a pull was incomplete. Run `brew upgrade ollama`, pull the model again and test it with the `/v1/embeddings` curl above. If it still fails, switch to the nomic model |
| `does not support embeddings` | A chat model was configured as `embeddingModelName`; use an embedding model |
| `Easy RAG document path ... is not a directory` | `rag.documentsPath` is wrong or not a folder |
| Answer ignores the documents | Check the log for `Easy RAG initialized ...`; lower `minScore` or raise `maxResults`; enable `llm.logRequests` to confirm the chunks reach the prompt |
| `curl` fails to connect | Use `http://`, not `https://`; port `8090` |
| Changed a document but the answer is old | The store is in memory; restart the application |

## Limitations

- In-memory store: the embeddings are lost on restart and rebuilt at every startup, which slows startup
  as the folder grows.
- Indexing runs during startup on the main thread; a large folder or a slow embedding model delays the
  server from accepting requests.
- Changing `embeddingModelName` requires a restart, because stored vectors and query vectors must come
  from the same model.
- Answer quality depends on the small chunks (300 characters); facts split across chunks may be missed.
