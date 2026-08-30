package com.langchain.central.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.langchain.central.BasicConfiguration;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.config.McpClientConfig;
import com.langchain.central.dao.InMemoryMovieDao;
import com.langchain.central.dao.MovieDao;

/**
 * Wires the three layers together: the DAO the tools read from, the tools the assistant is given,
 * and the configuration block the service layer needs.
 *
 * @author ankush.nakaskar
 */
public class CoreModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(MovieDao.class).to(InMemoryMovieDao.class);
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
