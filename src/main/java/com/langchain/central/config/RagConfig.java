package com.langchain.central.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagConfig {

    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = true;

    @NotBlank
    @JsonProperty("documentsPath")
    @Builder.Default
    private String documentsPath = "/Users/ankush.nakaskar/Office/newCode/personal_projects/LangChainMCPClient/documents";

    @NotBlank
    @JsonProperty("embeddingModelName")
    @Builder.Default
    private String embeddingModelName = "nomic-embed-text";

    @JsonProperty("maxResults")
    @Builder.Default
    private int maxResults = 5;

    @JsonProperty("minScore")
    @Builder.Default
    private double minScore = 0.0;
}
