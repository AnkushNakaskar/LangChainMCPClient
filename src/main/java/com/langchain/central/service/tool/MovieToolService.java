package com.langchain.central.service.tool;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.dao.Movie;
import com.langchain.central.dao.MovieDao;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tools that let the assistant read the movie catalogue.
 *
 * <p>Each method is one question the model may ask of the catalogue. They stay this thin on
 * purpose: langchain4j turns the annotations into the tool schema the model is shown, so the
 * descriptions are part of the prompt, while the lookup itself belongs to the {@link MovieDao}.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class MovieToolService implements ToolService {

    /** Returned when a title is unknown, so the model says so instead of inventing an answer. */
    private static final String NOT_FOUND = "Movie not found or attribute not available.";

    private final MovieDao movieDao;

    @Tool("Get the genre of a specific movie")
    public String getMovieGenre(@P("The title of the movie") final String movieTitle) {
        log.info("Get the genre of a specific movie {}", movieTitle);
        return attributeOf(movieTitle, "genre", Movie::genre);
    }

    @Tool("Get the director of a specific movie")
    public String getMovieDirector(@P("The title of the movie") final String movieTitle) {
        log.info("Get the director of a specific movie {}", movieTitle);
        return attributeOf(movieTitle, "director", Movie::director);
    }

    @Tool("Get the release year of a specific movie")
    public String getMovieReleaseYear(@P("The title of the movie") final String movieTitle) {
        log.info("Get the release year of a specific movie");
        return attributeOf(movieTitle, "release year", Movie::releaseYear);
    }

    @Tool("List all movie titles available in the database")
    public String listAllMovies() {
        final List<String> titles = movieDao.findAllTitles();
        log.info("Tool called: listAllMovies, {} titles available", titles.size());
        return titles.isEmpty() ? "The movie database is empty." : String.join(", ", titles);
    }

    private String attributeOf(final String movieTitle,
                               final String attribute,
                               final Function<Movie, String> reader) {
        log.info("Tool called: {} of movie '{}'", attribute, movieTitle);
        return movieDao.findByTitle(movieTitle)
                .map(reader)
                .orElse(NOT_FOUND);
    }
}
