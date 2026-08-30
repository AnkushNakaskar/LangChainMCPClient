package com.langchain.central.dao;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the movie catalogue.
 *
 * <p>The interface is what the tools depend on, so the in-memory catalogue behind it can be
 * replaced by a real database without touching the tools, the service or the resource.
 *
 * @author ankush.nakaskar
 */
public interface MovieDao {

    /**
     * @param title movie title as the user wrote it
     * @return the movie, or empty when the catalogue does not hold it
     */
    Optional<Movie> findByTitle(String title);

    /** @return every title in the catalogue, sorted */
    List<String> findAllTitles();
}
