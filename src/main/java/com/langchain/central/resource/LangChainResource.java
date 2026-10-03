package com.langchain.central.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.model.StreamEvent;
import com.langchain.central.model.StreamEventType;
import com.langchain.central.service.LangChainService;
import com.langchain.central.sse.SseStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP entry point of the application.
 *
 * <p>The resource only validates the request, hands it to {@link LangChainService} and shapes the
 * outcome for the caller. Nothing about models, memory or tools is decided here.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Path("/langchain")
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class LangChainResource {

    static final String SERVER_SENT_EVENTS = "text/event-stream";

    /**
     * How long the stream may stay silent before a comment is written. A tool call against the MCP
     * server can outlast an idle timeout anywhere on the path, and a connection dropped halfway
     * loses the answer that was already being written.
     */
    private static final long HEARTBEAT_SECONDS = 15;

    private final LangChainService langChainService;
    private final LLMConfig llmConfig;
    private final ObjectMapper objectMapper;

    /**
     * @param request prompt, plus optional sessionId, model override and {@code useTool} flag
     * @return the answer, the token counts and every tool the model called while answering
     */
    @POST
    @Path("/chat")
    @Operation(summary = "Chat with the Git assistant")
    @RequestBody(description = "Prompt, and optionally a sessionId, a model override and useTool")
    public AIResponse chat(@Valid @NotNull final AIRequest request) {
        log.info("Chat requested on session {}", request);
        try {
            final AIResponse response = langChainService.chat(request);
            log.info("Assistant answered with {} characters and {} tool calls",
                    response.getResponse() == null ? 0 : response.getResponse().length(),
                    response.getTools() == null ? 0 : response.getTools().size());
            return response;
        } catch (Exception e) {
            log.error("Chat failed for prompt {}", request.getPrompt(), e);
            return AIResponse.builder()
                    .createdAt(Instant.now())
                    .sessionId(request.getSessionId())
                    .done(false)
                    .doneReason("error")
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * The same conversation as {@link #chat}, delivered as Server-Sent Events: the answer arrives
     * token by token and the tool calls the model makes against the MCP server are announced while
     * they happen instead of only in the final payload.
     *
     * <p>The stream ends with a {@code done} or an {@code error} event. The {@code done} event
     * carries the complete {@link AIResponse}, so a caller that only wants the finished answer can
     * ignore every other event and still receive exactly what {@code /chat} returns.
     *
     * @param request same payload as {@link #chat}
     */
    @POST
    @Path("/chat/stream")
    @Produces(SERVER_SENT_EVENTS)
    @Operation(summary = "Chat with the Git assistant, streamed as Server-Sent Events")
    @RequestBody(description = "Prompt, and optionally a sessionId, a model override and useTool")
    public Response chatStream(@Valid @NotNull final AIRequest request) {
        log.info("Streaming chat requested on session {}", request.getSessionId());

        final StreamingOutput body = output -> writeStream(request, output);

        return Response.ok(body, SERVER_SENT_EVENTS)
                // the answer is produced while it is sent, so nothing on the way may buffer or
                // reuse it; X-Accel-Buffering switches buffering off in an nginx in front
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("X-Accel-Buffering", "no")
                .header("Connection", "keep-alive")
                .build();
    }

    /**
     * Drains the events of one answer onto the response.
     *
     * <p>The model client produces events on its own threads while this, the request thread, owns
     * the socket. Passing them through a queue keeps all writing on a single thread, which is what
     * both the servlet output stream and the SSE framing require.
     */
    private void writeStream(final AIRequest request, final OutputStream output)
            throws IOException {

        final BlockingQueue<StreamEvent> events = new LinkedBlockingQueue<>();
        final long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(llmConfig.getTimeoutSeconds());

        try (SseStream stream = new SseStream(output, objectMapper)) {
            try {
                langChainService.chatStream(request, events::add);
            } catch (Exception e) {
                log.error("Streaming chat could not be started for session {}",
                        request.getSessionId(), e);
                stream.send(errorEvent(request, e.getMessage()));
                return;
            }

            while (true) {
                final StreamEvent event = events.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);

                if (event == null) {
                    if (System.nanoTime() > deadline) {
                        log.warn("Streaming chat on session {} produced no event within {}s",
                                request.getSessionId(), llmConfig.getTimeoutSeconds());
                        stream.send(errorEvent(request, "The model did not respond in time"));
                        return;
                    }
                    stream.heartbeat();
                    continue;
                }

                stream.send(event);
                if (event.getType().isTerminal()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Streaming chat on session {} was interrupted", request.getSessionId(), e);
        }
    }

    private StreamEvent errorEvent(final AIRequest request, final String message) {
        return StreamEvent.builder()
                .type(StreamEventType.ERROR)
                .sessionId(request.getSessionId())
                .error(message)
                .build();
    }

    @GET
    @Path("/health")
    @Operation(summary = "Liveness probe for the chat endpoint")
    public Response health() {
        return Response.ok().entity("{\"status\":\"UP\"}").build();
    }
}
