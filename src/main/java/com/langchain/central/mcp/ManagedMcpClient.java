package com.langchain.central.mcp;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.config.McpClientConfig;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class ManagedMcpClient implements Managed {

    private final McpClientConfig config;
    private DefaultMcpClient client;
    private McpToolProvider toolProvider;

    @Inject
    public ManagedMcpClient(final McpClientConfig config) {
        this.config = config;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("MCP client is disabled");
            return;
        }

        final Duration timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        final var transport = StreamableHttpMcpTransport.builder()
                .url(config.getUrl())
                .timeout(timeout)
                .logRequests(config.isLogRequests())
                .logResponses(config.isLogResponses())
                .build();

        client = DefaultMcpClient.builder()
                .key(config.getKey())
                .clientName("langchain-demo")
                .clientVersion("1.0.0")
                .protocolVersion("2025-11-25")
                .initializationTimeout(timeout)
                .toolExecutionTimeout(timeout)
                .cacheToolList(true)
                .transport(transport)
                .build();

        final var tools = client.listTools();
        toolProvider = McpToolProvider.builder()
                .mcpClients(client)
                .failIfOneServerFails(true)
                .build();
        log.info("Connected to MCP server {} and discovered {} tools",
                config.getUrl(), tools.size());
    }

    @Override
    public void stop() {
        if (client != null) {
            client.close();
        }
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public McpToolProvider toolProvider() {
        if (!config.isEnabled()) {
            throw new IllegalStateException("MCP client is disabled");
        }
        if (toolProvider == null) {
            throw new IllegalStateException("MCP client has not started");
        }
        return toolProvider;
    }
}
