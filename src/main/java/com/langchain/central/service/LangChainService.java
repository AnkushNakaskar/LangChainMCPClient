package com.langchain.central.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.assistance.AssistanceType;
import com.langchain.central.assistance.GitAssistance;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.mcp.ManagedMcpClient;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, GitAssistance> assistants = new ConcurrentHashMap<>();

    @Inject
    public LangChainService(final LLMConfig llmConfig, final ManagedMcpClient mcpClient) {
        this.llmConfig = llmConfig;
        this.mcpClient = mcpClient;
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

    /** Assistants are cached per model and tool combination; see the class comment. */
    private GitAssistance assistantFor(final String modelName, final boolean useTools) {
        return assistants.computeIfAbsent(modelName + "|tools=" + useTools,
                ignored -> build(modelName, useTools));
    }

    private GitAssistance build(final String modelName, final boolean useTools) {
        final ChatModel model = LLMConvertors.toChatModel(llmConfig, modelName);

        final AiServices<GitAssistance> builder = AiServices.builder(GitAssistance.class)
                .chatModel(model)
                // one memory per sessionId, so concurrent conversations do not read each other
                .chatMemoryProvider(sessionId ->
                        MessageWindowChatMemory.withMaxMessages(llmConfig.getMaxMemoryMessages()));

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
