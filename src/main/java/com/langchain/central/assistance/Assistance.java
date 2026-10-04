package com.langchain.central.assistance;

import dev.langchain4j.service.Result;

/**
 * @author ankush.nakaskar
 */
public interface Assistance {

    Result<String> chat(String sessionId, String userMessage);
}
