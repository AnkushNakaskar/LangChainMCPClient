package com.langchain.central.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import lombok.Data;

/**
 * Connection settings of the Jira MCP server, driven by the {@code jiraMcpClient} block in
 * application.yml.
 *
 * @author ankush.nakaskar
 */
@Data
public class JiraMcpClientConfig {

    @JsonProperty("enabled")
    private boolean enabled = false;

    /** Streamable HTTP endpoint of the Jira MCP server, e.g. mcp-atlassian on port 9000. */
    @NotBlank
    @JsonProperty("url")
    private String url = "http://localhost:9000/mcp";

    @NotBlank
    @JsonProperty("key")
    private String key = "jira-mcp-server";

    /** Jira project the sprint questions are about when the user does not name one. */
    @NotBlank
    @JsonProperty("projectKey")
    private String projectKey = "DIS";

    @Positive
    @JsonProperty("timeoutSeconds")
    private int timeoutSeconds = 60;

    /**
     * Headers sent with every MCP request, e.g. {@code Authorization} when the MCP server expects
     * the Jira credentials per request instead of reading them from its own environment.
     */
    @JsonProperty("headers")
    private Map<String, String> headers = new HashMap<>();

    /**
     * Tools exposed to the model. Empty exposes every tool the server offers. Keeping this to the
     * read tools stops the model from editing tickets while it answers a question.
     */
    @JsonProperty("allowedTools")
    private List<String> allowedTools = new ArrayList<>();

    /**
     * JSON fields removed from tool results before they reach the model. Jira repeats these for
     * every person and status of every issue, which overflows the context of a local model.
     */
    @JsonProperty("droppedResultFields")
    private List<String> droppedResultFields = new ArrayList<>(List.of(
            "avatar_url", "account_id", "email", "color", "browse_url", "self", "total", "start_at"));

    @JsonProperty("logRequests")
    private boolean logRequests;

    @JsonProperty("logResponses")
    private boolean logResponses;
}
