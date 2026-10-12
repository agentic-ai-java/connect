package io.github.agenticai.connect.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveApiExampleTest {
    @Test void parsesCrLfCommentsAndDone() {
        var events = LiveApiExample.sse(": heartbeat\r\nevent: chunk\r\ndata: {\"value\":1}\r\n\r\ndata: [DONE]\r\n\r\n");
        assertEquals(1, events.size());
        assertEquals(1, events.get(0).get("value").getAsInt());
    }

    @Test void joinsMultilineDataAndFlushesEof() {
        var events = LiveApiExample.sse("data: {\"value\":\ndata: 2}");
        assertEquals(2, events.get(0).get("value").getAsInt());
    }

    @Test void ignoresEmptyEventsAndMetadata() {
        assertTrue(LiveApiExample.sse("\n: heartbeat\nevent: ping\nid: 1\n\n").isEmpty());
    }

    @Test void rejectsProviderStreamErrors() {
        assertThrows(IllegalStateException.class, () -> LiveApiExample.sse("data: {\"error\":{}}\n\n"));
        assertThrows(IllegalStateException.class, () -> LiveApiExample.sse("data: {\"type\":\"response.failed\"}\n\n"));
    }

    @Test void rejectsMalformedJson() {
        assertThrows(RuntimeException.class, () -> LiveApiExample.sse("data: {broken}\n\n"));
    }

    @Test void redactsKeyLikeErrorDetails() {
        assertEquals("bad key [已隐藏]", LiveApiExample.safe("bad key sk-example_test-123"));
    }
}
