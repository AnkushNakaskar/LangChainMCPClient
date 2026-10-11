package com.langchain.central.mcp_clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.util.Iterator;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Shrinks the JSON that tools return before it reaches the model.
 *
 * <p>Jira answers carry avatar urls, account ids, emails and colours for every person and status
 * on every issue. A sprint search of 50 issues comes to roughly 95 KB, which overflows the context
 * of a local model; Ollama then drops the oldest messages, the user's question among them, and the
 * request fails. Removing those fields keeps the same information at a fraction of the size.
 *
 * @author ankush.nakaskar
 */
@Slf4j
public class CompactingToolProvider implements ToolProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolProvider delegate;
    private final Set<String> droppedFields;

    public CompactingToolProvider(final ToolProvider delegate, final Set<String> droppedFields) {
        this.delegate = delegate;
        this.droppedFields = droppedFields;
    }

    @Override
    public ToolProviderResult provideTools(final ToolProviderRequest request) {
        final ToolProviderResult tools = delegate.provideTools(request);
        final ToolProviderResult.Builder compacted = ToolProviderResult.builder()
                .immediateReturnToolNames(tools.immediateReturnToolNames());
        tools.tools().forEach((spec, executor) -> compacted.add(spec, new CompactingExecutor(executor)));
        return compacted.build();
    }

    @Override
    public boolean isDynamic() {
        return delegate.isDynamic();
    }

    String compact(final String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            final String compacted = MAPPER.writeValueAsString(strip(MAPPER.readTree(text)));
            log.debug("Compacted tool result from {} to {} characters", text.length(), compacted.length());
            return compacted;
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * MCP servers often wrap their JSON answer in a string, e.g. {@code {"result":"[...]"}}, so
     * string values that hold JSON are parsed and stripped as well.
     */
    private JsonNode strip(final JsonNode node) {
        if (node.isTextual()) {
            final String value = node.asText().trim();
            if (value.startsWith("{") || value.startsWith("[")) {
                try {
                    return strip(MAPPER.readTree(value));
                } catch (Exception e) {
                    return node;
                }
            }
            return node;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                ((ArrayNode) node).set(i, strip(node.get(i)));
            }
            return node;
        }
        if (node.isObject()) {
            final ObjectNode object = (ObjectNode) node;
            final Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                final String name = names.next();
                final JsonNode value = object.get(name);
                if (droppedFields.contains(name) || value.isNull()) {
                    names.remove();
                }
            }
            object.fields().forEachRemaining(field -> field.setValue(strip(field.getValue())));
        }
        return node;
    }

    private final class CompactingExecutor implements ToolExecutor {

        private final ToolExecutor executor;

        private CompactingExecutor(final ToolExecutor executor) {
            this.executor = executor;
        }

        @Override
        public String execute(final ToolExecutionRequest request, final Object memoryId) {
            return compact(executor.execute(request, memoryId));
        }

        @Override
        public ToolExecutionResult executeWithContext(final ToolExecutionRequest request,
                                                      final InvocationContext context) {
            final ToolExecutionResult result = executor.executeWithContext(request, context);
            if (result == null || result.isError()) {
                return result;
            }
            final String compacted = compact(result.resultText());
            return ToolExecutionResult.builder()
                    .isError(false)
                    .result(compacted)
                    .resultText(compacted)
                    .attributes(result.attributes())
                    .build();
        }
    }
}
