package com.langchain.central.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.langchain.central.assistance.AssistanceType;
import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chat request, shaped after the Ollama {@code /api/generate} payload.
 *
 * <pre>
 * {
 *   "model": "hf.co/unsloth/Llama-3.2-1B-Instruct-GGUF:UD-Q4_K_XL",
 *   "prompt": "Why is local AI better for privacy?",
 *   "stream": false
 * }
 * </pre>
 *
 * @author ankush.nakaskar
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AIRequest {

    public static final String DEFAULT_SESSION_ID = "default";
    public static final AssistanceType DEFAULT_ASSISTANT = AssistanceType.GIT;

    /** The user message sent to the model. */
    @NotBlank
    private String prompt;


    /**
     * Conversation key. Requests sharing a sessionId share chat memory;
     * defaults to a single shared conversation when omitted.
     */
    @Builder.Default
    private String sessionId = DEFAULT_SESSION_ID;

    /** Assistant identifier. This Git-only client defaults to GIT. */
    @Builder.Default
    private AssistanceType assistant = DEFAULT_ASSISTANT;

    /**
     * Accepted for payload compatibility. Streaming is chosen by the endpoint, not by this flag:
     * {@code POST /langchain/chat} always answers in one piece and
     * {@code POST /langchain/chat/stream} always answers as Server-Sent Events.
     */
    @Builder.Default
    private boolean stream = false;

    /**
     * Whether the assistant's tools are offered to the model. Small models tend to call tools even
     * for unrelated prompts, so set this to false for general questions.
     *
     * <p>{@code useTool} is accepted as an alias, because the singular form reads naturally and was
     * otherwise silently ignored, leaving tools enabled when the caller meant to switch them off.
     */
    @Builder.Default
    @JsonAlias("useTool")
    private boolean useTools = true;

    /**
     * Normalised here rather than by the caller, so an explicit {@code null} or a blank string in
     * the payload falls back to the default just like an omitted field does.
     */
    public String getSessionId() {
        return (sessionId == null || sessionId.isBlank()) ? DEFAULT_SESSION_ID : sessionId;
    }

    public AssistanceType getAssistant() {
        return assistant == null ? DEFAULT_ASSISTANT : assistant;
    }
}
