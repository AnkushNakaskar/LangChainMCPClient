package com.langchain.central.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 *
 * @author ankush.nakaskar
 */
public enum StreamEventType {

    /** First event of a stream, carrying the session and the model that will answer. */
    START,

    /** A slice of the answer as the model produces it. */
    TOKEN,

    /** A slice of the model's reasoning, only sent by a thinking model that returns it. */
    THINKING,

    /** The model asked for a tool; the arguments are known but the tool has not run yet. */
    TOOL_CALL,

    /** A tool finished and its result went back to the model. */
    TOOL_RESULT,

    /** Progress reported by the MCP server itself, such as a tool call round trip or a log line. */
    MCP,

    /** Last event of a successful stream, carrying the complete {@link AIResponse}. */
    DONE,

    /** Last event of a failed stream. */
    ERROR;

    @JsonValue
    public String eventName() {
        return name().toLowerCase();
    }

    /** True for the two events after which no further event is sent. */
    public boolean isTerminal() {
        return this == DONE || this == ERROR;
    }
}
