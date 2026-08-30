package com.langchain.central.assistance;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Assistant backed by the movie tools.
 *
 * <p>The system message is part of the contract with the model, not decoration. The rules about
 * arguments and about prose are there because a 1B model, shown four tools at once, otherwise
 * echoes the argument schema back as the arguments
 * ({@code {"type":"object","properties":{"movieTitle":"Inception"}}}), which binds
 * {@code movieTitle} to null, and answers in JSON instead of in words.
 *
 * @author ankush.nakaskar
 */
public interface MovieAssistance extends Assistance {

    @Override
    @SystemMessage("""
            You are a helpful assistant.
            You also have access to tools backed by a small movie database.
            Only call those tools when the user explicitly asks about a movie by its title.
            For any other question, answer directly from your own knowledge and do not call any tool.

            Put the movie title straight into the movieTitle argument, as {"movieTitle": "Inception"}.
            Never pass the argument schema: "type", "required" and "properties" are not arguments.

            If the user greets you, thanks you, or asks anything that is not about a movie in the
            database, reply in one short sentence and call no tool at all.

            Once a tool has answered, reply with one short English sentence that names the movie and
            repeats the value the tool returned, such as "The genre of Inception is Science Fiction."
            Never answer with JSON and never write a tool call into your answer.
            """)
    Result<String> chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
