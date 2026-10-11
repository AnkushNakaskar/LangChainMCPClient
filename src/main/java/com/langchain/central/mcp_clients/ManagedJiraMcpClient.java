package com.langchain.central.mcp_clients;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.config.JiraMcpClientConfig;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.util.HashSet;
import lombok.extern.slf4j.Slf4j;

/**
 * MCP client of the Jira MCP server, which exposes Jira search, issue, board and sprint tools.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class ManagedJiraMcpClient implements Managed {

    private final JiraMcpClientConfig config;
    private final McpStreamEvents streamEvents;
    private DefaultMcpClient client;
    private ToolProvider toolProvider;

    @Inject
    public ManagedJiraMcpClient(final JiraMcpClientConfig config, final McpStreamEvents streamEvents) {
        this.config = config;
        this.streamEvents = streamEvents;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("Jira MCP client is disabled");
            return;
        }

        final Duration timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        final var transport = StreamableHttpMcpTransport.builder()
                .url(config.getUrl())
                .customHeaders(config.getHeaders())
                .timeout(timeout)
                .logRequests(config.isLogRequests())
                .logResponses(config.isLogResponses())
                .build();

        client = DefaultMcpClient.builder()
                .key(config.getKey())
                .clientName("langchain-demo-jira")
                .clientVersion("1.0.0")
                .protocolVersion("2025-11-25")
                .initializationTimeout(timeout)
                .toolExecutionTimeout(timeout)
                .cacheToolList(true)
                .addListener(streamEvents)
                .progressHandler(streamEvents)
                .logHandler(streamEvents)
                .transport(transport)
                .build();

        final var tools = client.listTools();
        final var builder = McpToolProvider.builder()
                .mcpClients(client)
                .failIfOneServerFails(true);
        if (!config.getAllowedTools().isEmpty()) {
            builder.filterToolNames(config.getAllowedTools());
        }
        toolProvider = new CompactingToolProvider(builder.build(),
                new HashSet<>(config.getDroppedResultFields()));
        log.info("Connected to Jira MCP server {} and discovered {} tools: {}",
                config.getUrl(), tools.size(), tools.stream().map(t -> t.name()).toList());
    }

    @Override
    public void stop() {
        if (client != null) {
            client.close();
        }
    }

    public ToolProvider toolProvider() {
        if (!config.isEnabled()) {
            throw new IllegalStateException("Jira MCP client is disabled");
        }
        if (toolProvider == null) {
            throw new IllegalStateException("Jira MCP client has not started");
        }
        return toolProvider;
    }
}
