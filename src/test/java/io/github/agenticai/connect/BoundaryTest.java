package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundaryTest {
    private final Connect kit = new Connect();
    private static JsonObject chat() { return Json.object("{\"model\":\"test\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"); }
    @Test void namedToolChoiceAcrossProtocols() {
        JsonObject responses = Json.object("{\"model\":\"test\",\"input\":\"hi\",\"max_output_tokens\":1024,\"tool_choice\":{\"type\":\"function\",\"name\":\"weather\"},\"tools\":[{\"type\":\"function\",\"name\":\"weather\",\"parameters\":{\"type\":\"object\"}}]}");
        JsonObject claude = kit.convertRequest(Format.OPENAI_RESPONSES, Format.CLAUDE, responses, null).value();
        assertEquals("weather", Json.s(Json.o(claude, "tool_choice"), "name"));
        JsonObject gemini = kit.convertRequest(Format.OPENAI_RESPONSES, Format.GEMINI, responses, null).value();
        assertEquals("weather", Json.a(Json.o(Json.o(gemini, "toolConfig"), "functionCallingConfig"), "allowedFunctionNames").get(0).getAsString());
        JsonObject request = chat(); request.add("tool_choice", Json.obj("type", "function", "function", Json.obj("name", "weather")));
        assertEquals("weather", Json.s(Json.o(kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, request, null).value(), "tool_choice"), "name"));
    }
    @Test void structuredOutputAndAdaptiveThinkingCoexist() {
        JsonObject request = chat(); Json.put(request, "model", "claude-opus-4-6");
        request.add("response_format", Json.obj("type", "json_schema", "json_schema", Json.obj("name", "a", "schema", Json.obj("type", "object"))));
        Json.put(request, "reasoning_effort", "high");
        JsonObject output = kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, request, null).value();
        assertEquals("high", Json.s(Json.o(output, "output_config"), "effort"));
        assertTrue(Json.o(output, "output_config").has("format"));
        assertEquals("adaptive", Json.s(Json.o(output, "thinking"), "type"));
    }
    @Test void responseFormatRoundTrip() {
        JsonObject request = chat(); request.add("response_format", Json.obj("type", "json_schema", "json_schema",
                Json.obj("name", "answer", "strict", true, "schema", Json.obj("type", "object"))));
        ConversionResult responses = kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, request, null);
        assertEquals(request.get("response_format"), kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, responses.value(), null).value().get("response_format"));
        assertTrue(Json.o(Json.o(kit.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, request, null).value(), "generationConfig"), "responseJsonSchema").has("type"));
    }
    @Test void claudeTextDocumentBecomesBase64File() {
        JsonObject request = Json.object("{\"model\":\"test\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"document\",\"source\":{\"type\":\"text\",\"data\":\"hello\"}}]}]}");
        JsonObject output = kit.convertRequest(Format.CLAUDE, Format.OPENAI_CHAT, request, null).value();
        JsonObject file = Json.o(Json.o(Json.a(Json.o(Json.a(output, "messages").get(0)), "content").get(0)), "file");
        assertEquals("data:text/plain;base64,aGVsbG8=", Json.s(file, "file_data"));
    }
    @Test void unsupportedAudioIsDiagnosedAndSafePolicyRejects() {
        JsonObject request = chat();
        Json.o(Json.a(request, "messages").get(0)).add("content", Json.arr(Json.obj("type", "input_audio", "input_audio", Json.obj("data", "aGVsbG8=", "format", "wav"))));
        ConversionResult result = kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, request, null);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("unsupported_media_type")));
        ConversionContext context = new ConversionContext(); context.options.toolLossPolicy = Options.LossPolicy.SAFE;
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, request, context));
        assertTrue(kit.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, request, null).json().contains("audio/wav"));
    }
    @Test void unknownFieldsAndToolArtifactsAreDiagnosed() {
        JsonObject request = chat(); Json.put(request, "opaque", 123);
        assertTrue(kit.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, request, null).diagnostics().stream().anyMatch(d -> d.path().equals("opaque")));
        JsonObject responses = Json.obj("model", "test", "input", "hi", "opaque", true);
        assertFalse(kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, responses, null).diagnostics().isEmpty());
        JsonObject response = Json.object("{\"id\":\"r\",\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\"}],\"status\":\"completed\"}");
        assertTrue(kit.convertResponse(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, response, null).diagnostics().stream().anyMatch(d -> d.code().equals("hosted_tool_artifact_omitted")));
    }
    @Test void errorResponsesRemainErrors() {
        JsonObject error = Json.obj("type", "error", "error", Json.obj("type", "invalid_request_error", "message", "bad"));
        for (Format target : Format.values()) {
            JsonObject converted = kit.convertResponse(Format.CLAUDE, target, error, null).value();
            assertTrue(converted.has("error")); assertTrue(Json.s(Json.o(converted, "error"), "message").equals("bad"));
            if (target == Format.OPENAI_RESPONSES) assertEquals("failed", Json.s(converted, "status"));
        }
    }
    @Test void geminiToolResultsResolveMatchingNames() {
        JsonObject input = Json.object("""
                {"model":"test","contents":[{"role":"model","parts":[{"functionCall":{"id":"a","name":"weather","args":{}}}]},
                {"role":"user","parts":[{"functionResponse":{"id":"a","name":"weather","response":{"result":"sunny"}}}]}],
                "generationConfig":{"maxOutputTokens":100}}
                """);
        JsonObject chat = kit.convertRequest(Format.GEMINI, Format.OPENAI_CHAT, input, null).value();
        assertEquals("a", Json.s(Json.o(Json.a(chat, "messages").get(1)), "tool_call_id"));
        JsonObject gemini = kit.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, chat, null).value();
        assertTrue(gemini.toString().contains("sunny"));
    }
    @Test void multipleResponseCandidatesAndFinishReasons() {
        JsonObject response = Json.obj("id", "a", "model", "test", "choices", Json.arr(
                Json.obj("index", 0, "message", Json.obj("role", "assistant", "content", "a"), "finish_reason", "length"),
                Json.obj("index", 1, "message", Json.obj("role", "assistant", "content", "b"), "finish_reason", "stop")));
        assertEquals(2, Json.a(kit.convertResponse(Format.OPENAI_CHAT, Format.GEMINI, response, null).value(), "candidates").size());
        ConversionResult converted = kit.convertResponse(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, response, null);
        assertEquals("incomplete", Json.s(converted.value(), "status"));
        assertTrue(converted.diagnostics().stream().anyMatch(d -> d.code().equals("multiple_choices_omitted")));
    }
    @Test void customToolStreamRestoresInput() {
        ConversionContext context = new ConversionContext();
        JsonObject request = Json.obj("model", "test", "input", "hi", "tools", Json.arr(Json.obj("type", "custom", "name", "run")));
        JsonObject chat = kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, request, context).value();
        String name = Json.s(Json.o(Json.o(Json.a(chat, "tools").get(0)), "function"), "name");
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, null, context);
        state.accept(Json.obj("choices", Json.arr(Json.obj("index", 0, "delta", Json.obj("tool_calls", Json.arr(Json.obj("index", 0,
                "id", "call_a", "function", Json.obj("name", name, "arguments", "{\"input\":\"print(1)\"}"))))))));
        var results = state.finish();
        JsonArray output = Json.a(Json.o(results.get(results.size() - 1).value(), "response"), "output");
        assertEquals("custom_tool_call", Json.s(Json.o(output.get(0)), "type"));
        assertEquals("print(1)", Json.s(Json.o(output.get(0)), "input"));
    }
}
