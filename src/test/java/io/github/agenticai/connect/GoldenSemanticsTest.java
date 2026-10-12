package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Compares baseline semantics using independent field extraction rather than production codecs. */
class GoldenSemanticsTest {
    private final Connect kit = new Connect();
    private static ConversionContext context() {
        ConversionContext context = new ConversionContext(); context.upstreamModel = "upstream-model"; return context;
    }
    @TestFactory Stream<DynamicTest> responseSnapshots() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Format from : Format.values()) for (Format to : Format.values()) {
            if (from == to || to == Format.GEMINI || from == Format.OPENAI_CHAT && to == Format.CLAUDE) continue;
            tests.add(DynamicTest.dynamicTest("响应语义快照 " + from + " → " + to, () -> {
                JsonObject expected = ConnectTest.resource("golden/response/" + Connect.converterId(from, to) + ".golden.json").getAsJsonObject();
                JsonObject input = ConnectTest.resource("response/" + from.wireName() + ".json").getAsJsonObject();
                JsonObject actual = kit.convertResponse(from, to, input, context()).value();
                assertEquals(responseView(to, expected), responseView(to, actual));
            }));
        }
        return tests.stream();
    }
    private static JsonObject responseView(Format format, JsonObject response) {
        StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        JsonArray calls = new JsonArray(); String finish;
        switch (format) {
            case OPENAI_CHAT -> {
                JsonObject choice = Json.o(Json.a(response, "choices").get(0)), message = Json.o(choice, "message");
                text.append(Json.s(message, "content")); reasoning.append(Json.s(message, "reasoning_content"));
                for (JsonElement element : Json.a(message, "tool_calls")) {
                    JsonObject f = Json.o(Json.o(element), "function");
                    calls.add(Json.obj("name", Json.s(f, "name"), "args", Json.parse(Json.s(f, "arguments"))));
                }
                finish = Json.s(choice, "finish_reason");
            }
            case CLAUDE -> {
                for (JsonElement element : Json.a(response, "content")) {
                    JsonObject block = Json.o(element);
                    switch (Json.s(block, "type")) {
                        case "text" -> text.append(Json.s(block, "text"));
                        case "thinking" -> reasoning.append(Json.s(block, "thinking"));
                        case "tool_use" -> calls.add(Json.obj("name", Json.s(block, "name"), "args", block.get("input")));
                        default -> { }
                    }
                }
                finish = Json.s(response, "stop_reason");
            }
            case OPENAI_RESPONSES -> {
                for (JsonElement element : Json.a(response, "output")) {
                    JsonObject item = Json.o(element);
                    if (Json.s(item, "type").equals("message")) {
                        for (JsonElement block : Json.a(item, "content")) text.append(Json.s(Json.o(block), "text"));
                    } else if (Json.s(item, "type").equals("reasoning")) {
                        for (JsonElement summary : Json.a(item, "summary")) reasoning.append(Json.s(Json.o(summary), "text"));
                    } else if (Json.s(item, "type").equals("function_call"))
                        calls.add(Json.obj("name", Json.s(item, "name"), "args", Json.parse(Json.s(item, "arguments"))));
                }
                finish = Json.s(response, "status");
            }
            default -> throw new IllegalArgumentException("无该方向的上游快照");
        }
        JsonObject usage = Json.o(response, "usage");
        long input = format == Format.CLAUDE ? Json.n(usage, "input_tokens") + Json.n(usage, "cache_read_input_tokens") + Json.n(usage, "cache_creation_input_tokens")
                : format == Format.OPENAI_CHAT ? Json.n(usage, "prompt_tokens") : Json.n(usage, "input_tokens");
        long output = Json.n(usage, format == Format.OPENAI_CHAT ? "completion_tokens" : "output_tokens");
        return Json.obj("text", text.toString(), "reasoning", reasoning.toString(), "calls", calls, "finish", finish, "input", input, "output", output);
    }
    @TestFactory Stream<DynamicTest> streamSnapshots() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Format from : Format.values()) for (Format to : Format.values()) {
            if (from == to) continue;
            tests.add(DynamicTest.dynamicTest("流式语义快照 " + from + " → " + to, () -> {
                JsonObject expected = ConnectTest.resource("golden/stream/" + Connect.converterId(from, to) + ".golden.json").getAsJsonObject();
                ResponseStreamState state = kit.newResponseStream(from, to, null, context());
                JsonArray actual = new JsonArray();
                for (JsonElement chunk : ConnectTest.resource("stream/" + from.wireName() + ".json").getAsJsonArray())
                    for (ConversionResult result : state.accept(chunk.getAsJsonObject())) actual.add(result.value());
                for (ConversionResult result : state.finish()) actual.add(result.value());
                assertEquals(streamText(to, Json.a(expected, "events")), streamText(to, actual));
                // Some baseline routes have historical usage differences; matrix tests verify source usage.
            }));
        }
        return tests.stream();
    }
    private static String streamText(Format format, JsonArray events) {
        StringBuilder text = new StringBuilder();
        for (JsonElement element : events) {
            JsonObject event = Json.o(element);
            // Baseline SSE envelopes store Type and Payload; this API returns the event directly.
            if (event.has("Payload")) event = Json.o(event, "Payload");
            switch (format) {
                case OPENAI_CHAT -> { for (JsonElement choice : Json.a(event, "choices")) text.append(Json.s(Json.o(Json.o(choice), "delta"), "content")); }
                case CLAUDE -> { if (Json.s(event, "type").equals("content_block_delta")) text.append(Json.s(Json.o(event, "delta"), "text")); }
                case GEMINI -> {
                    for (JsonElement candidate : Json.a(event, "candidates")) for (JsonElement part : Json.a(Json.o(Json.o(candidate), "content"), "parts"))
                        if (!Json.b(Json.o(part), "thought")) text.append(Json.s(Json.o(part), "text"));
                }
                case OPENAI_RESPONSES -> { if (Json.s(event, "type").equals("response.output_text.delta")) text.append(Json.s(event, "delta")); }
            }
        }
        return text.toString();
    }
}
