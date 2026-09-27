package com.langchain.central.util;

import com.langchain.central.model.ToolResponse;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Renders tool results as Markdown for the cases where the model itself never got to write an
 * answer. It is a fallback, not a formatter for normal answers: the text is the tool output with
 * a heading around it, so the caller sees what the tool returned instead of an empty response.
 *
 * @author ankush.nakaskar
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ToolResultRenderer {

    private static final String NOTE =
            "> The model stopped before writing an answer, so the raw tool output is shown below.";

    public static String render(final List<ToolResponse> tools) {
        final StringBuilder rendered = new StringBuilder(NOTE);
        for (final ToolResponse tool : tools) {
            rendered.append("\n\n## Result of `").append(tool.getName()).append("`\n\n");
            if (tool.getArguments() != null && !tool.getArguments().isBlank()) {
                rendered.append("Called with: `").append(tool.getArguments()).append("`\n\n");
            }
            rendered.append(tool.getResult() == null ? "_No result returned._" : tool.getResult());
        }
        return rendered.toString();
    }
}
