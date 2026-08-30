package com.langchain.central.service.tool;

/**
 * Marks a class that exposes {@code @Tool} annotated methods to an assistant.
 *
 * <p>Implementations are collected into a {@code Set<ToolService>} by Guice, so adding a new
 * tool class only requires binding it in the module.
 *
 * @author ankush.nakaskar
 */
public interface ToolService {

    /** Human readable name, used for logging which tool sets are registered. */
    default String name() {
        return getClass().getSimpleName();
    }
}
