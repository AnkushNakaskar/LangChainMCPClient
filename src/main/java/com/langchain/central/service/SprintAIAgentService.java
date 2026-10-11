package com.langchain.central.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.agents.SprintAIAgent;
import com.langchain.central.config.JiraMcpClientConfig;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.mcp_clients.ManagedJiraMcpClient;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.model.ToolResponse;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Answers sprint questions with {@link SprintAIAgent}, which reads the active sprint through the
 * Jira MCP tools.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class SprintAIAgentService {

    private final LLMConfig llmConfig;
    private final JiraMcpClientConfig jiraConfig;
    private final ManagedJiraMcpClient jiraMcpClient;

    @Inject
    public SprintAIAgentService(final LLMConfig llmConfig,
                                final JiraMcpClientConfig jiraConfig,
                                final ManagedJiraMcpClient jiraMcpClient) {
        this.llmConfig = llmConfig;
        this.jiraConfig = jiraConfig;
        this.jiraMcpClient = jiraMcpClient;
    }

    public AIResponse chatStream(final AIRequest request) {
        final String sessionId = request.getSessionId();
        final long startedAt = System.nanoTime();
        final List<ToolResponse> tools = new CopyOnWriteArrayList<>();

        final TokenStream stream = sprintAgent()
                .getSprintDetails(jiraConfig.getProjectKey(), request.getPrompt());
        final CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
        final AtomicInteger answerChars = new AtomicInteger();
        log.info("[sprint:{}] Asking the model about project {}: {}",
                sessionId, jiraConfig.getProjectKey(), request.getPrompt());
        stream.beforeToolExecution(before -> log.info("[sprint:{}] {}s Calling Jira tool {} with {}",
                        sessionId, secondsSince(startedAt), before.request().name(), before.request().arguments()))
                .onToolExecuted(execution -> {
                    tools.add(LLMConvertors.toToolResponse(execution));
                    log.info("[sprint:{}] {}s Jira tool {} returned {} characters{}",
                            sessionId, secondsSince(startedAt), execution.request().name(),
                            execution.result() == null ? 0 : execution.result().length(),
                            execution.hasFailed() ? " (FAILED)" : "");
                })
                .onIntermediateResponse(intermediate -> log.info(
                        "[sprint:{}] {}s Model asked for more tool calls, prompt is now {} tokens",
                        sessionId, secondsSince(startedAt),
                        intermediate.tokenUsage() == null ? "?" : intermediate.tokenUsage().inputTokenCount()))
                .onPartialResponse(partial -> {
                    if (answerChars.getAndAdd(partial.length()) == 0) {
                        log.info("[sprint:{}] {}s Model started writing the answer",
                                sessionId, secondsSince(startedAt));
                    }
                })
                .onCompleteResponse(response -> {
                    log.info("[sprint:{}] {}s Answer complete: {} characters, {} tokens in total",
                            sessionId, secondsSince(startedAt), answerChars.get(),
                            response.tokenUsage() == null ? "?" : response.tokenUsage().totalTokenCount());
                    futureResponse.complete(response);
                })
                .onError(error -> {
                    log.error("[sprint:{}] {}s Failed: {}", sessionId, secondsSince(startedAt), error.getMessage());
                    futureResponse.completeExceptionally(error);
                })
                .start();

        final ChatResponse response = futureResponse.join();
        final String streamedText = response.aiMessage() == null ? null : response.aiMessage().text();
        return LLMConvertors.toAIResponse(response, response.tokenUsage(), streamedText, tools,
                llmConfig.getModelName(), sessionId, System.nanoTime() - startedAt);
    }

    private static long secondsSince(final long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000_000L;
    }

    private SprintAIAgent sprintAgent() {
        final StreamingChatModel model = LLMConvertors.toChatModel(llmConfig);
        return AiServices.builder(SprintAIAgent.class)
                .streamingChatModel(model)
                .toolProvider(jiraMcpClient.toolProvider())
                .maxToolCallingRoundTrips(llmConfig.getMaxToolCallingRoundTrips())
                .build();
    }
}
