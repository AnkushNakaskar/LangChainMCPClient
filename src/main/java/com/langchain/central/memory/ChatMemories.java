package com.langchain.central.memory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.config.LLMConfig;
import dev.langchain4j.memory.ChatMemory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one chat memory per session, shared by every code path that talks to the model.
 *
 * <p>The blocking endpoint lets langchain4j's {@code AiServices} drive the conversation while the
 * streaming endpoint drives it by hand, and both must append to the same history: a question asked
 * over one endpoint has to be visible to the other, and two registries would silently fork the
 * conversation in two.
 *
 * @author ankush.nakaskar
 */
@Singleton
public class ChatMemories {

    private final int maxMessages;
    private final Map<String, ChatMemory> memories = new ConcurrentHashMap<>();

    @Inject
    public ChatMemories(final LLMConfig llmConfig) {
        this.maxMessages = llmConfig.getMaxMemoryMessages();
    }

    /** The memory of {@code sessionId}, created on first use. */
    public ChatMemory of(final Object sessionId) {
        return memories.computeIfAbsent(String.valueOf(sessionId),
                id -> TurnWindowChatMemory.withMaxMessages(id, maxMessages));
    }
}
