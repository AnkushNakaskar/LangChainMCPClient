package com.langchain.central.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 * @author ankush.nakaskar
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamEvent {

    private StreamEventType type;

    /** Conversation the frame belongs to. */
    private String sessionId;

    /** Model producing the answer, sent on {@link StreamEventType#START}. */
    private String model;

    /** Text slice for {@code token} and {@code thinking}, or a message for {@code mcp}. */
    private String content;

    /** Tool being called or the tool that just finished. */
    private ToolResponse tool;

    /** The complete answer, sent once on {@link StreamEventType#DONE}. */
    private AIResponse response;

    /** Failure message, sent on {@link StreamEventType#ERROR}. */
    private String error;

    @Builder.Default
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt = Instant.now();

    public static StreamEvent of(final StreamEventType type, final String sessionId) {
        return StreamEvent.builder().type(type).sessionId(sessionId).build();
    }
}
