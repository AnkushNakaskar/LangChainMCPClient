package com.langchain.central.config;

/**
 * Where the LLM is hosted.
 *
 * @author ankush.nakaskar
 */
public enum LlmType {

    /** A model served on this machine (Ollama / llama.cpp). No real API key is required. */
    LOCAL,

    /** A hosted provider (OpenAI, Groq, ...) reached over the network. An API key is mandatory. */
    REMOTE
}
