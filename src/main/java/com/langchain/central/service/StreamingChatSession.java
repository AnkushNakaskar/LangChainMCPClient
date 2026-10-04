package com.langchain.central.service;

import com.langchain.central.model.StreamEvent;
import com.langchain.central.model.StreamEventType;
import com.langchain.central.model.ToolResponse;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives one streamed answer directly on a {@link StreamingChatModel} and translates it into
 * {@link StreamEvent}s.
 *
 * <p>This is the raw langchain4j streaming API: the model is handed a {@link ChatRequest} together
 * with this handler and calls back as it produces output — text in
 * {@link #onPartialResponse}, reasoning in {@link #onPartialThinking}, tool calls in
 * {@link #onPartialToolCall} and {@link #onCompleteToolCall}, and the finished turn in
 * {@link #onCompleteResponse}. Everything an {@code AiServices} proxy would otherwise hide is
 * therefore done here — the system prompt, the chat memory and, above all, the tool-calling loop.
 * A model that asks for a tool ends its turn with tool-call requests instead of text, so the
 * requests are executed, their results appended to the history and the whole history sent again,
 * until the model finally answers in prose or the round trip budget runs out.
 *
 * <p>Each instance serves exactly one answer, because it accumulates that answer's text, token
 * usage and tool calls so the closing {@code done} event can carry the same complete
 * {@code AIResponse} the blocking endpoint returns. The callbacks run on the model client's
 * threads, so the collections are concurrent and the terminal event is emitted at most once:
 * langchain4j calls neither {@code onError} after {@code onCompleteResponse} nor the reverse, but a
 * caller must not be left waiting if that ever changes.
 *
 * @author ankush.nakaskar
 */
@Slf4j
final class StreamingChatSession implements StreamingChatResponseHandler {

    private final String sessionId;
    private final String modelName;
    private final StreamingChatModel model;
    private final ChatMemory memory;
    private final ToolProviderResult tools;
    private final int maxToolCallingRoundTrips;
    private final Consumer<StreamEvent> sink;

    private final long startedAt = System.nanoTime();
    private final StringBuilder answer = new StringBuilder();
    private final List<ToolResponse> executedTools = new CopyOnWriteArrayList<>();
    /** Tool calls already announced as a {@code tool_call} event; see {@link #announce}. */
    private final Set<String> announced = new LinkedHashSet<>();
    private volatile TokenUsage totalUsage;
    private volatile int toolCallingRoundTrips;
    private volatile boolean finished;

    StreamingChatSession(final String sessionId,
                         final String modelName,
                         final StreamingChatModel model,
                         final ChatMemory memory,
                         final ToolProviderResult tools,
                         final int maxToolCallingRoundTrips,
                         final Consumer<StreamEvent> sink) {
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.model = model;
        this.memory = memory;
        this.tools = tools;
        this.maxToolCallingRoundTrips = maxToolCallingRoundTrips;
        this.sink = sink;
    }

    /** Appends the turn to the history and sends the first request to the model. */
    void start(final String systemPrompt, final String userMessage) {
        log.info("Start of the event for  userMessage {}", userMessage);
        emit(StreamEvent.builder()
                .type(StreamEventType.START)
                .sessionId(sessionId)
                .model(modelName)
                .build());
        try {
            memory.add(SystemMessage.from(systemPrompt));
            memory.add(UserMessage.from(userMessage));
            sendToModel();
        } catch (Exception e) {
            fail(e);
        }
    }

    /**
     * Fails the stream from outside, used when the request cannot even be handed to the model.
     */
    void fail(final Throwable error) {
        onError(error);
    }

    /**
     * Sends the whole history, so the model sees its own earlier tool calls and their results and
     * can decide whether it has enough to answer.
     */
    private void sendToModel() {
        final ChatRequest.Builder request = ChatRequest.builder().messages(memory.messages());
        final List<ToolSpecification> specifications = toolSpecifications();
        if (!specifications.isEmpty()) {
            request.toolSpecifications(specifications);
        }
        model.chat(request.build(), this);
    }

    private List<ToolSpecification> toolSpecifications() {
        if (tools == null) {
            return List.of();
        }
        return new ArrayList<>(tools.tools().keySet());
    }

    @Override
    public void onPartialResponse(final String token) {
        log.info("StreamHandler onPartialResponse {}", token);
        answer.append(token);
        emit(StreamEvent.builder()
                .type(StreamEventType.TOKEN)
                .sessionId(sessionId)
                .content(token)
                .build());
    }

    @Override
    public void onPartialThinking(final PartialThinking thinking) {
        log.info("StreamHandler onPartialThinking {}", thinking);
        emit(StreamEvent.builder()
                .type(StreamEventType.THINKING)
                .sessionId(sessionId)
                .content(thinking.text())
                .build());
    }

    /**
     * A tool call is streamed like any other output, so its arguments arrive as fragments that are
     * not valid JSON on their own and cannot be put on the stream as they are. The fragments are
     * only logged; the assembled call arrives in {@link #onCompleteToolCall}.
     */
    @Override
    public void onPartialToolCall(final PartialToolCall partialToolCall) {
        log.info("Session {} is writing the arguments of tool {}: {}",
                sessionId, partialToolCall.name(), partialToolCall.partialArguments());
    }

    /**
     * The model finished writing one tool call. It is announced here rather than after the turn,
     * so the caller sees the call as soon as the model has committed to it instead of only once
     * the model has written every call of the turn.
     */
    @Override
    public void onCompleteToolCall(final CompleteToolCall completeToolCall) {
        log.info("StreamHandler onCompleteToolCall {}", completeToolCall);
        announce(completeToolCall.toolExecutionRequest());
    }

    /**
     * End of one turn of the model, which is not necessarily the end of the answer: a turn that
     * carries tool-call requests is answered by running them and asking the model again.
     */
    @Override
    public void onCompleteResponse(final ChatResponse chatResponse) {
        log.info("StreamHandler onCompleteResponse :: {}", chatResponse);
        if (finished) {
            return;
        }
        try {
            totalUsage = TokenUsage.sum(totalUsage, chatResponse.tokenUsage());

            final AiMessage aiMessage = chatResponse.aiMessage();
            memory.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                complete(chatResponse);
                return;
            }
            // a small model that is unhappy with a tool result will otherwise keep calling tools
            if (++toolCallingRoundTrips > maxToolCallingRoundTrips) {
                throw new IllegalStateException("The model exceeded the limit of "
                        + maxToolCallingRoundTrips + " sequential tool calls per answer");
            }
            executeTools(aiMessage.toolExecutionRequests());
            sendToModel();
        } catch (Exception e) {
            onError(e);
        }
    }

    @Override
    public void onError(final Throwable error) {
        log.info("Failed to send chat response for session {}", sessionId, error);
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
     * Runs every tool the model asked for and appends each result to the history.
     *
     * <p>A failing tool is reported back to the model as its result rather than ending the stream,
     * because the model can recover from a refused call, while the caller cannot recover from an
     * answer that stops halfway.
     */
    private void executeTools(final List<ToolExecutionRequest> requests) {
        for (final ToolExecutionRequest request : requests) {
            log.info("Executing tool execution for session {}", sessionId);
            announce(request);
            final String result = execute(request);
            memory.add(ToolExecutionResultMessage.from(request, result));

            final ToolResponse tool = ToolResponse.builder()
                    .name(request.name())
                    .arguments(request.arguments())
                    .result(result)
                    .build();
            executedTools.add(tool);
            emit(StreamEvent.builder()
                    .type(StreamEventType.TOOL_RESULT)
                    .sessionId(sessionId)
                    .tool(tool)
                    .build());
        }
    }

    /**
     * Emits the {@code tool_call} event of {@code request}, at most once.
     *
     * <p>Both {@link #onCompleteToolCall} and {@link #executeTools} reach a tool call, the first
     * while the model writes it and the second when it is about to run. Announcing from both keeps
     * the event early where the model streams its calls and present where it does not, and the
     * guard is what stops a model that streams them from producing the event twice.
     */
    private synchronized void announce(final ToolExecutionRequest request) {
        final String key = request.id() == null ? request.name() : request.id();
        if (!announced.add(key)) {
            return;
        }
        emit(StreamEvent.builder()
                .type(StreamEventType.TOOL_CALL)
                .sessionId(sessionId)
                .tool(ToolResponse.builder()
                        .name(request.name())
                        .arguments(request.arguments())
                        .build())
                .build());
    }

    /**
     * The session id is passed as the chat memory id, which is how the MCP client attributes the
     * call to this conversation and how its progress reaches this stream.
     */
    private String execute(final ToolExecutionRequest request) {
        final ToolExecutor executor = tools == null ? null : executorFor(request.name());
        if (executor == null) {
            log.warn("Model of session {} asked for the unknown tool {}", sessionId, request.name());
            return "Error: there is no tool named " + request.name();
        }
        try {
            return executor.execute(request, sessionId);
        } catch (Exception e) {
            log.error("Tool {} failed on session {}", request.name(), sessionId, e);
            return "Error: tool " + request.name() + " failed: " + e.getMessage();
        }
    }

    /**
     * {@code ToolProviderResult.toolExecutorByName} throws when the provider offered no tool of
     * that name, which a model is free to invent, so the lookup is done on the map instead.
     */
    private ToolExecutor executorFor(final String name) {
        for (final Map.Entry<ToolSpecification, ToolExecutor> tool : tools.tools().entrySet()) {
            if (tool.getKey().name().equals(name)) {
                return tool.getValue();
            }
        }
        return null;
    }

    private void complete(final ChatResponse chatResponse) {
        finished = true;
        emit(StreamEvent.builder()
                .type(StreamEventType.DONE)
                .sessionId(sessionId)
                .response(LLMConvertors.toAIResponse(chatResponse, totalUsage, answer.toString(),
                        executedTools, modelName, sessionId, System.nanoTime() - startedAt))
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
