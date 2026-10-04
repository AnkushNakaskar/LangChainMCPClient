package com.langchain.central.util;

import com.langchain.central.config.LLMConfig;
import com.langchain.central.model.AIResponse;
import com.langchain.central.model.ToolResponse;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author ankush.nakaskar
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LLMConvertors {


    public static StreamingChatModel toChatModel(final LLMConfig llmConfig) {
        OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(llmConfig.getBaseUrl())
                .modelName(llmConfig.getModelName())
                .apiKey(llmConfig.getApiKey())
                .temperature(llmConfig.getTemperature())
                .timeout(Duration.ofSeconds(llmConfig.getTimeoutSeconds()))
                .logRequests(llmConfig.isLogRequests())
                .logResponses(llmConfig.isLogResponses());

        if (llmConfig.getMaxTokens() != null) {
            builder.maxTokens(llmConfig.getMaxTokens());
        }
        if (llmConfig.getReasoningEffort() != null && !llmConfig.getReasoningEffort().isBlank()) {
            builder.reasoningEffort(llmConfig.getReasoningEffort());
        }
        final Map<String, Object> customParameters = llmConfig.getCustomParameters();
        if (customParameters != null && !customParameters.isEmpty()) {
            builder.customParameters(customParameters);
        }
        return builder.build();
    }

    /**
     * The streaming counterpart of {@link #toChatModel}, configured from the same settings so that
     * the streaming and the blocking endpoint answer alike.
     *
     * <p>This is a separate client rather than a mode of the other one: the OpenAI-compatible API
     * streams only when the request asks for it, and langchain4j models that in two types.
     */
    public static StreamingChatModel toStreamingChatModel(final LLMConfig llmConfig) {
        final OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder =
                OpenAiStreamingChatModel.builder()
                        .baseUrl(llmConfig.getBaseUrl())
                        .modelName(llmConfig.getModelName())
                        .apiKey(llmConfig.getApiKey())
                        .temperature(llmConfig.getTemperature())
                        .timeout(Duration.ofSeconds(llmConfig.getTimeoutSeconds()))
                        .logRequests(llmConfig.isLogRequests())
                        .logResponses(llmConfig.isLogResponses());

        if (llmConfig.getMaxTokens() != null) {
            builder.maxTokens(llmConfig.getMaxTokens());
        }
        if (llmConfig.getReasoningEffort() != null && !llmConfig.getReasoningEffort().isBlank()) {
            builder.reasoningEffort(llmConfig.getReasoningEffort());
        }
        final Map<String, Object> customParameters = llmConfig.getCustomParameters();
        if (customParameters != null && !customParameters.isEmpty()) {
            builder.customParameters(customParameters);
        }
        return builder.build();
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
        final List<ToolResponse> tools = toToolResponses(result);

        return AIResponse.builder()
                .model(modelName)
                .createdAt(Instant.now())
                .response(resolveAnswer(result.content(), tools, finishReason))
                .sessionId(sessionId)
                .done(true)
                .doneReason(finishReason == null ? null : finishReason.name().toLowerCase())
                .totalDuration(elapsedNanos)
                .promptEvalCount(usage == null ? null : usage.inputTokenCount())
                .evalCount(usage == null ? null : usage.outputTokenCount())
                .totalTokenCount(usage == null ? null : usage.totalTokenCount())
                .tools(tools)
                .build();
    }

    /**
     * Maps the final event of a streamed answer onto the same {@link AIResponse} the blocking
     * endpoint returns, so a caller that followed the stream still ends up with one complete,
     * identically shaped answer.
     *
     * @param chatResponse  final response of the stream
     * @param totalUsage    usage of every request the answer took, which is more than the final
     *                      response reports as soon as the model called a tool
     * @param streamedText  everything that was emitted as tokens, used when the final response
     *                      carries no text of its own
     * @param tools         tools called while answering, collected from the stream
     */
    public static AIResponse toAIResponse(final ChatResponse chatResponse,
                                          final TokenUsage totalUsage,
                                          final String streamedText,
                                          final List<ToolResponse> tools,
                                          final String modelName,
                                          final String sessionId,
                                          final long elapsedNanos) {
        final TokenUsage usage = totalUsage != null
                ? totalUsage
                : (chatResponse == null ? null : chatResponse.tokenUsage());
        final FinishReason finishReason = chatResponse == null ? null : chatResponse.finishReason();
        final String finalText = chatResponse == null || chatResponse.aiMessage() == null
                ? null
                : chatResponse.aiMessage().text();
        final String answer = (finalText == null || finalText.isBlank()) ? streamedText : finalText;
        final List<ToolResponse> calledTools = (tools == null || tools.isEmpty()) ? null : tools;

        return AIResponse.builder()
                .model(modelName)
                .createdAt(Instant.now())
                .response(resolveAnswer(answer, calledTools, finishReason))
                .sessionId(sessionId)
                .done(true)
                .doneReason(finishReason == null ? null : finishReason.name().toLowerCase())
                .totalDuration(elapsedNanos)
                .promptEvalCount(usage == null ? null : usage.inputTokenCount())
                .evalCount(usage == null ? null : usage.outputTokenCount())
                .totalTokenCount(usage == null ? null : usage.totalTokenCount())
                .tools(calledTools)
                .build();
    }

    /**
     * The model can stop before writing a single character, typically when the prompt plus the
     * tool results already fill its context and generation ends with {@code LENGTH}. The caller
     * would then receive nothing but tool-call JSON, so the tool results are rendered instead.
     */
    private static String resolveAnswer(final String content,
                                        final List<ToolResponse> tools,
                                        final FinishReason finishReason) {
        if (content != null && !content.isBlank()) {
            return content;
        }
        if (tools == null || tools.isEmpty()) {
            return content;
        }
        log.warn("Model produced no text after {} tool call(s), finish reason {}; "
                        + "falling back to the raw tool results. Raise llm.maxTokens or the "
                        + "server context window to let the model answer.",
                tools.size(), finishReason);
        return ToolResultRenderer.render(tools);
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
