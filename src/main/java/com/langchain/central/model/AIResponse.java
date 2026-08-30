package com.langchain.central.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chat response, shaped after the Ollama {@code /api/generate} response.
 *
 * <pre>
 * {
 *   "model": "...",
 *   "created_at": "2026-08-22T11:14:45.223373Z",
 *   "response": "Local AI, also known as edge AI, ...",
 *   "done": true,
 *   "done_reason": "stop",
 *   "total_duration": 3726356083,
 *   "prompt_eval_count": 18,
 *   "eval_count": 506
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
public class AIResponse {

    /** Model that produced the answer. */
    private String model;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    /** The generated answer. */
    private String response;

    /** Conversation this answer belongs to. */
    private String sessionId;

    @Builder.Default
    private boolean done = true;

    /** Why generation stopped, e.g. {@code stop} or {@code length}. */
    private String doneReason;

    /** End-to-end server side duration in nanoseconds, matching Ollama's total_duration. */
    private Long totalDuration;

    /** Tokens consumed by the prompt. */
    private Integer promptEvalCount;

    /** Tokens produced by the model. */
    private Integer evalCount;

    private Integer totalTokenCount;

    /** Tools the model decided to call while answering, if any. */
    private List<ToolResponse> tools;

    /** Populated instead of {@link #response} when generation failed. */
    private String error;
}
