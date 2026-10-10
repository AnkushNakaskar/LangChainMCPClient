package com.langchain.central.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.assistance.GitAssistance;
import com.langchain.central.assistance.GitStreamingAssistance;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.mcp.ManagedMcpClient;
import com.langchain.central.mcp.McpStreamEvents;
import com.langchain.central.memory.ChatMemories;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.model.StreamEvent;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class LangChainService {

    private final LLMConfig llmConfig;
    private final ManagedMcpClient mcpClient;
    private final McpStreamEvents mcpStreamEvents;
    private final ChatMemories chatMemories;

    @Inject
    public LangChainService(final LLMConfig llmConfig,
                            final ManagedMcpClient mcpClient,
                            final McpStreamEvents mcpStreamEvents,
                            final ChatMemories chatMemories) {
        this.llmConfig = llmConfig;
        this.mcpClient = mcpClient;
        this.mcpStreamEvents = mcpStreamEvents;
        this.chatMemories = chatMemories;
    }


    /**
     * Sends the prompt to the model and maps the outcome, including any tool the model called,
     * onto {@link AIResponse}.
     */
    public AIResponse chatStream(final AIRequest request) {
        final String sessionId = request.getSessionId();
        final long startedAt = System.nanoTime();
        final GitStreamingAssistance assistant = getGitStreamingAssistant();
        final TokenStream tokenStream = assistant.chat(sessionId, request.getPrompt());
        CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();

        tokenStream
                .onPartialResponse((String partialResponse) -> System.out.println(partialResponse))
                .onPartialThinking((PartialThinking partialThinking) -> System.out.println(partialThinking))
                .onIntermediateResponse((ChatResponse intermediateResponse) -> System.out.println(intermediateResponse))
                // This will be invoked every time a new partial tool call (usually containing a single token of the tool's arguments) is available.
                .onPartialToolCall((PartialToolCall partialToolCall) -> System.out.println(partialToolCall))
                // This will be invoked right before a tool is executed. BeforeToolExecution contains ToolExecutionRequest (e.g. tool name, tool arguments, etc.)
                .beforeToolExecution((BeforeToolExecution beforeToolExecution) -> System.out.println(beforeToolExecution))
                // This will be invoked right after a tool is executed. ToolExecution contains ToolExecutionRequest and tool execution result.
                .onToolExecuted((ToolExecution toolExecution) -> System.out.println(toolExecution))
                // This will be invoked for raw provider streaming events that are not already exposed via the typed callbacks above (e.g. server-tool lifecycle events). See the "Unmapped Raw Events" section of Response Streaming.
                .onUnmappedRawEvent((Object rawEvent) -> System.out.println(rawEvent))
                .onCompleteResponse((ChatResponse response) -> futureResponse.complete(response))
                .onError((Throwable error) -> futureResponse.completeExceptionally(error))
                .start();

        final ChatResponse response = futureResponse.join(); // Blocks the main thread until the streaming process is complete
        final String streamedText = response.aiMessage() == null ? null : response.aiMessage().text();
        return LLMConvertors.toAIResponse(response, response.tokenUsage(), streamedText, null,
                llmConfig.getModelName(), sessionId, System.nanoTime() - startedAt);
    }


    /**
     * Sends the prompt to the model and maps the outcome, including any tool the model called,
     * onto {@link AIResponse}.
     */
    public AIResponse chat(final AIRequest request) {
        final String sessionId = request.getSessionId();
        final long startedAt = System.nanoTime();
        final GitAssistance assistant = getAssistant();
        final Result<String> result = assistant.chat(sessionId, request.getPrompt());
        return LLMConvertors.toAIResponse(result, llmConfig.getModelName(), sessionId, System.nanoTime() - startedAt);
    }

    /**
     * Same conversation as {@link #chat}, delivered one event at a time.
     *
     * <p>Returns as soon as the request is on its way: the events are produced by the model
     * client's threads and handed to {@code sink}, which therefore has to be safe to call from a
     * thread other than this one. The stream always ends with exactly one {@code done} or
     * {@code error} event, so the caller has a definite point at which to close the response.
     *
     * @param request the prompt, plus the session, model override and tool flag
     * @param sink receives every event of this answer
     */
    public void chatStream(final AIRequest request,
                           final Consumer<StreamEvent> sink) {
        final String sessionId = request.getSessionId();
        final boolean useTools = request.isUseTools() && mcpClient.isEnabled();
        final Consumer<StreamEvent> trackedSink = event -> {
            if (event.getType()
                    .isTerminal()) {
                mcpStreamEvents.unregister(sessionId);
            }
            sink.accept(event);
        };
        mcpStreamEvents.register(sessionId, trackedSink);

        final StreamingChatSession streamSession = new StreamingChatSession(sessionId, llmConfig.getModelName(),
                LLMConvertors.toStreamingChatModel(llmConfig), chatMemories.of(sessionId), useTools
                                                                                           ? toolsFor(sessionId, request.getPrompt())
                                                                                           : null,
                llmConfig.getMaxToolCallingRoundTrips(), trackedSink);
        try {
            streamSession.start(GitAssistance.SYSTEM_PROMPT, request.getPrompt());
        } catch (Exception e) {
            streamSession.fail(e);
        }
    }

//TODO : We need to check what if no matching tool found, then generic response should be invoked
    private ToolProviderResult toolsFor(final String sessionId,
                                        final String prompt) {
        return mcpClient.toolProvider()
                .provideTools(ToolProviderRequest.builder()
                        .userMessage(UserMessage.from(prompt))
                        .invocationContext(InvocationContext.builder()
                                .chatMemoryId(sessionId)
                                .build())
                        .build());
    }

    private GitAssistance getAssistant() {
        final StreamingChatModel model = LLMConvertors.toChatModel(llmConfig);

        final AiServices<GitAssistance> builder = AiServices.builder(GitAssistance.class)
                .streamingChatModel(model)
                .chatMemoryProvider(chatMemories::of);
        builder.toolProvider(mcpClient.toolProvider())
                .maxToolCallingRoundTrips(llmConfig.getMaxToolCallingRoundTrips());
        return builder.build();
    }

    private GitStreamingAssistance getGitStreamingAssistant() {
        final StreamingChatModel model = LLMConvertors.toChatModel(llmConfig);

        final AiServices<GitStreamingAssistance> builder = AiServices.builder(GitStreamingAssistance.class)
                .streamingChatModel(model)
                .chatMemoryProvider(chatMemories::of);
        builder.toolProvider(mcpClient.toolProvider())
                .maxToolCallingRoundTrips(llmConfig.getMaxToolCallingRoundTrips());
        return builder.build();
    }
}
