package com.langchain.central.dao;

/**
 * A single movie as the tools expose it.
 *
 * <p>The fields are named after what the assistant is asked for, so a tool reads one of them
 * directly instead of looking an attribute up by string.
 *
 * @author ankush.nakaskar
 */
public record Movie(String title, String genre, String director, String releaseYear) {
}
