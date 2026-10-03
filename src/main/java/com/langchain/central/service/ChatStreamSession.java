package com.langchain.central.service;

import com.langchain.central.model.StreamEvent;
import com.langchain.central.model.StreamEventType;
import com.langchain.central.model.ToolResponse;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Translates one langchain4j {@link TokenStream} into {@link StreamEvent}s for a single request.
 *
 * <p>Each instance serves exactly one answer, because it accumulates that answer's text and tool
 * calls so the closing {@code done} event can carry the same complete {@code AIResponse} the
 * blocking endpoint returns. The callbacks run on the model client's threads, so the collections
 * are concurrent and the terminal event is emitted at most once: langchain4j calls neither
 * {@code onError} after {@code onCompleteResponse} nor the reverse, but a caller must not be left
 * waiting if that ever changes.
 *
 * @author ankush.nakaskar
 */
@Slf4j
final class ChatStreamSession {

    private final String sessionId;
    private final String modelName;
    private final Consumer<StreamEvent> sink;
    private final long startedAt = System.nanoTime();
    private final StringBuilder answer = new StringBuilder();
    private final List<ToolResponse> tools = new CopyOnWriteArrayList<>();
    private volatile boolean finished;

    ChatStreamSession(final String sessionId,
                      final String modelName,
                      final Consumer<StreamEvent> sink) {
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.sink = sink;
    }

    /** Wires the callbacks and starts the request to the model. */
    void start(final TokenStream tokenStream) {
        emit(StreamEvent.builder()
                .type(StreamEventType.START)
                .sessionId(sessionId)
                .model(modelName)
                .build());

        tokenStream.onPartialResponse(this::onToken)
                .onPartialThinking(thinking -> emit(StreamEvent.builder()
                        .type(StreamEventType.THINKING)
                        .sessionId(sessionId)
                        .content(thinking.text())
                        .build()))
                .beforeToolExecution(before -> emit(StreamEvent.builder()
                        .type(StreamEventType.TOOL_CALL)
                        .sessionId(sessionId)
                        .tool(ToolResponse.builder()
                                .name(before.request().name())
                                .arguments(before.request().arguments())
                                .build())
                        .build()))
                .onToolExecuted(this::onToolExecuted)
                .onCompleteResponse(this::onComplete)
                .onError(this::onError)
                .start();
    }

    /**
     * Fails the stream from outside, used when the request cannot even be handed to the model.
     */
    void fail(final Throwable error) {
        onError(error);
    }

    private void onToken(final String token) {
        answer.append(token);
        emit(StreamEvent.builder()
                .type(StreamEventType.TOKEN)
                .sessionId(sessionId)
                .content(token)
                .build());
    }

    private void onToolExecuted(final ToolExecution execution) {
        final ToolResponse tool = LLMConvertors.toToolResponse(execution);
        tools.add(tool);
        emit(StreamEvent.builder()
                .type(StreamEventType.TOOL_RESULT)
                .sessionId(sessionId)
                .tool(tool)
                .build());
    }

    private void onComplete(final ChatResponse chatResponse) {
        if (finished) {
            return;
        }
        finished = true;
        emit(StreamEvent.builder()
                .type(StreamEventType.DONE)
                .sessionId(sessionId)
                .response(LLMConvertors.toAIResponse(chatResponse, answer.toString(), tools,
                        modelName, sessionId, System.nanoTime() - startedAt))
                .build());
    }

    private void onError(final Throwable error) {
        if (finished) {
            log.warn("Ignoring error after the stream of session {} already finished",
                    sessionId, error);
            return;
        }
        finished = true;
        log.error("Streaming chat failed on session {}", sessionId, error);
        emit(StreamEvent.builder()
                .type(StreamEventType.ERROR)
                .sessionId(sessionId)
                .error(error.getMessage() == null ? error.toString() : error.getMessage())
                .build());
    }

    /**
     * A sink that throws, typically because the caller disconnected, must not propagate back into
     * the model client, which would surface as a failed generation instead of an abandoned one.
     */
    private void emit(final StreamEvent event) {
        try {
            sink.accept(event);
        } catch (Exception e) {
            log.warn("Could not deliver a {} event of session {}", event.getType(), sessionId, e);
        }
    }
}
