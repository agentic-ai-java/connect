package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamTest {
    private final Connect kit = new Connect();
    private static StreamOptions options() {
        StreamOptions options = new StreamOptions(); options.id = "resp_test"; options.model = "test"; options.emitSequenceNumber = true; return options;
    }
    private static JsonObject chat(JsonObject delta, String finish) {
        return Json.obj("choices", Json.arr(Json.obj("index", 0, "delta", delta, "finish_reason", finish)));
    }
    private static List<JsonObject> toolEvents(Format source) {
        return switch (source) {
            case OPENAI_CHAT -> List.of(
                    chat(Json.obj("reasoning_content", "想"), null), chat(Json.obj("content", "查"), null),
                    chat(Json.obj("tool_calls", Json.arr(Json.obj("index", 0, "id", "call_a", "type", "function",
                            "function", Json.obj("name", "weather", "arguments", "{")))), null),
                    chat(Json.obj("tool_calls", Json.arr(Json.obj("index", 0, "function", Json.obj("arguments", "\"city\":\"Paris\"}")))), null),
                    chat(Json.obj(), "tool_calls"));
            case CLAUDE -> List.of(
                    Json.obj("type", "message_start", "message", Json.obj("model", "test", "usage", Json.obj("input_tokens", 5))),
                    Json.obj("type", "content_block_delta", "index", 0, "delta", Json.obj("type", "thinking_delta", "thinking", "想")),
                    Json.obj("type", "content_block_delta", "index", 1, "delta", Json.obj("type", "text_delta", "text", "查")),
                    Json.obj("type", "content_block_start", "index", 2, "content_block", Json.obj("type", "tool_use", "id", "call_a", "name", "weather", "input", Json.obj())),
                    Json.obj("type", "content_block_delta", "index", 2, "delta", Json.obj("type", "input_json_delta", "partial_json", "{")),
                    Json.obj("type", "content_block_delta", "index", 2, "delta", Json.obj("type", "input_json_delta", "partial_json", "\"city\":\"Paris\"}")),
                    Json.obj("type", "message_delta", "delta", Json.obj("stop_reason", "tool_use"), "usage", Json.obj("output_tokens", 3)),
                    Json.obj("type", "message_stop"));
            case GEMINI -> List.of(Json.obj("candidates", Json.arr(Json.obj("content", Json.obj("parts", Json.arr(
                    Json.obj("text", "想", "thought", true), Json.obj("text", "查"), Json.obj("functionCall", Json.obj("id", "call_a", "name", "weather", "args", Json.obj("city", "Paris")))),
                    "role", "model"), "finishReason", "STOP")), "usageMetadata", Json.obj("promptTokenCount", 5, "candidatesTokenCount", 3)));
            case OPENAI_RESPONSES -> List.of(
                    Json.obj("type", "response.created", "response", Json.obj("model", "test")),
                    Json.obj("type", "response.reasoning_summary_text.delta", "delta", "想"),
                    Json.obj("type", "response.output_text.delta", "delta", "查"),
                    Json.obj("type", "response.output_item.added", "output_index", 2, "item",
                            Json.obj("id", "fc_a", "type", "function_call", "call_id", "call_a", "name", "weather", "arguments", "")),
                    Json.obj("type", "response.function_call_arguments.delta", "item_id", "fc_a", "delta", "{"),
                    Json.obj("type", "response.function_call_arguments.delta", "item_id", "fc_a", "delta", "\"city\":\"Paris\"}"),
                    Json.obj("type", "response.function_call_arguments.done", "item_id", "fc_a", "arguments", "{\"city\":\"Paris\"}"),
                    Json.obj("type", "response.completed", "response", Json.obj("usage", Json.obj("input_tokens", 5, "output_tokens", 3))));
        };
    }
    @TestFactory Stream<DynamicTest> toolsAndReasoningMatrix() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Format from : Format.values()) for (Format to : Format.values()) {
            if (from == to) continue;
            tests.add(DynamicTest.dynamicTest("工具流 " + from + " → " + to, () -> {
                ResponseStreamState state = kit.newResponseStream(from, to, options(), null);
                List<ConversionResult> output = new ArrayList<>();
                for (JsonObject event : toolEvents(from)) output.addAll(state.accept(event));
                output.addAll(state.finish());
                StringBuilder all = new StringBuilder(); for (ConversionResult result : output) all.append(result.json());
                assertTrue(all.toString().contains("weather")); assertTrue(all.toString().contains("Paris"));
                assertTrue(all.toString().contains("想")); assertTrue(all.toString().contains("查"));
                if (to == Format.OPENAI_RESPONSES) {
                    JsonArray items = Json.a(Json.o(output.get(output.size() - 1).value(), "response"), "output");
                    JsonObject tool = Json.o(items.get(items.size() - 1));
                    assertEquals("call_a", Json.s(tool, "call_id"));
                    assertEquals("{\"city\":\"Paris\"}", Json.s(tool, "arguments"));
                }
                if (to == Format.GEMINI) {
                    JsonObject last = output.get(output.size() - 1).value();
                    JsonObject tool = Json.o(Json.a(Json.o(Json.o(Json.a(last, "candidates").get(0)), "content"), "parts").get(0));
                    assertEquals("Paris", Json.s(Json.o(Json.o(tool, "functionCall"), "args"), "city"));
                }
                List<String> expectedDiagnostics = from == Format.OPENAI_RESPONSES
                        ? List.of("reasoning_summary_not_replayable") : List.of();
                assertEquals(expectedDiagnostics, state.diagnostics().stream().map(Diagnostic::code).toList());
            }));
        }
        return tests.stream();
    }
    @Test void parallelToolFragmentsKeepIndependentIndices() {
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, options(), null);
        state.accept(chat(Json.obj("tool_calls", Json.arr(
                Json.obj("index", 0, "id", "a", "function", Json.obj("name", "one", "arguments", "{\"x\":")),
                Json.obj("index", 1, "id", "b", "function", Json.obj("name", "two", "arguments", "{\"y\":")))), null));
        state.accept(chat(Json.obj("tool_calls", Json.arr(
                Json.obj("index", 1, "function", Json.obj("arguments", "2}")),
                Json.obj("index", 0, "function", Json.obj("arguments", "1}")))), null));
        List<ConversionResult> output = state.finish();
        JsonArray items = Json.a(Json.o(output.get(output.size() - 1).value(), "response"), "output");
        assertEquals("{\"x\":1}", Json.s(Json.o(items.get(0)), "arguments"));
        assertEquals("{\"y\":2}", Json.s(Json.o(items.get(1)), "arguments"));
    }
    @Test void lateUsageAndTerminalContent() {
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_CHAT, Format.CLAUDE, options(), null);
        state.accept(chat(Json.obj("content", "hi"), null)); state.accept(chat(Json.obj(), "stop"));
        assertTrue(state.accept(chat(Json.obj("content", "late"), null)).isEmpty());
        state.accept(Json.obj("choices", new JsonArray(), "usage", Json.obj("prompt_tokens", 10, "completion_tokens", 2)));
        List<ConversionResult> finalEvents = state.finish();
        assertEquals(10, state.usage().inputTokens()); assertEquals(2, state.usage().outputTokens());
        assertEquals("late_stream_content_ignored", state.diagnostics().get(0).code());
        assertEquals(2, Json.n(Json.o(finalEvents.get(finalEvents.size() - 2).value(), "usage"), "output_tokens"));
    }
    @Test void incompleteToolArgumentsRemainAvailableForResponses() {
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, options(), null);
        state.accept(chat(Json.obj("tool_calls", Json.arr(Json.obj("index", 0, "id", "a",
                "function", Json.obj("name", "one", "arguments", "{\"x\":")))), null));
        state.accept(chat(Json.obj(), "length"));
        List<ConversionResult> output = state.finish();
        JsonObject last = output.get(output.size() - 1).value();
        assertEquals("response.incomplete", Json.s(last, "type"));
        assertEquals("{\"x\":", Json.s(Json.o(Json.a(Json.o(last, "response"), "output").get(0)), "arguments"));
    }
    @Test void failureAndSequenceAreTerminalAndIdempotent() {
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, options(), null);
        List<ConversionResult> first = state.accept(chat(Json.obj("content", "hi"), null));
        List<ConversionResult> failed = state.failResponsesStream("bad_upstream", "上游错误");
        assertEquals("error", Json.s(failed.get(0).value(), "type"));
        assertEquals("response.failed", Json.s(failed.get(1).value(), "type"));
        assertEquals(first.size(), Json.n(failed.get(0).value(), "sequence_number"));
        assertTrue(state.finish().isEmpty()); assertTrue(state.failResponsesStream("again", "again").isEmpty());
        assertTrue(state.finalized());
        assertThrows(IllegalStateException.class, () -> kit.newResponseStream(Format.OPENAI_CHAT, Format.CLAUDE, null, null).failResponsesStream("x", "x"));
    }
    @Test void upstreamFailedResponses() {
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_RESPONSES, Format.CLAUDE, options(), null);
        List<ConversionResult> output = state.accept(Json.obj("type", "response.failed", "response", Json.obj("error", Json.obj("message", "bad"))));
        assertEquals("error", Json.s(output.get(output.size() - 1).value(), "type"));
        assertTrue(state.finalized()); assertFalse(state.diagnostics().isEmpty());
    }
    @Test void missingToolStartIsAnError() {
        ResponseStreamState state = kit.newResponseStream(Format.CLAUDE, Format.OPENAI_CHAT, null, null);
        assertThrows(ConversionException.class, () -> state.accept(Json.obj("type", "content_block_delta", "index", 0,
                "delta", Json.obj("type", "input_json_delta", "partial_json", "{}"))));
        ResponseStreamState responses = kit.newResponseStream(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, null, null);
        assertThrows(ConversionException.class, () -> responses.accept(Json.obj("type", "response.function_call_arguments.delta", "item_id", "x", "delta", "{}")));
    }
    @Test void usageOptionAndSequenceOptionSnapshot() {
        StreamOptions options = options(); options.includeUsage = false; options.emitSequenceNumber = false;
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, options, null);
        options.includeUsage = true; options.emitSequenceNumber = true;
        List<ConversionResult> events = state.accept(chat(Json.obj("content", "hi"), null));
        assertFalse(events.get(0).value().has("sequence_number"));
        List<ConversionResult> finalEvents = state.finish();
        assertFalse(Json.o(finalEvents.get(finalEvents.size() - 1).value(), "response").has("usage"));
    }
    @Test void independentStreamsDoNotLeakContent() {
        ResponseStreamState one = kit.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, null, null);
        ResponseStreamState two = kit.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, null, null);
        one.accept(chat(Json.obj("content", "one"), null)); two.accept(chat(Json.obj("content", "two"), null));
        List<ConversionResult> first = one.finish(), second = two.finish();
        assertFalse(first.get(first.size() - 1).json().contains("two"));
        assertFalse(second.get(second.size() - 1).json().contains("one"));
    }
}
