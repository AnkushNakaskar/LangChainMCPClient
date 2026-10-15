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
    private String documentsPath = "/Users/ankush.nakaskar/Office/newCode/personal_projects/LangChainMCPClient/test_doc";

    @NotBlank
    @JsonProperty("embeddingModelName")
    @Builder.Default
    private String embeddingModelName = "hf.co/Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0";

    @JsonProperty("maxResults")
    @Builder.Default
    private int maxResults = 5;

    @JsonProperty("minScore")
    @Builder.Default
    private double minScore = 0.0;
}
