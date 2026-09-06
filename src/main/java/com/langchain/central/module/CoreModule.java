package com.langchain.central.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.langchain.central.BasicConfiguration;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.config.McpClientConfig;

/**
 * Exposes the LLM and MCP client configuration blocks to the service layer.
 *
 * @author ankush.nakaskar
 */
public class CoreModule extends AbstractModule {

    @Override
    protected void configure() {
    }

    /** Exposes the {@code llm} block of application.yml to the service layer. */
    @Provides
    @Singleton
    public LLMConfig llmConfig(final BasicConfiguration configuration) {
        return configuration.getLlm();
    }

    @Provides
    @Singleton
    public McpClientConfig mcpClientConfig(final BasicConfiguration configuration) {
        return configuration.getMcpClient();
    }

}
