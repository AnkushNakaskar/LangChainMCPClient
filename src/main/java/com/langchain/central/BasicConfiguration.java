package com.langchain.central;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.langchain.central.config.JiraMcpClientConfig;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.config.McpClientConfig;
import com.langchain.central.config.RagConfig;
import in.vectorpro.dropwizard.swagger.SwaggerBundleConfiguration;
import io.dropwizard.Configuration;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Data
public class BasicConfiguration extends Configuration {

    @NotNull private final int defaultSize;

    @Valid
    @NotNull
    private SwaggerBundleConfiguration swagger;

    @Valid
    @NotNull
    private LLMConfig llm;


    @Valid
    @NotNull
    private McpClientConfig mcpClient = new McpClientConfig();

    @Valid
    @NotNull
    private JiraMcpClientConfig jiraMcpClient = new JiraMcpClientConfig();

    @Valid
    @NotNull
    private RagConfig rag = new RagConfig();

    private String baseName ="resource";



    @JsonCreator
    public BasicConfiguration(@JsonProperty("defaultSize") final int defaultSize) {
        this.defaultSize = defaultSize;
    }

    public int getDefaultSize() {
        return defaultSize;
    }

}