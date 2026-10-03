import { useCallback, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { streamChat } from './chatStream.js';

const SESSION_STORAGE_KEY = 'langchain-mcp-session-id';

function newSessionId() {
  return `ui-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function loadSessionId() {
  const stored = window.localStorage.getItem(SESSION_STORAGE_KEY);
  if (stored) {
    return stored;
  }
  const created = newSessionId();
  window.localStorage.setItem(SESSION_STORAGE_KEY, created);
  return created;
}

function formatDuration(nanos) {
  if (!nanos) {
    return null;
  }
  return `${(nanos / 1_000_000_000).toFixed(1)}s`;
}

/** The activity line of a tool or MCP event, kept short enough for a single row. */
function activityLabel(event) {
  if (event.type === 'tool_call') {
    return `Calling ${event.tool?.name ?? 'a tool'}`;
  }
  if (event.type === 'tool_result') {
    return `${event.tool?.name ?? 'Tool'} finished`;
  }
  return event.content ?? 'MCP server activity';
}

function Activity({ entries, streaming }) {
  if (entries.length === 0) {
    return null;
  }
  return (
    <ul className="activity">
      {entries.map((entry) => (
        <li key={entry.key} className={`activity-item activity-${entry.type}`}>
          <span className="activity-label">{entry.label}</span>
          {entry.detail ? <pre className="activity-detail">{entry.detail}</pre> : null}
        </li>
      ))}
      {streaming ? <li className="activity-item activity-pending">Working…</li> : null}
    </ul>
  );
}

function Stats({ response }) {
  if (!response) {
    return null;
  }
  const parts = [
    response.doneReason ? `finished: ${response.doneReason}` : null,
    response.promptEvalCount != null ? `prompt ${response.promptEvalCount} tokens` : null,
    response.evalCount != null ? `answer ${response.evalCount} tokens` : null,
    formatDuration(response.totalDuration),
  ].filter(Boolean);

  return parts.length ? <div className="stats">{parts.join(' · ')}</div> : null;
}

function Message({ message }) {
  if (message.role === 'user') {
    return (
      <div className="message message-user">
        <div className="bubble">{message.text}</div>
      </div>
    );
  }

  return (
    <div className="message message-assistant">
      <div className="bubble">
        <Activity entries={message.activity} streaming={message.streaming} />
        {message.thinking ? (
          <details className="thinking">
            <summary>Reasoning</summary>
            <pre>{message.thinking}</pre>
          </details>
        ) : null}
        {message.text ? (
          <div className="markdown">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.text}</ReactMarkdown>
          </div>
        ) : null}
        {!message.text && message.streaming ? <span className="caret" /> : null}
        {message.error ? <div className="error">{message.error}</div> : null}
        <Stats response={message.response} />
      </div>
    </div>
  );
}

export default function App() {
  const [sessionId, setSessionId] = useState(loadSessionId);
  const [model, setModel] = useState('');
  const [useTools, setUseTools] = useState(true);
  const [prompt, setPrompt] = useState('');
  const [messages, setMessages] = useState([]);
  const [streaming, setStreaming] = useState(false);

  const abortRef = useRef(null);
  const bottomRef = useRef(null);
  const activityCounter = useRef(0);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    window.localStorage.setItem(SESSION_STORAGE_KEY, sessionId);
  }, [sessionId]);

  /**
   * Events arrive one at a time and each one only changes the answer being written, so the
   * update is applied to the last message instead of rebuilding the whole list.
   */
  const applyEvent = useCallback((event) => {
    setMessages((current) => {
      const next = [...current];
      const index = next.length - 1;
      const message = { ...next[index] };

      switch (event.type) {
        case 'token':
          message.text += event.content ?? '';
          break;
        case 'thinking':
          message.thinking += event.content ?? '';
          break;
        case 'tool_call':
        case 'tool_result':
        case 'mcp':
          activityCounter.current += 1;
          message.activity = [
            ...message.activity,
            {
              key: activityCounter.current,
              type: event.type,
              label: activityLabel(event),
              detail:
                event.type === 'tool_call'
                  ? event.tool?.arguments
                  : undefined,
            },
          ];
          break;
        case 'done':
          message.response = event.response;
          // the final payload is authoritative: it also covers the case where the model wrote
          // nothing and the server fell back to rendering the raw tool results
          message.text = event.response?.response ?? message.text;
          message.streaming = false;
          break;
        case 'error':
          message.error = event.error ?? 'The answer failed';
          message.streaming = false;
          break;
        default:
          break;
      }

      next[index] = message;
      return next;
    });
  }, []);

  const send = useCallback(
    async (event) => {
      event.preventDefault();
      const trimmed = prompt.trim();
      if (!trimmed || streaming) {
        return;
      }

      setMessages((current) => [
        ...current,
        { role: 'user', text: trimmed },
        {
          role: 'assistant',
          text: '',
          thinking: '',
          activity: [],
          response: null,
          error: null,
          streaming: true,
        },
      ]);
      setPrompt('');
      setStreaming(true);

      const controller = new AbortController();
      abortRef.current = controller;

      try {
        await streamChat(
          {
            prompt: trimmed,
            sessionId,
            useTools,
            stream: true,
            ...(model.trim() ? { model: model.trim() } : {}),
          },
          applyEvent,
          controller.signal,
        );
      } catch (failure) {
        applyEvent({
          type: failure.name === 'AbortError' ? 'done' : 'error',
          error: failure.message,
        });
      } finally {
        abortRef.current = null;
        setStreaming(false);
        // a stream cut short by a network failure never delivered its terminal event, so the
        // answer would otherwise keep showing as still being written
        setMessages((current) => {
          const next = [...current];
          const index = next.length - 1;
          if (index >= 0 && next[index].streaming) {
            next[index] = { ...next[index], streaming: false };
          }
          return next;
        });
      }
    },
    [applyEvent, model, prompt, sessionId, streaming, useTools],
  );

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const resetSession = useCallback(() => {
    abortRef.current?.abort();
    setMessages([]);
    setSessionId(newSessionId());
  }, []);

  return (
    <div className="app">
      <header className="header">
        <h1>LangChain MCP Chat</h1>
        <div className="controls">
          <label>
            Session
            <input
              value={sessionId}
              onChange={(e) => setSessionId(e.target.value)}
              spellCheck={false}
            />
          </label>
          <label>
            Model
            <input
              value={model}
              placeholder="server default"
              onChange={(e) => setModel(e.target.value)}
              spellCheck={false}
            />
          </label>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={useTools}
              onChange={(e) => setUseTools(e.target.checked)}
            />
            Git tools
          </label>
          <button type="button" onClick={resetSession}>
            New conversation
          </button>
        </div>
      </header>

      <main className="conversation">
        {messages.length === 0 ? (
          <p className="empty">
            Ask about a repository. With Git tools enabled the assistant calls the MCP server and
            reports every call here as it happens.
          </p>
        ) : (
          messages.map((message, index) => (
            <Message key={index} message={message} />
          ))
        )}
        <div ref={bottomRef} />
      </main>

      <form className="composer" onSubmit={send}>
        <textarea
          value={prompt}
          placeholder="Ask about a repository…"
          rows={3}
          onChange={(e) => setPrompt(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              send(e);
            }
          }}
        />
        {streaming ? (
          <button type="button" className="stop" onClick={stop}>
            Stop
          </button>
        ) : (
          <button type="submit" disabled={!prompt.trim()}>
            Send
          </button>
        )}
      </form>
    </div>
  );
}
