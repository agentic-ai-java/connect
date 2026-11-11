package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Wire-contract regressions for requests, responses, and streaming conversions. */
class ProtocolCompatibilityTest {
    private final Connect connect = new Connect();

    private static JsonObject chat() {
        return Json.object("""
                {"model":"gemini-4-argon","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}]}
                """);
    }

    private JsonObject request(Format target, JsonObject input) {
        return connect.convertRequest(Format.OPENAI_CHAT, target, input, null).value();
    }

    @ParameterizedTest
    @CsvSource({"low,low", "high,high", "auto,auto", "original,original", "' HIGH ',high", "bogus,auto", "'',auto"})
    void responsesPreservesAndNormalizesImageDetail(String hint, String expected) {
        JsonObject input = chat();
        Json.o(Json.a(input, "messages").get(0)).add("content", Json.arr(Json.obj("type", "image_url",
                "image_url", Json.obj("url", "https://example.test/image.png", "detail", hint))));
        JsonObject before = input.deepCopy();
        JsonObject output = request(Format.OPENAI_RESPONSES, input);
        JsonObject image = Json.o(Json.a(Json.o(Json.a(output, "input").get(0)), "content").get(0));
        assertEquals(expected, Json.s(image, "detail"));
        assertEquals("https://example.test/image.png", Json.s(image, "image_url"));
        assertEquals(before, input);
    }

