package com.langchain.central.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single tool invocation the model made while answering.
 *
 * @author ankush.nakaskar
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResponse {

    /** Tool method name, e.g. {@code getMovieGenre}. */
    private String name;

    /** JSON arguments the model passed to the tool. */
    private String arguments;

    /** Value the tool returned back to the model. */
    private String result;
}
