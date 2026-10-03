package com.langchain.central.mcp;

import com.google.inject.Singleton;
import com.langchain.central.model.StreamEvent;
import com.langchain.central.model.StreamEventType;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.client.logging.McpLogMessage;
import dev.langchain4j.mcp.client.logging.McpLogMessageHandler;
import dev.langchain4j.mcp.client.progress.McpProgressHandler;
import dev.langchain4j.mcp.client.progress.McpProgressNotification;
import dev.langchain4j.mcp.protocol.McpCallToolParams;
import dev.langchain4j.mcp.protocol.McpClientRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns what the MCP client does into {@link StreamEvent}s on the chat stream of the session that
 * caused it.
 *
 * <p>The MCP server answers a tool call over its own event stream, so a call can be in flight for
 * a while before the model receives anything to say. Relaying the round trip keeps the caller
 * informed during that gap instead of leaving the stream silent.
 *
 * <p>Tool calls are routed by the chat memory id, which is this application's session id, so
 * concurrent conversations do not see each other's tool activity. A progress notification is not
 * part of a request/response pair and carries no session id, only the progress token of the call
 * it belongs to. The token of every call in flight is therefore remembered against its session,
 * which is what keeps progress correct now that the MCP server answers calls asynchronously and
 * several of them can be running at once. A notification that cannot be attributed is forwarded
 * only when a single stream is open, and logged otherwise, because guessing its owner would
 * attribute one session's work to another.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class McpStreamEvents implements McpClientListener, McpProgressHandler, McpLogMessageHandler {

    private final Map<String, Consumer<StreamEvent>> sinks = new ConcurrentHashMap<>();

    /**
     * The session each tool call in flight belongs to, keyed by the progress token the client sent
     * with it. The MCP client derives that token from the request id, which is how a progress
     * notification arriving on its own is traced back to the conversation that caused it.
     */
    private final Map<String, String> sessionsByProgressToken = new ConcurrentHashMap<>();

    /**
     * Routes MCP activity of {@code sessionId} to {@code sink} until {@link #unregister} is called.
     */
    public void register(final String sessionId, final Consumer<StreamEvent> sink) {
        sinks.put(sessionId, sink);
    }

    public void unregister(final String sessionId) {
        sinks.remove(sessionId);
        sessionsByProgressToken.values().remove(sessionId);
    }

    @Override
    public void beforeExecuteTool(final McpCallContext context) {
        final String sessionId = sessionIdOf(context);
        final String progressToken = progressTokenOf(context);
        if (sessionId != null && progressToken != null) {
            sessionsByProgressToken.put(progressToken, sessionId);
        }
        emit(context, "Calling " + toolName(context) + " on the MCP server");
    }

    @Override
    public void afterExecuteTool(final McpCallContext context,
                                 final ToolExecutionResult result,
                                 final Map<String, Object> attributes) {
        forgetProgressToken(context);
        emit(context, toolName(context) + " returned from the MCP server"
                + (result != null && result.isError() ? " with an error" : ""));
    }

    @Override
    public void onExecuteToolError(final McpCallContext context, final Throwable error) {
        forgetProgressToken(context);
        emit(context, toolName(context) + " failed on the MCP server: " + error.getMessage());
    }

    @Override
    public void onProgress(final McpProgressNotification notification) {
        final String message = notification.message() == null
                ? "MCP server progress " + notification.progress()
                : notification.message();

        final String sessionId = notification.progressToken() == null
                ? null
                : sessionsByProgressToken.get(notification.progressToken());
        if (sessionId == null) {
            broadcast(message);
            return;
        }

        final Consumer<StreamEvent> sink = sinks.get(sessionId);
        if (sink != null) {
            publish(sink, sessionId, message);
        }
    }

    @Override
    public void handleLogMessage(final McpLogMessage logMessage) {
        broadcast("MCP server " + logMessage.level() + ": " + logMessage.data());
    }

    private void emit(final McpCallContext context, final String message) {
        final String sessionId = sessionIdOf(context);
        if (sessionId == null) {
            log.debug("MCP event outside a chat session: {}", message);
            return;
        }
        final Consumer<StreamEvent> sink = sinks.get(sessionId);
        if (sink == null) {
            return;
        }
        publish(sink, sessionId, message);
    }

    /** See the class comment on why an unattributable notification reaches at most one stream. */    private void broadcast(final String message) {
        if (sinks.size() != 1) {
            log.debug("Unattributable MCP notification with {} open stream(s): {}",
                    sinks.size(), message);
            return;
        }
        sinks.forEach((sessionId, sink) -> publish(sink, sessionId, message));
    }

    /**
     * A sink that throws must not break the MCP client, which would turn a cosmetic streaming
     * problem into a failed tool call.
     */
    private void publish(final Consumer<StreamEvent> sink,
                         final String sessionId,
                         final String message) {
        try {
            sink.accept(StreamEvent.builder()
                    .type(StreamEventType.MCP)
                    .sessionId(sessionId)
                    .content(message)
                    .build());
        } catch (Exception e) {
            log.warn("Could not publish MCP event to session {}", sessionId, e);
        }
    }

    private String sessionIdOf(final McpCallContext context) {
        if (context == null || context.invocationContext() == null) {
            return null;
        }
        final Object chatMemoryId = context.invocationContext().chatMemoryId();
        return chatMemoryId == null ? null : chatMemoryId.toString();
    }

    /**
     * The token the server reports progress of this call under. The MCP client sends the request
     * id as the progress token, so the id of the outgoing message identifies the call.
     */
    private String progressTokenOf(final McpCallContext context) {
        if (context == null || context.message() == null || context.message().getId() == null) {
            return null;
        }
        return String.valueOf(context.message().getId());
    }

    /** A finished call can produce no further progress, so its token must not outlive it. */
    private void forgetProgressToken(final McpCallContext context) {
        final String progressToken = progressTokenOf(context);
        if (progressToken != null) {
            sessionsByProgressToken.remove(progressToken);
        }
    }

    private String toolName(final McpCallContext context) {
        if (context != null
                && context.message() instanceof McpClientRequest request
                && request.getParams() instanceof McpCallToolParams params
                && params.getName() != null) {
            return params.getName();
        }
        return "tool";
    }
}
