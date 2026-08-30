package com.langchain.central.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import lombok.Data;

@Data
public class McpClientConfig {

    @JsonProperty("enabled")
    private boolean enabled = true;

    @NotBlank
    @JsonProperty("url")
    private String url = "http://localhost:8088/mcp";

    @NotBlank
    @JsonProperty("key")
    private String key = "langchain-mcp-server";

    @Positive
    @JsonProperty("timeoutSeconds")
    private int timeoutSeconds = 30;

    @JsonProperty("logRequests")
    private boolean logRequests;

    @JsonProperty("logResponses")
    private boolean logResponses;
}
