package com.langchain.central.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.langchain.central.model.StreamEvent;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Writes {@link StreamEvent}s to a response body as Server-Sent Events.
 *
 * <p>Every frame is flushed as soon as it is written. Without that the servlet container buffers
 * the answer and releases it in one block at the end, which is indistinguishable from the
 * non-streaming endpoint for the caller.
 *
 * <p>The instance is not thread safe on purpose: the streaming endpoint drains a queue from a
 * single thread, so the model's callback threads never touch the socket.
 *
 * @author ankush.nakaskar
 */
public final class SseStream implements Closeable {

    private final Writer writer;
    private final ObjectMapper objectMapper;

    public SseStream(final OutputStream output, final ObjectMapper objectMapper) {
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.objectMapper = objectMapper;
    }

    /**
     * Writes one frame, naming the event after {@link StreamEvent#getType()} so a browser can
     * listen for a single kind of frame.
     */
    public void send(final StreamEvent event) throws IOException {
        writer.write("event: ");
        writer.write(event.getType().eventName());
        writer.write('\n');
        writeData(objectMapper.writeValueAsString(event));
        writer.write('\n');
        writer.flush();
    }

    /**
     * Writes an SSE comment. Clients ignore it, but it keeps an idle connection from being closed
     * by a proxy while the model is still thinking or a tool is still running.
     */
    public void heartbeat() throws IOException {
        writer.write(": keep-alive\n\n");
        writer.flush();
    }

    /**
     * A payload containing a newline would otherwise end the frame early, so each line is written
     * as its own {@code data:} field, which the client rejoins with newlines.
     */
    private void writeData(final String payload) throws IOException {
        for (final String line : payload.split("\n", -1)) {
            writer.write("data: ");
            writer.write(line);
            writer.write('\n');
        }
    }

    @Override
    public void close() throws IOException {
        writer.flush();
    }
}
