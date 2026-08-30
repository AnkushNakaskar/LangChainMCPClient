package com.langchain.central.dao;

import com.google.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The demo catalogue, held in memory.
 *
 * <p>Titles are matched without regard to case or surrounding spaces: the title reaches this class
 * as the model copied it out of a sentence, so {@code "inception"} and {@code " Inception "} have
 * to find the same film as {@code "Inception"}.
 *
 * @author ankush.nakaskar
 */
@Singleton
public class InMemoryMovieDao implements MovieDao {

    private final Map<String, Movie> moviesByKey;

    public InMemoryMovieDao() {
        this(List.of(
                new Movie("Inception", "Science Fiction", "Christopher Nolan", "2010"),
                new Movie("Pulp Fiction", "Crime", "Quentin Tarantino", "1994"),
                new Movie("The Matrix", "Science Fiction", "The Wachowskis", "1999"),
                new Movie("Forrest Gump", "Drama", "Robert Zemeckis", "1994")));
    }

    public InMemoryMovieDao(final List<Movie> movies) {
        this.moviesByKey = movies.stream().collect(Collectors.toMap(
                movie -> key(movie.title()),
                movie -> movie,
                (first, ignored) -> first,
                LinkedHashMap::new));
    }

    @Override
    public Optional<Movie> findByTitle(final String title) {
        return title == null ? Optional.empty() : Optional.ofNullable(moviesByKey.get(key(title)));
    }

    @Override
    public List<String> findAllTitles() {
        return moviesByKey.values().stream().map(Movie::title).sorted().toList();
    }

    private static String key(final String title) {
        return title.strip().toLowerCase(Locale.ROOT);
    }
}
