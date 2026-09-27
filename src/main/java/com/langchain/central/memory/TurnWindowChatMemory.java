package com.langchain.central.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Chat memory that drops history one whole turn at a time.
 *
 * <p>A turn is a user message together with every assistant and tool message produced while
 * answering it. A plain sliding window counts messages instead of turns, so once the window is
 * full it evicts the user's own question and keeps the assistant and tool messages that answered
 * it. The history then starts at a tool call that belongs to no visible question, which chat
 * templates reject outright: Qwen raises {@code No user query found in messages} and the request
 * fails with a 500 in the middle of an answer.
 *
 * <p>Tool calling makes that easy to hit, because a single answer appends two messages per round
 * trip. This memory therefore evicts from the oldest turn boundary and never evicts the turn that
 * is currently being answered, so the history handed to the model always begins with a user
 * message and every tool result still has the question and the tool call it belongs to. Honouring
 * the turn boundary can leave the history slightly above {@code maxMessages}; that is deliberate,
 * as an oversized history is harmless whereas an incoherent one is not.
 *
 * @author ankush.nakaskar
 */
@Slf4j
public class TurnWindowChatMemory implements ChatMemory {

    private final Object id;
    private final int maxMessages;
    private final List<ChatMessage> messages = new ArrayList<>();
    private SystemMessage systemMessage;

    public TurnWindowChatMemory(final Object id, final int maxMessages) {
        if (maxMessages < 1) {
            throw new IllegalArgumentException("maxMessages must be at least 1");
        }
        this.id = id;
        this.maxMessages = maxMessages;
    }

    public static TurnWindowChatMemory withMaxMessages(final Object id, final int maxMessages) {
        return new TurnWindowChatMemory(id, maxMessages);
    }

    @Override
    public Object id() {
        return id;
    }

    /**
     * The system message is held apart from the history, so that it is never counted against the
     * window and never evicted. A later system message replaces the previous one rather than being
     * appended, because a template that requires the system message to come first rejects a second
     * one.
     */
    @Override
    public synchronized void add(final ChatMessage message) {
        if (message instanceof SystemMessage incoming) {
            systemMessage = incoming;
            return;
        }
        messages.add(message);
        evictOldestTurns();
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        final List<ChatMessage> history = new ArrayList<>(messages.size() + 1);
        if (systemMessage != null) {
            history.add(systemMessage);
        }
        history.addAll(messages);
        return Collections.unmodifiableList(history);
    }

    @Override
    public synchronized void clear() {
        messages.clear();
        systemMessage = null;
    }

    /**
     * Removes complete turns from the front while the history is too long. The turn currently
     * being answered is the last one and is never removed, so eviction stops as soon as only that
     * turn is left.
     */
    private void evictOldestTurns() {
        while (messages.size() > maxMessages) {
            final int nextTurn = indexOfTurnAfterFirst();
            if (nextTurn < 0) {
                log.debug("Chat memory {} holds a single turn of {} messages, above the window of "
                                + "{}; keeping it so the history stays coherent",
                        id, messages.size(), maxMessages);
                return;
            }
            messages.subList(0, nextTurn).clear();
        }
    }

    /**
     * @return index of the user message that starts the second turn, or -1 when the history holds
     *         at most one turn and nothing may be evicted
     */
    private int indexOfTurnAfterFirst() {
        boolean seenFirstTurn = false;
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessage) {
                if (seenFirstTurn) {
                    return index;
                }
                seenFirstTurn = true;
            }
        }
        return -1;
    }
}
