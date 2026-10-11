package com.langchain.central.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.agents.SprintAIAgent;
import com.langchain.central.assistance.GitAssistance;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class SprintAIAgentService {

    private final LLMConfig llmConfig;

    @Inject
    public SprintAIAgentService(final LLMConfig llmConfig) {
        this.llmConfig = llmConfig;
    }


    public AIResponse chatStream(final AIRequest request) {
        final String sessionId = request.getSessionId();
        final long startedAt = System.nanoTime();
        final StreamingChatModel model = LLMConvertors.toChatModel(llmConfig);
        SystemMessage systemMessage = SystemMessage.from(GitAssistance.SYSTEM_PROMPT);
        UserMessage userMessage = UserMessage.from(request.getPrompt());

        String mockJiraJson = mockJiraDetailsInput();

        // 3. Define Custom Chat Parameters (Temperature, Max Tokens, etc.)
        ChatRequestParameters parameters = ChatRequestParameters.builder()
                .temperature(0.2)           // Deterministic output
                .maxOutputTokens(100)        // Token limit
                .build();

        // 4. Combine Messages and Parameters into a ChatRequest
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(systemMessage, userMessage)
                .parameters(parameters)
                .build();
        SprintAIAgent agent = AiServices.create(SprintAIAgent.class, model);
        TokenStream stream = agent.getSprintDetails("SPRINT-42", mockJiraJson, request.getPrompt());
        final CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
        stream.onCompleteResponse((ChatResponse response) -> futureResponse.complete(response))
                .onError((Throwable error) -> futureResponse.completeExceptionally(error))
                .start();
        final ChatResponse response = futureResponse.join(); // Blocks the main thread until the streaming process is complete
        final String streamedText = response.aiMessage() == null ? null : response.aiMessage().text();
        return LLMConvertors.toAIResponse(response, response.tokenUsage(), streamedText, null,
                llmConfig.getModelName(), sessionId, System.nanoTime() - startedAt);
    }

    private static String mockJiraDetailsInput() {
        return """
                {
                  "sprint": { "id": "SPRINT-42", "name": "Sprint 42 - Payment Gateway Integration", "state": "ACTIVE" },
                  "teamMembers": ["Alice (Dev)", "Bob (Dev)", "Charlie (QA)", "Diana (PO)"],
                  "metrics": { "totalTasks": 12, "done": 5, "inProgress": 4, "codeReview": 2, "blocked": 1 },
                  "epics": [
                    {
                      "epicKey": "EPIC-101",
                      "summary": "Modernize Checkout Pipeline",
                      "quarterlyGoal": "Q4-OKR-1: Increase Checkout Conversion by 15%",
                      "yearlyRoadmap": "2026 Architectural Platform Refresh"
                    }
                  ],
                  "issues": [
                    { "key": "PAY-1", "summary": "Stripe webhook integration", "status": "In Progress", "assignee": "Alice", "epic": "EPIC-101" },
                    { "key": "PAY-2", "summary": "Fix double charge bug", "status": "Done", "assignee": "Bob", "epic": "EPIC-101" },
                    { "key": "PAY-3", "summary": "3D Secure auth flow", "status": "Blocked", "assignee": "Alice", "blockerReason": "Waiting on third-party API keys" }
                  ]
                }
                """;
    }

}
