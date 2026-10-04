package com.langchain.central.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.assistance.AssistanceType;
import com.langchain.central.assistance.GitAssistance;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.mcp.ManagedMcpClient;
import com.langchain.central.mcp.McpStreamEvents;
import com.langchain.central.memory.ChatMemories;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.model.StreamEvent;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves chat requests: it owns the assistants, sends the prompt to the configured LLM and maps
 * the outcome onto the API model.
 *
 * <p>The blocking endpoint hands the conversation to a langchain4j {@code AiServices} proxy. The
 * streaming endpoint instead drives a {@link StreamingChatModel} directly through
 * {@link StreamingChatSession}, which is why the memory, the system prompt and the tool-calling
 * loop are explicit there. Both paths share one {@link ChatMemories} registry, so a session's
 * history is the same whichever endpoint it was built up over.
 *
 * <p>An assistant and a streaming model are built once per model and tool combination and then
 * reused, because each one carries an HTTP client. Building one per request would open a new
 * client each time.
 *
 * <p>The MCP tools are discovered through a langchain4j tool provider. On the blocking path
 * langchain4j offers them to the model and executes the ones it calls; on the streaming path
 * {@link StreamingChatSession} does the same by hand.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class LangChainService {

    private final LLMConfig llmConfig;
    private final ManagedMcpClient mcpClient;
    private final McpStreamEvents mcpStreamEvents;
    private final ChatMemories chatMemories;
    private final Map<String, GitAssistance> assistants = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingModels = new ConcurrentHashMap<>();

    @Inject
    public LangChainService(final LLMConfig llmConfig,
                            final ManagedMcpClient mcpClient,
                            final McpStreamEvents mcpStreamEvents,
                            final ChatMemories chatMemories) {
        this.llmConfig = llmConfig;
        this.mcpClient = mcpClient;
        this.mcpStreamEvents = mcpStreamEvents;
        this.chatMemories = chatMemories;
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
        final boolean useTools = request.isUseTools() && mcpClient.isEnabled();

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

        final StreamingChatSession streamSession = new StreamingChatSession(
                sessionId,
                modelName,
                streamingModelFor(modelName),
                chatMemories.of(sessionId),
                useTools ? toolsFor(sessionId, request.getPrompt()) : null,
                llmConfig.getMaxToolCallingRoundTrips(),
                trackedSink);
        try {
            streamSession.start(GitAssistance.SYSTEM_PROMPT, request.getPrompt());
        } catch (Exception e) {
            // handing the prompt over failed, so no callback will ever fire and the caller would
            // wait for an event that cannot arrive
            streamSession.fail(e);
        }
    }

    /**
     * Asks the MCP server which tools it offers for this prompt, together with the executor that
     * runs each of them.
     *
     * <p>Resolved per request rather than cached, because the provider is free to offer a
     * different set per conversation; the MCP client caches the tool list underneath.
     */
    private ToolProviderResult toolsFor(final String sessionId, final String prompt) {
        return mcpClient.toolProvider().provideTools(ToolProviderRequest.builder()
                .userMessage(UserMessage.from(prompt))
                .invocationContext(InvocationContext.builder()
                        .chatMemoryId(sessionId)
                        .build())
                .build());
    }

    /** Streaming clients are cached per model, for the reason given in the class comment. */
    private StreamingChatModel streamingModelFor(final String modelName) {
        return streamingModels.computeIfAbsent(modelName,
                name -> LLMConvertors.toStreamingChatModel(llmConfig, name));
    }

    /** Assistants are cached per model and tool combination; see the class comment. */
    private GitAssistance assistantFor(final String modelName, final boolean useTools) {
        return assistants.computeIfAbsent(modelName + "|tools=" + useTools,
                ignored -> build(modelName, useTools));
    }

    private GitAssistance build(final String modelName, final boolean useTools) {
        final StreamingChatModel model = LLMConvertors.toChatModel(llmConfig, modelName);

        final AiServices<GitAssistance> builder = AiServices.builder(GitAssistance.class)
                .streamingChatModel(model)
                .chatMemoryProvider(chatMemories::of);

        if (useTools && mcpClient.isEnabled()) {
            builder.toolProvider(mcpClient.toolProvider())
                    .maxToolCallingRoundTrips(llmConfig.getMaxToolCallingRoundTrips());
        }
        log.info("Built Git assistant for model {} with MCP tools {}", modelName, useTools && mcpClient.isEnabled());
        return builder.build();
    }
}
