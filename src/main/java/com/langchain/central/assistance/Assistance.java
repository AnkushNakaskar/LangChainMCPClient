package com.langchain.central.assistance;

import dev.langchain4j.service.Result;

/**
 * Contract every AI assistant in this application exposes.
 *
 * <p>Implementations are not written by hand: langchain4j generates a proxy from the interface
 * via {@code AiServices.builder(...)}. Sub-interfaces declare the langchain4j annotations that
 * shape the prompt.
 *
 * @author ankush.nakaskar
 */
public interface Assistance {

    /**
     * Sends a user message to the model.
     *
     * @param sessionId   conversation key, decides which chat memory is used
     * @param userMessage the prompt
     * @return the answer plus token usage, finish reason and any tool calls made
     */
    Result<String> chat(String sessionId, String userMessage);
}
