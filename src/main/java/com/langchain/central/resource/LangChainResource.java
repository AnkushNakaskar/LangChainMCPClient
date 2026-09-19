package com.langchain.central.resource;

import com.google.inject.Inject;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.service.LangChainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import java.time.Instant;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP entry point of the application.
 *
 * <p>The resource only validates the request, hands it to {@link LangChainService} and shapes the
 * outcome for the caller. Nothing about models, memory or tools is decided here.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Path("/langchain")
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class LangChainResource {

    private final LangChainService langChainService;

    /**
     * @param request prompt, plus optional sessionId, model override and {@code useTool} flag
     * @return the answer, the token counts and every tool the model called while answering
     */
    @POST
    @Path("/chat")
    @Operation(summary = "Chat with the Git assistant")
    @RequestBody(description = "Prompt, and optionally a sessionId, a model override and useTool")
    public AIResponse chat(@Valid @NotNull final AIRequest request) {
        log.info("Chat requested on session {}", request);
        try {
            final AIResponse response = langChainService.chat(request);
            log.info("Assistant answered with {} characters and {} tool calls",
                    response.getResponse() == null ? 0 : response.getResponse().length(),
                    response.getTools() == null ? 0 : response.getTools().size());
            return response;
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


    @GET
    @Path("/health")
    @Operation(summary = "Liveness probe for the chat endpoint")
    public Response health() {
        return Response.ok().entity("{\"status\":\"UP\"}").build();
    }
}
