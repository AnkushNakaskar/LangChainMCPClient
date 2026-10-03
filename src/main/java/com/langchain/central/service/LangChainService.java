package com.langchain.central.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.assistance.AssistanceType;
import com.langchain.central.assistance.GitAssistance;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.mcp.ManagedMcpClient;
import com.langchain.central.mcp.McpStreamEvents;
import com.langchain.central.memory.TurnWindowChatMemory;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.model.StreamEvent;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves chat requests: it owns the assistants, sends the prompt to the configured LLM and maps
 * the outcome onto the API model.
 *
 * <p>An assistant is built once per model and tool combination and then reused, because each one
 * carries an HTTP client and the chat memory of every session it has served. Building one per
 * request would drop the conversation and open a new client each time.
 *
 * <p>The tools are handed to langchain4j as plain annotated objects. It reads their {@code @Tool}
 * methods, offers them to the model and executes the ones the model calls, so nothing in this
 * application has to sit between the model and a tool.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class LangChainService {

    private final LLMConfig llmConfig;
    private final ManagedMcpClient mcpClient;
    private final McpStreamEvents mcpStreamEvents;
    private final Map<String, GitAssistance> assistants = new ConcurrentHashMap<>();

    @Inject
    public LangChainService(final LLMConfig llmConfig,
                            final ManagedMcpClient mcpClient,
                            final McpStreamEvents mcpStreamEvents) {
        this.llmConfig = llmConfig;
        this.mcpClient = mcpClient;
        this.mcpStreamEvents = mcpStreamEvents;
        log.info("LLM configured as {} model {} at {}",
                llmConfig.getType(), llmConfig.getModelName(), llmConfig.getBaseUrl());
    }



    /**
     * Sends the prompt to the model and maps the outcome, including any tool the model called,
     * onto {@link AIResponse}.
     */
    public AIResponse chat(final AIRequest request) {
        final AssistanceType assistanceType = request.getAssistant();
        final String modelName = request.getModelOrDefault(llmConfig.getModelName());
        final String sessionId = request.getSessionId();
        final long startedAt = System.nanoTime();

        log.info("Chat request on session {} for assistant {} using model {}, tools {}",
                sessionId, assistanceType, modelName,
                request.isUseTools() ? "enabled" : "disabled");

        final GitAssistance assistant = assistantFor(modelName, request.isUseTools());
        final Result<String> result = assistant.chat(sessionId, request.getPrompt());

        return LLMConvertors.toAIResponse(
                result, modelName, sessionId, System.nanoTime() - startedAt);
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
     * @param sink    receives every event of this answer
     */
    public void chatStream(final AIRequest request, final Consumer<StreamEvent> sink) {
        final String modelName = request.getModelOrDefault(llmConfig.getModelName());
        final String sessionId = request.getSessionId();

        log.info("Streaming chat request on session {} for assistant {} using model {}, tools {}",
                sessionId, request.getAssistant(), modelName,
                request.isUseTools() ? "enabled" : "disabled");

        // the MCP client reports tool round trips globally, so the session has to be announced
        // before the model can call a tool and withdrawn once the answer is complete
        final Consumer<StreamEvent> trackedSink = event -> {
            if (event.getType().isTerminal()) {
                mcpStreamEvents.unregister(sessionId);
            }
            sink.accept(event);
        };
        mcpStreamEvents.register(sessionId, trackedSink);

        final ChatStreamSession streamSession =
                new ChatStreamSession(sessionId, modelName, trackedSink);
        try {
            final GitAssistance assistant = assistantFor(modelName, request.isUseTools());
            streamSession.start(assistant.chatStream(sessionId, request.getPrompt()));
        } catch (Exception e) {
            // building the assistant or handing over the prompt failed, so no callback will ever
            // fire and the caller would wait for an event that cannot arrive
            streamSession.fail(e);
        }
    }

    /** Assistants are cached per model and tool combination; see the class comment. */
    private GitAssistance assistantFor(final String modelName, final boolean useTools) {
        return assistants.computeIfAbsent(modelName + "|tools=" + useTools,
                ignored -> build(modelName, useTools));
    }

    private GitAssistance build(final String modelName, final boolean useTools) {
        final ChatModel model = LLMConvertors.toChatModel(llmConfig, modelName);
        final StreamingChatModel streamingModel =
                LLMConvertors.toStreamingChatModel(llmConfig, modelName);

        final AiServices<GitAssistance> builder = AiServices.builder(GitAssistance.class)
                .chatModel(model)
                // the assistant declares both a blocking and a streaming method, and langchain4j
                // picks the model that matches the method being called
                .streamingChatModel(streamingModel)
                // one memory per sessionId, so concurrent conversations do not read each other
                .chatMemoryProvider(sessionId -> TurnWindowChatMemory.withMaxMessages(
                        sessionId, llmConfig.getMaxMemoryMessages()));

        if (useTools && mcpClient.isEnabled()) {
            builder.toolProvider(mcpClient.toolProvider())
                    // a small model that is unhappy with a tool result will otherwise keep calling
                    // tools; langchain4j allows a hundred rounds by default
                    .maxToolCallingRoundTrips(llmConfig.getMaxToolCallingRoundTrips());
        }
        log.info("Built Git assistant for model {} with MCP tools {}", modelName,
                useTools && mcpClient.isEnabled());
        return builder.build();
    }
}
