package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderProfileTest {
    private final Connect kit = new Connect();
    private static JsonObject request() {
        return Json.object("""
                {"model":"client-alias","messages":[{"role":"user","content":"hi"}],"vendor_extension":{"keep":true}}
                """);
    }
    private static ConversionContext deepSeek(Options.LossPolicy policy) {
        Options options = new Options();
        options.providerProfile = ProviderProfile.deepSeekChat(); options.toolLossPolicy = policy;
        ConversionContext ctx = new ConversionContext(options); ctx.upstreamModel = "deepseek-flash";
        return ctx;
    }
    private ConversionResult adapt(JsonObject input, ConversionContext ctx) {
        return kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_CHAT, input, ctx);
    }
    private static boolean diagnostic(ConversionResult result, String code) {
        return result.diagnostics().stream().anyMatch(d -> d.code().equals(code));
    }
    @Test void identityPreservesExtensionsAndCopiesInput() {
        JsonObject input = request(), before = input.deepCopy();
        ConversionResult generic = adapt(input, new ConversionContext());
        assertEquals(before, generic.value()); assertNotSame(input, generic.value());
        ConversionResult result = adapt(input, deepSeek(Options.LossPolicy.STRICT));
        assertEquals("deepseek-flash", Json.s(result.value(), "model"));
        assertEquals(input.get("vendor_extension"), result.value().get("vendor_extension"));
        assertFalse(result.value().has("thinking")); assertTrue(result.diagnostics().isEmpty());
        assertEquals(before, input);
    }
    @Test void routedModelWinsWithAndWithoutProfile() {
        for (Format target : List.of(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, Format.CLAUDE)) {
            ConversionContext ctx = new ConversionContext(); ctx.upstreamModel = "routed-model";
            ctx.options.claudeDefaultMaxTokens = model -> { assertEquals("routed-model", model); return 100; };
            assertEquals("routed-model", Json.s(kit.convertRequest(Format.OPENAI_CHAT, target, request(), ctx).value(), "model"));
        }
        ConversionContext ctx = new ConversionContext(); ctx.upstreamModel = "gemini-3-pro";
        ctx.reasoning = new Reasoning.Intent("enabled", "high", null, null);
        JsonObject out = kit.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, request(), ctx).value();
        assertFalse(out.has("model"));
        assertEquals("high", Json.s(Json.o(Json.o(out, "generationConfig"), "thinkingConfig"), "thinkingLevel"));
    }
    @Test void customProfileRunsOnceAtFinalTargetIncludingIdentity() {
        AtomicInteger calls = new AtomicInteger();
        Options options = new Options();
        options.providerProfile = (body, adaptation) -> {
            calls.incrementAndGet(); assertEquals(Format.GEMINI, adaptation.from()); assertEquals(Format.CLAUDE, adaptation.to());
            assertTrue(body.has("messages")); assertNotNull(adaptation.reasoning());
            assertEquals("target-model", adaptation.context().upstreamModel);
            Json.put(body, "custom", true); adaptation.warning("custom", "custom", "Custom adapter executed");
        };
        ConversionContext ctx = new ConversionContext(options); ctx.upstreamModel = "target-model";
        JsonObject input = Json.object("""
                {"contents":[{"role":"user","parts":[{"text":"hi"}]}],
                 "generationConfig":{"maxOutputTokens":4096,"thinkingConfig":{"thinkingBudget":1024}}}
                """);
        ConversionResult result = kit.convertRequest(Format.GEMINI, Format.CLAUDE, input, ctx);
        assertEquals(1, calls.get()); assertEquals(2, result.steps().size()); assertTrue(Json.b(result.value(), "custom"));
        assertTrue(diagnostic(result, "custom"));
        options.providerProfile = (body, adaptation) -> calls.incrementAndGet();
        adapt(request(), ctx); assertEquals(2, calls.get());
    }
    @Test void deepSeekRequiresItsTargetAndRequiredFields() {
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES,
                request(), deepSeek(Options.LossPolicy.ALLOW)));
        for (String invalid : List.of("{}", "{\"model\":\"x\",\"messages\":[]}", "{\"model\":\"x\",\"messages\":{}}"))
            assertThrows(ConversionException.class, () -> adapt(Json.object(invalid), deepSeek(Options.LossPolicy.ALLOW)));
        ConversionContext ctx = deepSeek(Options.LossPolicy.ALLOW); ctx.upstreamModel = "";
        JsonObject input = request(); input.remove("model");
        assertThrows(ConversionException.class, () -> adapt(input, ctx));
    }
    @Test void nativeThinkingPreservesExtensionsAndMapsEffort() {
        for (String effort : List.of("minimal", "low", "medium", "high", "xhigh", "max", "ultra", "none")) {
            JsonObject input = request(); Json.put(input, "reasoning_effort", effort);
            input.add("thinking", Json.obj("type", "enabled", "extra", "kept"));
            ConversionResult result = adapt(input, deepSeek(Options.LossPolicy.ALLOW));
            assertEquals(switch (effort) { case "minimal" -> "low"; case "medium", "xhigh" -> "high"; case "ultra" -> "max"; default -> effort; },
                    Json.s(result.value(), "reasoning_effort"));
            assertEquals(effort.equals("none") ? "disabled" : "enabled", Json.s(Json.o(result.value(), "thinking"), "type"));
            assertEquals("kept", Json.s(Json.o(result.value(), "thinking"), "extra"));
        }
    }
    @Test void disabledThinkingAndContextOverrideAreExplicit() {
        JsonObject input = request(); input.add("thinking", Json.obj("type", "disabled"));
        ConversionResult disabled = adapt(input, deepSeek(Options.LossPolicy.ALLOW));
        assertEquals("none", Json.s(disabled.value(), "reasoning_effort"));
        ConversionContext ctx = deepSeek(Options.LossPolicy.ALLOW); ctx.reasoning = new Reasoning.Intent("enabled", "max", null, null);
        ConversionResult result = adapt(input, ctx);
        assertEquals("enabled", Json.s(Json.o(result.value(), "thinking"), "type"));
        assertTrue(diagnostic(result, "reasoning_overridden"));
    }
    @Test void providerEffortAliasNormalizesWhenConvertingToGenericProtocols() {
        JsonObject input = request(); input.remove("vendor_extension"); Json.put(input, "reasoning_effort", "ultra");
        ConversionResult result = kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, input, new ConversionContext());
        assertEquals("max", Json.s(Json.o(result.value(), "reasoning"), "effort"));
        assertTrue(diagnostic(result, "reasoning_effort_mapped"));
    }
    @Test void budgetVisibilityAndAlternateDialectReportLoss() {
        JsonObject input = request(); input.add("reasoning", Json.obj("enabled", true, "max_tokens", 4096, "exclude", false));
        ConversionResult result = adapt(input, deepSeek(Options.LossPolicy.ALLOW));
        assertFalse(result.value().has("reasoning")); assertEquals("high", Json.s(result.value(), "reasoning_effort"));
        for (String code : List.of("deepseek_effort_mapped", "provider_reasoning_dialect_removed", "reasoning_budget_approximated", "reasoning_visibility_unsupported"))
            assertTrue(diagnostic(result, code), code);
        assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.STRICT)));
        ConversionContext ctx = deepSeek(Options.LossPolicy.ALLOW); ctx.reasoning = new Reasoning.Intent("enabled", "", null, null);
        Json.put(input, "reasoning_effort", "high");
        assertFalse(adapt(input, ctx).value().has("reasoning_effort"));
    }
    @Test void tokenLimitPrecedenceAndInvalidValues() {
        JsonObject input = request(); Json.put(input, "max_tokens", 10); Json.put(input, "max_completion_tokens", 20);
        ConversionResult result = adapt(input, deepSeek(Options.LossPolicy.ALLOW));
        assertEquals(20, Json.n(result.value(), "max_tokens")); assertFalse(result.value().has("max_completion_tokens"));
        assertTrue(diagnostic(result, "provider_token_limit_overridden"));
        Json.put(input, "max_tokens", 20);
        assertFalse(diagnostic(adapt(input, deepSeek(Options.LossPolicy.STRICT)), "provider_token_limit_overridden"));
        for (int limit : List.of(0, -1)) {
            Json.put(input, "max_completion_tokens", limit);
            assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.ALLOW)));
        }
    }
    @Test void ignoredParametersAndTopPClampingAreDiagnosed() {
        JsonObject input = request(); Json.put(input, "top_p", 0.2);
        for (String field : List.of("temperature", "presence_penalty", "frequency_penalty")) Json.put(input, field, 0.3);
        ConversionResult result = adapt(input, deepSeek(Options.LossPolicy.SAFE));
        assertEquals(0.95, result.value().get("top_p").getAsDouble());
        assertEquals(0.3, result.value().get("temperature").getAsDouble());
        assertTrue(diagnostic(result, "deepseek_top_p_adjusted")); assertTrue(diagnostic(result, "provider_parameter_ignored"));
        for (double topP : List.of(0.0, -0.1, 1.1)) {
            Json.put(input, "top_p", topP);
            assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.ALLOW)));
        }
        Json.put(input, "top_p", 0.99); assertEquals(0.99, adapt(input, deepSeek(Options.LossPolicy.ALLOW)).value().get("top_p").getAsDouble());
        input.add("thinking", Json.obj("type", "disabled")); Json.put(input, "top_p", 0.1);
        assertEquals(0.1, adapt(input, deepSeek(Options.LossPolicy.ALLOW)).value().get("top_p").getAsDouble());
    }
    @Test void unsafeCombinationsAreNotSilentlyRewritten() {
        for (String field : List.of("tool_choice", "response_format", "functions")) {
            JsonObject input = request();
            input.add(field, field.equals("tool_choice") ? new com.google.gson.JsonPrimitive("required")
                    : field.equals("response_format") ? Json.obj("type", "json_schema", "json_schema", Json.obj("schema", Json.obj())) : Json.arr(Json.obj("name", "f")));
            ConversionResult result = adapt(input, deepSeek(Options.LossPolicy.ALLOW));
            assertEquals(input.get(field), result.value().get(field));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
            assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.SAFE)));
        }
        JsonObject input = request(); input.add("tool_choice", Json.obj("type", "function", "function", Json.obj("name", "f")));
        assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.SAFE)));
        input.add("thinking", Json.obj("type", "disabled"));
        assertTrue(adapt(input, deepSeek(Options.LossPolicy.SAFE)).diagnostics().isEmpty());
        Json.put(input, "tool_choice", "auto"); input.add("response_format", Json.obj("type", "json_object"));
        assertTrue(adapt(input, deepSeek(Options.LossPolicy.STRICT)).diagnostics().isEmpty());
    }
    @Test void everyAssistantTurnMustHaveReasoningWhenThinkingWithTools() {
        JsonObject input = request(); input.add("tools", Json.arr(Json.obj("type", "function", "function", Json.obj("name", "f"))));
        for (JsonObject assistant : List.of(Json.obj("role", "assistant", "content", "answer"),
                Json.obj("role", "assistant", "content", "", "reasoning_content", ""),
                Json.obj("role", "assistant", "reasoning_content", Json.obj()),
                Json.obj("role", "assistant", "reasoning_content", 42))) {
            Json.a(input, "messages").add(assistant);
            ConversionException error = assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.SAFE)));
            assertTrue(error.diagnostics().stream().anyMatch(d -> d.code().equals("deepseek_reasoning_history_missing")));
            Json.put(assistant, "reasoning_content", "complete text");
        }
        assertTrue(adapt(input, deepSeek(Options.LossPolicy.STRICT)).diagnostics().isEmpty());
        input.add("thinking", Json.obj("type", "disabled"));
        Json.o(Json.a(input, "messages").get(1)).remove("reasoning_content");
        assertTrue(adapt(input, deepSeek(Options.LossPolicy.STRICT)).diagnostics().isEmpty());
    }
    @Test void assistantArraysBecomeTextOnlyAndMediaIsNotDiscarded() {
        JsonObject input = request(); JsonObject assistant = Json.obj("role", "assistant", "content", Json.arr(
                Json.obj("type", "text", "text", "a"), Json.obj("type", "text", "text", "b")));
        Json.a(input, "messages").add(assistant);
        assertEquals("ab", Json.s(Json.o(Json.a(adapt(input, deepSeek(Options.LossPolicy.STRICT)).value(), "messages").get(1)), "content"));
        Json.a(assistant, "content").add(Json.obj("type", "image_url", "image_url", Json.obj("url", "https://example.com/a.png")));
        ConversionResult result = adapt(input, deepSeek(Options.LossPolicy.ALLOW));
        assertEquals(assistant.get("content"), Json.o(Json.a(result.value(), "messages").get(1)).get("content"));
        assertTrue(diagnostic(result, "deepseek_assistant_content_unsupported"));
        assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.SAFE)));
    }
    @Test void invalidNumericControlsAndAssistantContentProduceActionableErrors() {
        for (String field : List.of("top_p", "max_tokens")) for (Object value : List.of("1", Json.obj(), false)) {
            JsonObject input = request(); input.add(field, Json.obj("value", value).get("value"));
            assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.ALLOW)));
        }
        JsonObject input = request(); Json.put(input, "max_tokens", 1.5);
        assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.ALLOW)));
        input.remove("max_tokens");
        for (Object content : List.of(12, false, Json.obj())) {
            Json.a(input, "messages").add(Json.obj("role", "assistant", "content", content));
            assertThrows(ConversionException.class, () -> adapt(input, deepSeek(Options.LossPolicy.SAFE)));
        }
    }
    @Test void sharedConnectAndBuiltinProfileKeepConcurrentRequestsIsolated() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                int index = i;
                tasks.add(() -> {
                    ConversionContext ctx = deepSeek(Options.LossPolicy.STRICT); ctx.upstreamModel = "routed-" + index;
                    JsonObject input = Json.obj("model", "alias", "tools", Json.arr(Json.obj("type", "function", "name", "f" + index)),
                            "input", Json.arr(Json.obj("type", "reasoning", "content", Json.arr(Json.obj("type", "reasoning_text", "text", "plan" + index))),
                                    Json.obj("type", "function_call", "call_id", "call" + index, "name", "f" + index, "arguments", "{}")));
                    JsonObject before = input.deepCopy();
                    ConversionResult result = kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, input, ctx);
                    assertEquals("routed-" + index, Json.s(result.value(), "model"));
                    assertEquals("plan" + index, Json.s(Json.o(Json.a(result.value(), "messages").get(0)), "reasoning_content"));
                    assertEquals(1, ctx.callNames.size()); assertEquals("f" + index, ctx.callNames.get("call" + index));
                    assertEquals(before, input); assertTrue(result.diagnostics().isEmpty());
                    return null;
                });
            }
            for (var future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) future.get();
        } finally {
            executor.shutdownNow();
        }
    }
}
