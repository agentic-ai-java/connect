package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectTest {
    private final Connect kit = new Connect(source -> new MediaResolver.MediaData("aGVsbG8=", "image/png"));
    static JsonElement resource(String path) {
        try (InputStream stream = ConnectTest.class.getResourceAsStream("/fixtures/" + path)) {
            if (stream == null) throw new IllegalArgumentException("缺少测试资源：" + path);
            return Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static ConversionContext context() {
        Options options = new Options(); options.claudeDefaultMaxTokens = model -> 4096;
        ConversionContext context = new ConversionContext(options); context.upstreamModel = "upstream-model"; return context;
    }
    @TestFactory Stream<DynamicTest> requestSnapshots() {
        return Stream.of(new Format[] {Format.OPENAI_CHAT, Format.CLAUDE},
                new Format[] {Format.OPENAI_CHAT, Format.OPENAI_RESPONSES},
                new Format[] {Format.OPENAI_RESPONSES, Format.OPENAI_CHAT},
                new Format[] {Format.OPENAI_RESPONSES, Format.CLAUDE}).map(pair -> DynamicTest.dynamicTest(
                "请求基准快照 " + pair[0] + " → " + pair[1], () -> {
                    JsonObject request = resource("request/" + pair[0].wireName() + ".json").getAsJsonObject();
                    JsonElement expected = resource("golden/request/" + Connect.converterId(pair[0], pair[1]) + ".golden.json");
                    ConversionResult actual = kit.convertRequest(pair[0], pair[1], request, context());
                    assertEquals(expected, actual.value());
                    assertTrue(actual.diagnostics().isEmpty(), actual.diagnostics().toString());
                }));
    }
    @TestFactory Stream<DynamicTest> requestMatrix() {
        List<DynamicTest> tests = new ArrayList<>();
        Map<Format, JsonObject> inputs = Map.of(
                Format.OPENAI_CHAT, resource("request/openai.json").getAsJsonObject(),
                Format.OPENAI_RESPONSES, resource("request/openai_responses.json").getAsJsonObject(),
                Format.CLAUDE, Json.object("""
                    {"model":"claude-test","max_tokens":1024,"messages":[{"role":"user","content":"你好"}],
                    "tools":[{"name":"weather","input_schema":{"type":"object","properties":{}}}],"tool_choice":{"type":"auto"}}
                    """),
                Format.GEMINI, Json.object("""
                    {"contents":[{"role":"user","parts":[{"text":"你好"}]}],"generationConfig":{"maxOutputTokens":1024},
                    "tools":[{"functionDeclarations":[{"name":"weather","parameters":{"type":"object","properties":{}}}]}]}
                    """));
        for (Format from : Format.values()) for (Format to : Format.values()) tests.add(DynamicTest.dynamicTest(from + " → " + to, () -> {
            JsonObject input = inputs.get(from).deepCopy(), before = input.deepCopy();
            ConversionResult result = kit.convertRequest(from, to, input, context());
            assertEquals(before, input);
            assertEquals(from, result.from()); assertEquals(to, result.to());
            String key = switch (to) { case OPENAI_CHAT, CLAUDE -> "messages"; case OPENAI_RESPONSES -> "input"; case GEMINI -> "contents"; };
            assertFalse(Json.a(result.value(), key).isEmpty(), result.json());
            if (from != to) {
                assertFalse(result.steps().isEmpty()); assertTrue(result.value().has("tools"));
            }
        }));
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> responseMatrixAndUsage() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Format from : Format.values()) for (Format to : Format.values()) tests.add(DynamicTest.dynamicTest(from + " → " + to, () -> {
            JsonObject input = resource("response/" + from.wireName() + ".json").getAsJsonObject(), before = input.deepCopy();
            ConversionResult result = kit.convertResponse(from, to, input, context());
            assertEquals(before, input); assertFalse(result.stream());
            assertTrue(result.json().contains("The answer is 42.")); assertTrue(result.json().contains("get_weather"));
            assertEquals(from, result.usage().source());
            assertEquals(from == Format.CLAUDE ? 15 : 10, result.usage().inputTokens());
            assertEquals(from == Format.GEMINI ? 7 : 5, result.usage().outputTokens());
            assertEquals(Json.o(input, from == Format.GEMINI ? "usageMetadata" : "usage"), result.usage().raw());
            if (to == Format.CLAUDE) assertEquals("tool_use", Json.s(result.value(), "stop_reason"));
            if (to == Format.OPENAI_RESPONSES) assertEquals("completed", Json.s(result.value(), "status"));
        }));
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> streamMatrix() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Format from : Format.values()) for (Format to : Format.values()) tests.add(DynamicTest.dynamicTest(from + " → " + to, () -> {
            StreamOptions options = new StreamOptions(); options.id = "resp_fixed"; options.model = "test"; options.emitSequenceNumber = true;
            ResponseStreamState state = kit.newResponseStream(from, to, options, context());
            List<ConversionResult> events = new ArrayList<>();
            for (JsonElement event : resource("stream/" + from.wireName() + ".json").getAsJsonArray()) events.addAll(state.accept(event.getAsJsonObject()));
            events.addAll(state.finish());
            assertEquals(4, state.usage().inputTokens()); assertEquals(2, state.usage().outputTokens()); assertEquals(6, state.usage().totalTokens());
            assertTrue(state.finalized()); assertTrue(state.finish().isEmpty());
            assertThrows(IllegalStateException.class, () -> state.accept(Json.obj()));
            StringBuilder all = new StringBuilder(); long sequence = 0;
            for (ConversionResult event : events) {
                assertTrue(event.stream()); assertEquals(from, event.from()); assertEquals(to, event.to());
                all.append(event.json());
                if (to == Format.OPENAI_RESPONSES && from != to) assertEquals(sequence++, Json.n(event.value(), "sequence_number"));
            }
            assertTrue(all.toString().contains("Hello")); assertTrue(all.toString().contains("world"));
            assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
            if (from != to) {
                String type = Json.s(events.get(events.size() - 1).value(), "type");
                if (to == Format.CLAUDE) assertEquals("message_stop", type);
                if (to == Format.OPENAI_RESPONSES) {
                    assertEquals("response.completed", type);
                    JsonObject response = Json.o(events.get(events.size() - 1).value(), "response");
                    assertEquals("Hello world", Json.s(Json.o(Json.a(Json.o(Json.a(response, "output").get(0)), "content").get(0)), "text"));
                }
            }
        }));
        return tests.stream();
    }
    @Test void routeMetadataAndIds() {
        JsonObject input = resource("response/claude.json").getAsJsonObject();
        ConversionResult result = kit.convertResponseById("claude_to_gemini", input, context());
        assertEquals(2, result.steps().size()); assertEquals(ConversionResult.Quality.DISCOURAGED, result.quality());
        assertEquals(Format.OPENAI_CHAT, result.steps().get(0).to());
        assertEquals(ConversionResult.Quality.GOOD, kit.convertResponseById("openai_to_openai_responses", resource("response/openai.json").getAsJsonObject(), null).quality());
        assertThrows(ConversionException.class, () -> kit.convertResponseById("missing", input, null));
        assertThrows(ConversionException.class, () -> kit.convertRequestVia(Format.CLAUDE, Format.GEMINI, input, null, List.of(Format.CLAUDE)));
        assertThrows(ConversionException.class, () -> kit.convertRequestVia(Format.CLAUDE, Format.GEMINI, input, null, List.of(Format.CLAUDE, Format.CLAUDE, Format.GEMINI)));
        assertNotNull(kit.newResponseStreamById("openai_to_claude", null, null));
    }
    @Test void mediaResolverRequiredAndDataUrl() {
        Connect noResolver = new Connect();
        JsonObject request = resource("request/openai.json").getAsJsonObject();
        assertThrows(ConversionException.class, () -> noResolver.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, request, context()));
        JsonObject image = Json.o(Json.a(Json.o(Json.a(request, "messages").get(1)), "content").get(1));
        image.add("image_url", Json.obj("url", "data:image/png;base64,aGVsbG8="));
        assertTrue(noResolver.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, request, context()).json().contains("aGVsbG8="));
        assertEquals("image/png", MediaResolver.decodeDataUrl("data:image/png;base64,aGVsbG8=").mimeType());
        assertThrows(ConversionException.class, () -> MediaResolver.decodeDataUrl("data:text/plain,hello"));
        assertThrows(IllegalArgumentException.class, () -> new MediaResolver.MediaData("%%%invalid", "image/png"));
    }
    @Test void claudeRequiresValidMaxTokens() {
        JsonObject input = Json.object("{\"model\":\"claude-test\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, input, null));
        assertEquals(4096, Json.n(kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, input, context()).value(), "max_tokens"));
        ConversionContext context = context(); context.options.claudeDefaultMaxTokens = model -> 0;
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, input, context));
        Json.put(input, "max_tokens", 0);
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, input, context()));
    }
    @Test void lossPolicyRequestOnly() {
        JsonObject input = Json.object("{\"model\":\"test\",\"input\":\"hi\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"computer_use_preview\"}]}");
        ConversionContext context = context();
        ConversionResult allow = kit.convertRequest(Format.OPENAI_RESPONSES, Format.CLAUDE, input, context);
        assertEquals("hosted_tool_omitted", allow.diagnostics().get(0).code());
        context.options.toolLossPolicy = Options.LossPolicy.SAFE;
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_RESPONSES, Format.CLAUDE, input, context));
        JsonObject chat = Json.object("{\"model\":\"test\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1024,\"seed\":1}");
        assertFalse(kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, chat, context).diagnostics().isEmpty());
        context.options.toolLossPolicy = Options.LossPolicy.STRICT;
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, chat, context));
        JsonObject response = resource("response/openai_responses.json").getAsJsonObject();
        Json.a(response, "output").add(Json.obj("type", "computer_call"));
        assertFalse(kit.convertResponse(Format.OPENAI_RESPONSES, Format.CLAUDE, response, context).diagnostics().isEmpty());
    }
    @Test void customAndNamespaceRoundTrip() {
        ConversionContext ctx = context();
        JsonObject request = Json.object("""
                {"model":"test","input":[{"type":"custom_tool_call","call_id":"call_x","name":"run","input":"print(1)"},
                {"type":"function_call","call_id":"call_y","namespace":"math","name":"sum","arguments":"{}"}],
                "tools":[{"type":"custom","name":"run"},{"type":"namespace","name":"math","tools":[
                {"type":"function","name":"sum","parameters":{"type":"object","properties":{"a":{"type":"number"}}}}]}]}
                """);
        Json.put(Json.o(Json.a(request, "input").get(1)), "arguments", "{\"a\":1}");
        ConversionResult converted = kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, request, ctx);
        JsonArray messages = Json.a(converted.value(), "messages");
        JsonObject first = Json.o(Json.a(Json.o(messages.get(0)), "tool_calls").get(0));
        JsonObject second = Json.o(Json.a(Json.o(messages.get(1)), "tool_calls").get(0));
        assertTrue(Json.s(Json.o(first, "function"), "name").startsWith("rk_"));
        assertEquals("{\"input\":\"print(1)\"}", Json.s(Json.o(first, "function"), "arguments"));
        JsonObject response = Json.obj("id", "c", "model", "test", "choices", Json.arr(Json.obj("index", 0, "message",
                Json.obj("role", "assistant", "content", "", "tool_calls", Json.arr(first, second)), "finish_reason", "tool_calls")));
        JsonArray output = Json.a(kit.convertResponse(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, response, ctx).value(), "output");
        JsonObject custom = Json.o(output.get(1)), function = Json.o(output.get(2));
        assertEquals("custom_tool_call", Json.s(custom, "type")); assertEquals("print(1)", Json.s(custom, "input"));
        assertEquals("math", Json.s(function, "namespace")); assertEquals("sum", Json.s(function, "name"));
    }
    @Test void webSearchHookAndSafetySettings() {
        ConversionContext ctx = context();
        ctx.options.webSearchEncoder = (format, config) -> Json.obj("googleSearch", Json.obj("custom", true));
        ctx.options.geminiSafetySetting = category -> "OFF";
        ctx.options.geminiSupportsImages = model -> true;
        JsonObject input = Json.object("{\"model\":\"gemini-test\",\"input\":\"hi\",\"tools\":[{\"type\":\"web_search\"}]}");
        JsonObject output = kit.convertRequest(Format.OPENAI_RESPONSES, Format.GEMINI, input, ctx).value();
        assertEquals(4, Json.a(output, "safetySettings").size());
        assertEquals(2, Json.a(Json.o(output, "generationConfig"), "responseModalities").size());
        assertTrue(Json.b(Json.o(Json.o(Json.a(output, "tools").get(0)), "googleSearch"), "custom"));
        ctx.options.webSearchEncoder = null;
        assertTrue(kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, input, ctx).value().has("web_search_options"));
    }
    @Test void reasoningExactBudgetAndSuffixes() {
        assertEquals("claude-sonnet-4-5", Reasoning.parseModelSuffix("claude-sonnet-4-5-thinking-4096", true).model());
        assertEquals(4096, Reasoning.parseModelSuffix("claude-sonnet-4-5-thinking-4096", true).intent().budget());
        assertEquals("claude-test-high", Reasoning.parseModelSuffix("claude-test-high", false).model());
        assertEquals("claude-test", Reasoning.parseModelSuffix("claude-test-nothinking", true).model());
        assertEquals("none", Reasoning.parseModelSuffix("gemini-test-none", true).intent().effort());
        assertThrows(ConversionException.class, () -> Reasoning.parseModelSuffix("gemini-test-thinking-abc", true));
        assertEquals("gpt-test", Reasoning.parseOpenAIEffortSuffix("gpt-test-high", null).model());
        assertEquals("gpt-test-max", Reasoning.parseOpenAIEffortSuffix("gpt-test-max", model -> true).model());
        assertEquals("qwen-max", Reasoning.parseOpenAIEffortSuffix("qwen-max", null).model());
        assertEquals("opaque@version", Reasoning.parseModifiers("opaque@version@thinking:4096@effort:high").model());
        ConversionContext context = context(); context.reasoning = new Reasoning.Intent("enabled", "", 4096, true);
        JsonObject input = Json.object("{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":2048,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        ConversionResult result = kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, input, context);
        assertEquals(4097, Json.n(result.value(), "max_tokens"));
        assertEquals(4096, Json.n(Json.o(result.value(), "thinking"), "budget_tokens"));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("claude_max_tokens_raised")));
        Json.put(input, "model", "gemini-3-pro");
        ConversionResult gemini = kit.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, input, context);
        assertTrue(Json.o(Json.o(gemini.value(), "generationConfig"), "thinkingConfig").has("thinkingLevel"));
        assertEquals(4096, context.reasoning.budget());
    }
    @Test void usageNonZeroMergePreservesCacheAndBilling() {
        Usage first = Usage.from(Format.CLAUDE, Json.object("{\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":4,\"cache_creation\":{\"ephemeral_5m_input_tokens\":1,\"ephemeral_1h_input_tokens\":3}}}"));
        Usage second = Usage.from(Format.CLAUDE, Json.object("{\"usage\":{\"input_tokens\":0,\"output_tokens\":5}}"));
        Usage merged = first.mergeNonZero(second);
        assertEquals(17, merged.inputTokens()); assertEquals(5, merged.outputTokens()); assertEquals(22, merged.totalTokens());
        assertEquals(1, merged.cacheWrite5mTokens()); assertEquals(3, merged.cacheWrite1hTokens());
        JsonObject raw = merged.raw(); Json.put(raw, "input_tokens", 999); assertNotEquals(raw, merged.raw());
        assertEquals(10, Json.n(merged.toJson(Format.CLAUDE), "input_tokens"));
        assertEquals(17, Json.n(merged.toJson(Format.OPENAI_CHAT), "prompt_tokens"));
        assertEquals(5, Json.n(merged.toJson(Format.OPENAI_RESPONSES), "output_tokens"));
    }
    @Test void strictJsonAndErrors() {
        for (String invalid : List.of("{a:1}", "{\"a\":1,}", "{\"a\":1} garbage", "", "/*x*/{}"))
            assertThrows(RuntimeException.class, () -> Json.object(invalid));
        assertThrows(IllegalArgumentException.class, () -> Json.object("[]"));
        assertEquals(Format.CLAUDE, Format.parse("anthropic"));
        assertThrows(IllegalArgumentException.class, () -> Format.parse("missing"));
        assertEquals("length", FinishReasons.fromClaude("pause_turn"));
        assertEquals("refusal", FinishReasons.toClaude("content_filter"));
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, Json.obj(), null));
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES,
                Json.object("{\"model\":\"x\",\"n\":2,\"messages\":[]}"), null));
    }
}
