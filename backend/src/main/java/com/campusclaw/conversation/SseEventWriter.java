package com.campusclaw.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 将单行 JSON 编码为标准 SSE 帧，并在每一帧后立即刷新。 */
final class SseEventWriter {
    private final OutputStream output;
    private final ObjectMapper objectMapper;

    SseEventWriter(OutputStream output, ObjectMapper objectMapper) {
        this.output = output;
        this.objectMapper = objectMapper;
    }

    void write(String event, Object data) {
        try {
            String frame = "event: " + event + "\n"
                    + "data: " + objectMapper.writeValueAsString(data) + "\n\n";
            output.write(frame.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