    @Test void stringImageUrlsAndClaudeToolResultImagesHaveExplicitDetail() {
        JsonObject input = chat();
        Json.o(Json.a(input, "messages").get(0)).add("content", Json.arr(Json.obj("type", "image_url",
                "image_url", "https://example.test/image.png")));
        assertTrue(Json.stringify(request(Format.OPENAI_RESPONSES, input)).contains("\"detail\":\"auto\""));
        JsonObject claude = Json.object("""
                {"model":"gpt-test","max_tokens":1024,"messages":[
                  {"role":"user","content":[{"type":"image","source":{"type":"url","url":"https://example.test/a.png"}}]},
                  {"role":"assistant","content":[{"type":"tool_use","id":"screen","name":"screenshot","input":{}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"screen","content":[
                    {"type":"text","text":"Screenshot"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}}
                  ]}]}]}
                """);
        JsonArray items = Json.a(connect.convertRequest(Format.CLAUDE, Format.OPENAI_RESPONSES, claude, null).value(), "input");
        JsonObject userImage = Json.o(Json.a(Json.o(items.get(0)), "content").get(0));
        assertEquals("auto", Json.s(userImage, "detail"));
        JsonObject result = items.asList().stream().map(Json::o)
                .filter(x -> Json.s(x, "type").equals("function_call_output")).findFirst().orElseThrow();
        JsonArray output = Json.a(result, "output");
        assertEquals("input_text", Json.s(Json.o(output.get(0)), "type"));
        assertEquals("auto", Json.s(Json.o(output.get(1)), "detail"));
        assertEquals("data:image/png;base64,aGVsbG8=", Json.s(Json.o(output.get(1)), "image_url"));
        JsonObject responses = Json.obj("model", "gpt-test", "input", items);
        JsonObject chat = connect.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, responses, null).value();
        JsonObject roundTrip = request(Format.OPENAI_RESPONSES, chat);
        JsonObject replayed = Json.a(roundTrip, "input").asList().stream().map(Json::o)
                .filter(x -> Json.s(x, "type").equals("function_call_output")).findFirst().orElseThrow();
        assertEquals(output, replayed.get("output"));
    }

    @Test void geminiImageConfigurationUsesGoogleOverrideWithoutMutatingSource() {
        JsonObject input = chat();
        input.add("imageConfig", Json.obj("imageSize", "1K", "aspectRatio", "1:1"));
        assertEquals(Json.o(input, "imageConfig"), Json.o(Json.o(request(Format.GEMINI, input), "generationConfig"), "imageConfig"));
        input.add("extra_body", Json.obj("google", Json.obj("image_config", Json.obj())));
        assertEquals("1K", Json.s(Json.o(Json.o(request(Format.GEMINI, input), "generationConfig"), "imageConfig"), "imageSize"));
        Json.o(input, "extra_body").add("google", Json.obj("image_config", Json.obj("image_size", "4K", "aspect_ratio", "16:9")));
        JsonObject before = input.deepCopy();
        JsonObject output = request(Format.GEMINI, input);
        assertEquals(Json.obj("imageSize", "4K", "aspectRatio", "16:9"), Json.o(Json.o(output, "generationConfig"), "imageConfig"));
        assertFalse(output.has("imageConfig"));
        assertFalse(output.has("extra_body"));
        assertEquals(before, input);
        assertFalse(Json.o(request(Format.GEMINI, chat()), "generationConfig").has("imageConfig"));
        JsonObject roundTrip = connect.convertRequest(Format.GEMINI, Format.OPENAI_CHAT, output, context("gemini-4-argon")).value();
        assertEquals(Json.o(Json.o(output, "generationConfig"), "imageConfig"), Json.o(roundTrip, "imageConfig"));
    }

    @Test void unsupportedExtensionsRemainObservable() {
        JsonObject input = chat();
        input.add("imageConfig", Json.obj("imageSize", "4K"));
        input.add("extra_body", Json.obj("unknown", true, "google", Json.obj("unknown", true)));
        assertEquals(2, connect.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, input, null).diagnostics().size());
        assertEquals(2, connect.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, input, null).diagnostics().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"END\"", "\"\"", "[\"END\",\"\"]", "[\"END\",7,null,false,{},\"\"]", "17", "{}", "[null,3]", "null", "[]"})
    void stopSequencesOnlyContainStrings(String stop) {
        JsonObject input = chat(); input.add("stop", Json.parse(stop));
        JsonArray expected = new JsonArray();
        JsonElement raw = input.get("stop");
        for (JsonElement item : raw.isJsonArray() ? raw.getAsJsonArray() : Json.arr(raw))
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) expected.add(item);
        for (Format target : List.of(Format.CLAUDE, Format.GEMINI)) {
            JsonObject result = request(target, input);
            JsonObject parent = target == Format.CLAUDE ? result : Json.o(result, "generationConfig");
            String key = target == Format.CLAUDE ? "stop_sequences" : "stopSequences";
            if (expected.isEmpty()) assertFalse(parent.has(key));
            else assertEquals(expected, Json.a(parent, key));
        }
    }

    @Test void invalidStopsCanBeRejectedByStrictPolicy() {
        JsonObject input = chat(); input.add("stop", Json.arr(Json.parse("false")));
        ConversionContext ctx = new ConversionContext(); ctx.options.toolLossPolicy = Options.LossPolicy.STRICT;
        assertThrows(ConversionException.class, () -> connect.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, input, ctx));
    }

    @Test void openAIPromptCacheControlsRoundTripAndWarnForOtherProtocols() {
        JsonObject input = chat();
        input.addProperty("prompt_cache_key", "session-123");
        input.addProperty("prompt_cache_retention", "24h");
        input.add("prompt_cache_options", Json.obj("ttl", "12h"));
        ConversionResult responses = connect.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, input, null);
        assertTrue(responses.diagnostics().isEmpty());
        ConversionResult back = connect.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, responses.value(), null);
        assertTrue(back.diagnostics().isEmpty());
        for (String key : List.of("prompt_cache_key", "prompt_cache_options", "prompt_cache_retention")) {
            assertEquals(input.get(key), responses.value().get(key));
            assertEquals(input.get(key), back.value().get(key));
        }
        for (Format target : List.of(Format.CLAUDE, Format.GEMINI)) {
            ConversionResult result = connect.convertRequest(Format.OPENAI_CHAT, target, input, null);
            assertEquals(3, result.diagnostics().stream().filter(d -> d.code().equals("prompt_cache_control_omitted")).count());
            assertFalse(result.value().has("prompt_cache_options"));
        }
    }

    private static ConversionContext context(String model) {
        ConversionContext ctx = new ConversionContext(); ctx.upstreamModel = model; return ctx;
    }

    @ParameterizedTest
    @CsvSource({"minimal,low", "none,low", "low,low", "medium,medium", "high,high", "xhigh,high", "max,high"})
    void argonUsesThinkingLevels(String effort, String expected) {
        JsonObject input = chat(); input.addProperty("reasoning_effort", effort);
        JsonObject thinking = Json.o(Json.o(request(Format.GEMINI, input), "generationConfig"), "thinkingConfig");
        assertEquals(expected, Json.s(thinking, "thinkingLevel"));
        assertFalse(thinking.has("thinkingBudget"));
    }

    @Test void argonDefaultsAndBudgetsRespectRoutedModel() {
        for (String model : List.of("gemini-4-argon", "gemini-4-argon-cyber-permissive")) {
            for (Integer budget : new Integer[] {null, -1, 1024, 8192, 20000}) {
                ConversionContext ctx = context(model);
                ctx.reasoning = new Reasoning.Intent("enabled", "", budget, true);
                JsonObject output = connect.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, chat(), ctx).value();
                String expected = budget == null || budget == -1 ? model.endsWith("permissive") ? "high" : "medium"
                        : budget == 1024 ? "low" : budget == 8192 ? "medium" : "high";
                JsonObject thinking = Json.o(Json.o(output, "generationConfig"), "thinkingConfig");
                assertEquals(expected, Json.s(thinking, "thinkingLevel"));
                assertTrue(Json.b(thinking, "includeThoughts"));
            }
        }
        assertFalse(Json.o(request(Format.GEMINI, chat()), "generationConfig").has("thinkingConfig"));
        ConversionContext disabled = context("gemini-4-argon");
        disabled.reasoning = new Reasoning.Intent("disabled", "", -1, false);
        ConversionResult result = connect.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, chat(), disabled);
        assertEquals("low", Json.s(Json.o(Json.o(result.value(), "generationConfig"), "thinkingConfig"), "thinkingLevel"));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("gemini_effort_mapped")));
    }

    @Test void argonNativeFieldsAreCopiedAndCrossProtocolLossIsDiagnosed() {
        JsonObject input = Json.object("""
                {"continuation_token":{"opaque":"token"},"generationConfig":{"mediaProcessing":{"mode":"full"}},
                 "contents":[{"role":"user","parts":[{"text":"Hello","media_processing":{"mode":"full"}}]}]}
                """);
        JsonObject before = input.deepCopy();
        ConversionResult nativeResult = connect.convertRequest(Format.GEMINI, Format.GEMINI, input, null);
        assertEquals(before, nativeResult.value()); assertNotSame(input, nativeResult.value());
        ConversionResult converted = connect.convertRequest(Format.GEMINI, Format.OPENAI_CHAT, input, context("gemini-4-argon"));
        assertEquals(3, converted.diagnostics().size());
        assertEquals(before, input);
    }

    @Test void continuationIsIncompleteForFullResponsesAndStreams() {
        JsonObject input = Json.object("""
                {"modelVersion":"gemini-4-argon","candidates":[{"index":0,"finishReason":"CONTINUATION",
                 "continuationToken":"opaque","content":{"role":"model","parts":[{"text":"Partial answer"}]}}]}
                """);
        assertEquals(input, connect.convertResponse(Format.GEMINI, Format.GEMINI, input, null).value());
        for (Format target : List.of(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, Format.CLAUDE)) {
            ConversionResult response = connect.convertResponse(Format.GEMINI, target, input, null);
            assertTrue(response.diagnostics().stream().anyMatch(d -> d.code().equals("continuation_token_omitted")));
            ResponseStreamState stream = connect.newResponseStream(Format.GEMINI, target, null, null);
            List<ConversionResult> events = new ArrayList<>(stream.accept(input)); events.addAll(stream.finish());
            String serialized = events.stream().map(ConversionResult::json).reduce("", String::concat);
            switch (target) {
                case OPENAI_CHAT -> {
                    assertEquals("length", Json.s(Json.o(Json.a(response.value(), "choices").get(0)), "finish_reason"));
                    assertTrue(serialized.contains("\"finish_reason\":\"length\""));
                }
                case CLAUDE -> {
                    assertEquals("max_tokens", Json.s(response.value(), "stop_reason"));
                    assertTrue(serialized.contains("\"stop_reason\":\"max_tokens\""));
                }
                case OPENAI_RESPONSES -> {
                    assertEquals("incomplete", Json.s(response.value(), "status"));
                    assertEquals("max_output_tokens", Json.s(Json.o(response.value(), "incomplete_details"), "reason"));
                    assertTrue(serialized.contains("\"type\":\"response.incomplete\""));
                }
                default -> fail("Unexpected target");
            }
        }
    }

    @Test void adjacentAssistantCallsTextAndLiteralEllipsesSurviveClaudeConversion() {
        JsonObject input = Json.object("""
                {"model":"claude-test","max_tokens":1024,"messages":[
                  {"role":"assistant","content":"Checking","tool_calls":[{"id":"a","type":"function","function":{"name":"first","arguments":"{}"}}]},
                  {"role":"assistant","content":"...","tool_calls":[{"id":"b","type":"function","function":{"name":"second","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"a","content":"one"},{"role":"tool","tool_call_id":"b","content":"two"}]}
                """);
        JsonArray messages = Json.a(request(Format.CLAUDE, input), "messages");
        assertEquals("Checking", Json.s(Json.o(Json.a(Json.o(messages.get(0)), "content").get(0)), "text"));
        assertEquals("...", Json.s(Json.o(Json.a(Json.o(messages.get(1)), "content").get(0)), "text"));
        assertEquals("a", Json.s(Json.o(Json.a(Json.o(messages.get(0)), "content").get(1)), "id"));
        assertEquals("b", Json.s(Json.o(Json.a(Json.o(messages.get(1)), "content").get(1)), "id"));
    }

    @Test void geminiAssistantTextAndCallsSurviveInRequestsResponsesAndStreams() {
        JsonObject content = Json.object("""
                {"role":"model","parts":[{"text":"Checking now"},{"functionCall":{"id":"a","name":"lookup","args":{"key":1}}}]}
                """);
        JsonObject request = connect.convertRequest(Format.GEMINI, Format.OPENAI_CHAT,
                Json.obj("contents", Json.arr(content)), context("gemini-4-argon")).value();
        JsonObject message = Json.o(Json.a(request, "messages").get(0));
        assertEquals("Checking now", Json.s(Json.o(Json.a(message, "content").get(0)), "text"));
        assertEquals(1, Json.a(message, "tool_calls").size());
        JsonObject response = Json.obj("candidates", Json.arr(Json.obj("index", 0, "content", content, "finishReason", "STOP")));
        JsonObject converted = connect.convertResponse(Format.GEMINI, Format.OPENAI_CHAT, response, null).value();
        message = Json.o(Json.o(Json.a(converted, "choices").get(0)), "message");
        assertEquals("Checking now", Json.s(message, "content")); assertEquals(1, Json.a(message, "tool_calls").size());
        ResponseStreamState stream = connect.newResponseStream(Format.GEMINI, Format.OPENAI_CHAT, null, null);
        List<ConversionResult> events = new ArrayList<>(stream.accept(response)); events.addAll(stream.finish());
        String wire = events.stream().map(ConversionResult::json).reduce("", String::concat);
        assertTrue(wire.contains("Checking now")); assertTrue(wire.contains("lookup"));
    }

    @Test void toolNumbersAndSchemaOrderSurviveSerialization() {
        String arguments = "{\"large\":9007199254740993,\"decimal\":0.1234567890123456789,\"exp\":1e-100}";
        JsonObject response = Json.obj("choices", Json.arr(Json.obj("message", Json.obj("role", "assistant", "content", "Checking",
                "tool_calls", Json.arr(Json.obj("id", "a", "function", Json.obj("name", "lookup", "arguments", arguments)))), "finish_reason", "tool_calls")));
        JsonObject converted = connect.convertResponse(Format.OPENAI_CHAT, Format.CLAUDE, response, null).value();
        assertTrue(Json.stringify(converted).contains(arguments));
        JsonObject back = connect.convertResponse(Format.CLAUDE, Format.OPENAI_CHAT, converted, null).value();
        JsonObject message = Json.o(Json.o(Json.a(back, "choices").get(0)), "message");
        assertEquals(arguments, Json.s(Json.o(Json.o(Json.a(message, "tool_calls").get(0)), "function"), "arguments"));
        JsonObject input = chat();
        JsonObject schema = Json.object("""
                {"type":"object","properties":{"reasoning":{"type":"string"},"answer":{"type":"string"}},"required":["reasoning","answer"]}
                """);
        input.add("tools", Json.arr(Json.obj("type", "function", "function", Json.obj("name", "lookup", "parameters", schema))));
        input.add("response_format", Json.obj("type", "json_schema", "json_schema", Json.obj("name", "result", "schema", schema)));
        for (Format target : List.of(Format.CLAUDE, Format.GEMINI, Format.OPENAI_RESPONSES)) {
            String wire = Json.stringify(request(target, input));
            assertTrue(wire.contains("\"properties\":{\"reasoning\":{\"type\":\"string\"},\"answer\":{\"type\":\"string\"}}"));
        }
    }

    @Test void announcedReasoningItemsAlwaysHaveAnEmptySummaryArray() {
        ResponseStreamState stream = connect.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, null, null);
        List<ConversionResult> events = stream.accept(Json.obj("choices", Json.arr(Json.obj("index", 0, "delta",
                Json.obj("reasoning_content", "Consider", "content", "Answer")))));
        JsonObject item = events.stream().map(ConversionResult::value)
                .filter(e -> Json.s(e, "type").equals("response.output_item.added"))
                .map(e -> Json.o(e, "item")).filter(e -> Json.s(e, "type").equals("reasoning")).findFirst().orElseThrow();
        assertTrue(item.has("summary")); assertEquals(Json.arr(), item.get("summary"));
        assertTrue(events.stream().anyMatch(e -> Json.s(e.value(), "type").equals("response.output_text.delta")));
        stream.finish();
    }
}
