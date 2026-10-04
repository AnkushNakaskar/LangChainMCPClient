package com.langchain.central.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM connection settings, driven by the {@code llm} block in application.yml.
 *
 * @author ankush.nakaskar
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMConfig {

    /** LOCAL (Ollama / llama.cpp) or REMOTE (hosted provider). */
    @NotNull
    @JsonProperty("type")
    @Builder.Default
    private LlmType type = LlmType.LOCAL;

    /** OpenAI-compatible base url, e.g. http://localhost:11434/v1 for Ollama. */
    @NotBlank
    @JsonProperty("baseUrl")
    private String baseUrl;

    /** Model identifier as known to the server, e.g. the GGUF tag pulled into Ollama. */
    @NotBlank
    @JsonProperty("modelName")
    private String modelName;

    /** Ignored by local servers, but the OpenAI client refuses to build without a value. */
    @JsonProperty("apiKey")
    private String apiKey;

    @JsonProperty("temperature")
    @Builder.Default
    private Double temperature = 0.0;

    @Positive
    @JsonProperty("timeoutSeconds")
    @Builder.Default
    private int timeoutSeconds = 120;

    /**
     * How many messages of history are replayed to the model per conversation.
     *
     * <p>History is dropped a whole turn at a time, so a small window costs older context but
     * never leaves a tool result without the question it belongs to. A turn that is still being
     * answered is kept whole even when it is larger than this value.
     */
    @Positive
    @JsonProperty("maxMemoryMessages")
    @Builder.Default
    private int maxMemoryMessages = 50;

    /**
     * How many times the model may call tools within one answer. A small model that has its calls
     * refused will otherwise keep calling them, and langchain4j allows a hundred rounds by default.
     */
    @Positive
    @JsonProperty("maxToolCallingRoundTrips")
    @Builder.Default
    private int maxToolCallingRoundTrips = 5;

    /**
     * Upper bound on the tokens the model may produce for one answer.
     *
     * <p>Left unset the server decides, and a server with a small default truncates long answers.
     * A truncated answer comes back with {@code doneReason=length} and, when the truncation hits
     * immediately after a tool call, with no text at all.
     */
    @JsonProperty("maxTokens")
    private Integer maxTokens;

    /**
     * How much of the token budget a reasoning model may spend on thinking before it answers.
     *
     * <p>A thinking model writes its reasoning into a separate field and only then starts the
     * answer. When the budget runs out during the thinking the reply carries no answer at all,
     * which is what happens with a long tool result. {@code none} turns thinking off and is the
     * right setting here, because the tool has already done the work the model would reason about.
     */
    @JsonProperty("reasoningEffort")
    private String reasoningEffort;

    /**
     * Extra fields merged into the chat completion request body, for settings the OpenAI schema
     * does not cover.
     *
     * <p>Only fields the server actually reads have an effect. Ollama in particular ignores
     * {@code options} here, so its context window cannot be set from this client; see the note
     * on {@code llm.modelName} in application.yml.
     */
    @JsonProperty("customParameters")
    private Map<String, Object> customParameters;

    @JsonProperty("logRequests")
    @Builder.Default
    private boolean logRequests = false;

    @JsonProperty("logResponses")
    @Builder.Default
    private boolean logResponses = false;


}
