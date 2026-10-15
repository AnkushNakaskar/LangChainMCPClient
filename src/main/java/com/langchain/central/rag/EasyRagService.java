package com.langchain.central.rag;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.config.RagConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class EasyRagService {

    private final LLMConfig llmConfig;
    private final RagConfig ragConfig;

    private volatile EmbeddingStore<TextSegment> embeddingStore;
    private volatile ContentRetriever contentRetriever;
    private volatile boolean initialized;

    @Inject
    public EasyRagService(final LLMConfig llmConfig, final RagConfig ragConfig) {
        this.llmConfig = llmConfig;
        this.ragConfig = ragConfig;
    }

    @PostConstruct
    public void initialize() {
        if (!ragConfig.isEnabled()) {
            log.info("Easy RAG is disabled.");
            return;
        }

        try {
            final Path documentsPath = Path.of(ragConfig.getDocumentsPath());
            if (!Files.isDirectory(documentsPath)) {
                log.warn("Easy RAG document path '{}' does not exist or is not a directory; disabling retrieval.",
                        documentsPath);
                return;
            }

            final OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                    .baseUrl(llmConfig.getBaseUrl())
                    .apiKey(llmConfig.getApiKey())
                    .modelName(ragConfig.getEmbeddingModelName())
                    .timeout(Duration.ofSeconds(llmConfig.getTimeoutSeconds()))
                    .logRequests(llmConfig.isLogRequests())
                    .logResponses(llmConfig.isLogResponses())
                    .build();

            final InMemoryEmbeddingStore<TextSegment> memoryStore = new InMemoryEmbeddingStore<>();
            final List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively(documentsPath);
            if (documents.isEmpty()) {
                log.warn("Easy RAG loaded zero documents from '{}'.", documentsPath);
                return;
            }

            final EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(memoryStore)
                    .documentSplitter(DocumentSplitters.recursive(300, 30))
                    .build();

            ingestor.ingest(documents);

            this.embeddingStore = memoryStore;
            this.contentRetriever = new EmbeddingStoreContentRetriever(memoryStore, embeddingModel,
                    ragConfig.getMaxResults(), ragConfig.getMinScore());
            this.initialized = true;
            log.info("Easy RAG initialized with {} documents from '{}' using embedding model '{}'.",
                    documents.size(), documentsPath, ragConfig.getEmbeddingModelName());
        } catch (Exception e) {
            log.error("Easy RAG initialization failed. Retrieval remains disabled.", e);
            this.initialized = false;
            this.contentRetriever = null;
            this.embeddingStore = null;
        }
    }

    public boolean isEnabled() {
        return ragConfig.isEnabled() && initialized && contentRetriever != null;
    }

    public ContentRetriever contentRetriever() {
        return contentRetriever;
    }
}
