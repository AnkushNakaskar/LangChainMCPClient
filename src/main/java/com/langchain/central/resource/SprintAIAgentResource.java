package com.langchain.central.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.service.SprintAIAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import java.time.Instant;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author ankush.nakaskar
 */
@Slf4j
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Path("/sprint")
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class SprintAIAgentResource {


    private final SprintAIAgentService sprintAIAgentService;
    private final LLMConfig llmConfig;
    private final ObjectMapper objectMapper;



    @POST
    @Path("/sprint/chat")
    @Operation(summary = "Chat with the Sprint AI agent assistant")
    @RequestBody(description = "Prompt, and optionally a sessionId, a model override and useTool")
    public AIResponse chatWithSprintAIAgent(@Valid @NotNull final AIRequest request) {
        log.info("Sprint AI Agent request:{}", request);
        try {

            return AIResponse.builder()
                    .response("Success")
                    .createdAt(Instant.now())
                    .sessionId(request.getSessionId())
                    .done(true)
                    .build();
        } catch (Exception e) {
            log.error("Chat failed for prompt {}", request.getPrompt(), e);
            return AIResponse.builder()
                    .createdAt(Instant.now())
                    .sessionId(request.getSessionId())
                    .done(false)
                    .doneReason("error")
                    .error(e.getMessage())
                    .build();
        }
    }

}
