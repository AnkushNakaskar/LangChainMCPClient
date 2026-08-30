package com.langchain.central.util;

import com.langchain.central.config.LLMConfig;
import com.langchain.central.model.AIResponse;
import com.langchain.central.model.ToolResponse;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Conversions between langchain4j types and this application's API/config types.
 *
 * @author ankush.nakaskar
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LLMConvertors {

    /**
     * Builds an OpenAI-compatible chat model. The same client works for a local Ollama /
     * llama.cpp server and for a hosted provider, only the config differs.
     *
     * @param llmConfig  connection settings from application.yml
     * @param modelName  model to talk to, may override {@link LLMConfig#getModelName()}
     */
    public static ChatModel toChatModel(final LLMConfig llmConfig, final String modelName) {
        final String resolvedModelName =
                (modelName == null || modelName.isBlank()) ? llmConfig.getModelName() : modelName;

        return OpenAiChatModel.builder()
                .baseUrl(llmConfig.getBaseUrl())
                .modelName(resolvedModelName)
                .apiKey(llmConfig.resolveApiKey())
                .temperature(llmConfig.getTemperature())
                .timeout(Duration.ofSeconds(llmConfig.getTimeoutSeconds()))
                .logRequests(llmConfig.isLogRequests())
                .logResponses(llmConfig.isLogResponses())
                .build();
    }

    /**
     * Maps a langchain4j result onto the Ollama-shaped {@link AIResponse}.
     *
     * @param result        outcome of the AiService call
     * @param modelName     model that produced the answer
     * @param sessionId     conversation the answer belongs to
     * @param elapsedNanos  server side duration, reported as {@code totalDuration}
     */
    public static AIResponse toAIResponse(final Result<String> result,
                                          final String modelName,
                                          final String sessionId,
                                          final long elapsedNanos) {
        final TokenUsage usage = result.tokenUsage();
        final FinishReason finishReason = result.finishReason();

        return AIResponse.builder()
                .model(modelName)
                .createdAt(Instant.now())
                .response(result.content())
                .sessionId(sessionId)
                .done(true)
                .doneReason(finishReason == null ? null : finishReason.name().toLowerCase())
                .totalDuration(elapsedNanos)
                .promptEvalCount(usage == null ? null : usage.inputTokenCount())
                .evalCount(usage == null ? null : usage.outputTokenCount())
                .totalTokenCount(usage == null ? null : usage.totalTokenCount())
                .tools(toToolResponses(result))
                .build();
    }

    /**
     * Maps the tool calls the model made onto {@link ToolResponse}, or {@code null} when the
     * model answered without touching any tool.
     */
    public static List<ToolResponse> toToolResponses(final Result<String> result) {
        final List<ToolExecution> executions = result.toolExecutions();
        if (executions == null || executions.isEmpty()) {
            return null;
        }
        return executions.stream()
                .map(LLMConvertors::toToolResponse)
                .collect(Collectors.toList());
    }

    public static ToolResponse toToolResponse(final ToolExecution execution) {
        return ToolResponse.builder()
                .name(execution.request().name())
                .arguments(execution.request().arguments())
                .result(execution.result())
                .build();
    }
}
