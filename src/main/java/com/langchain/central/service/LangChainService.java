package com.langchain.central.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.assistance.GitAssistance;
import com.langchain.central.assistance.GitStreamingAssistance;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.mcp.ManagedMcpClient;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class LangChainService {

    private final LLMConfig llmConfig;
    private final ManagedMcpClient mcpClient;

    @Inject
    public LangChainService(final LLMConfig llmConfig,
                            final ManagedMcpClient mcpClient) {
        this.llmConfig = llmConfig;
        this.mcpClient = mcpClient;
    }


    /**
     * Streaming with StreamChatModel
     * onto {@link AIResponse}.
     */
    public AIResponse chatStreamWithHandlerOnly(final AIRequest request) {
        final String sessionId = request.getSessionId();
        final long startedAt = System.nanoTime();
        final StreamingChatModel model = LLMConvertors.toChatModel(llmConfig);
        SystemMessage systemMessage = SystemMessage.from(GitAssistance.SYSTEM_PROMPT);
        UserMessage userMessage = UserMessage.from(request.getPrompt());

        // 3. Define Custom Chat Parameters (Temperature, Max Tokens, etc.)
        ChatRequestParameters parameters = ChatRequestParameters.builder()
                .temperature(0.2)           // Deterministic output
                .maxOutputTokens(100)        // Token limit
                .build();

        // 4. Combine Messages and Parameters into a ChatRequest
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(systemMessage, userMessage)
                .parameters(parameters)
                .build();

        final CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
        model.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                log.info("Ankush :: "+completeResponse.toString());
                futureResponse.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                log.error("Streaming chat failed for session {}", sessionId, error);
                futureResponse.completeExceptionally(error);
            }
        });
        final ChatResponse response = futureResponse.join();
        final String responseText = response.aiMessage() == null ? null : response.aiMessage().text();
        return LLMConvertors.toAIResponse(response, response.tokenUsage(), responseText, null,
                llmConfig.getModelName(), sessionId, System.nanoTime() - startedAt);
    }

    /**
     * Streaming with AI service in LangChain4J
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




    private GitAssistance getAssistant() {
        final StreamingChatModel model = LLMConvertors.toChatModel(llmConfig);

        final AiServices<GitAssistance> builder = AiServices.builder(GitAssistance.class)
                .streamingChatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10));

        builder.toolProvider(mcpClient.toolProvider())
                .maxToolCallingRoundTrips(llmConfig.getMaxToolCallingRoundTrips());
        return builder.build();
    }


    private GitStreamingAssistance getGitStreamingAssistant() {
        final StreamingChatModel model = LLMConvertors.toChatModel(llmConfig);

        final AiServices<GitStreamingAssistance> builder = AiServices.builder(GitStreamingAssistance.class)
                .streamingChatModel(model);
        builder.toolProvider(mcpClient.toolProvider())
                .maxToolCallingRoundTrips(llmConfig.getMaxToolCallingRoundTrips());
        return builder.build();
    }

}
