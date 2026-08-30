package com.langchain.central.assistance;

/**
 * Selects which assistant, and therefore which tool set, handles a request.
 *
 * @author ankush.nakaskar
 */
public enum AssistanceType {

    /** Answers questions about the movie database. */
    MOVIE,

    /** Answers questions about the GIT. */
    GIT,
}
