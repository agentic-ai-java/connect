package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RequestHistoryTest {
    private final Connect kit = new Connect();
    private static ConversionContext context(Options.LossPolicy policy) {
        Options options = new Options(); options.toolLossPolicy = policy; options.claudeDefaultMaxTokens = model -> 4096;
        ConversionContext ctx = new ConversionContext(options); ctx.upstreamModel = "target";
        return ctx;
    }
    private static JsonObject message(ConversionResult result, int index) {
        return Json.o(Json.a(result.value(), "messages").get(index));
    }
    @Test void claudeThinkingAndCallsStayOnOneAssistantWithoutLeakingIntoContent() {
        JsonObject input = Json.object("""
                {"model":"claude-test","max_tokens":1024,"messages":[{"role":"assistant","content":[
                {"type":"thinking","thinking":"private plan","signature":"native-signature"},
                {"type":"text","text":"Let me check"},
                {"type":"tool_use","id":"a","name":"f","input":{}},
                {"type":"tool_use","id":"b","name":"g","input":{}}]}]}
                """);
        JsonObject before = input.deepCopy();
        ConversionResult result = kit.convertRequest(Format.CLAUDE, Format.OPENAI_CHAT, input, context(Options.LossPolicy.ALLOW));
        JsonObject assistant = message(result, 0);
        assertEquals("private plan", Json.s(assistant, "reasoning_content"));
        assertEquals("Let me check", ContentCodec.text(assistant.get("content")));
        assertEquals(2, Json.a(assistant, "tool_calls").size());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("reasoning_signature_omitted")));
        assertEquals(before, input);
    }
    @Test void geminiThoughtsRemainSeparateAndNativeSignaturesAreDiagnosed() {
        JsonObject input = Json.object("""
                {"contents":[{"role":"model","parts":[{"thought":true,"text":"plan","thoughtSignature":"signed"},
                {"text":"answer"},{"functionCall":{"id":"a","name":"f","args":{}},"thoughtSignature":"call-signed"}]}]}
                """);
        ConversionResult result = kit.convertRequest(Format.GEMINI, Format.OPENAI_CHAT, input, context(Options.LossPolicy.ALLOW));
        assertEquals("plan", Json.s(message(result, 0), "reasoning_content"));
        assertEquals("answer", ContentCodec.text(message(result, 0).get("content")));
        assertEquals(2, result.diagnostics().size());
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.GEMINI, Format.OPENAI_CHAT, input, context(Options.LossPolicy.STRICT)));
    }
    @Test void responsesFullReasoningMessageAndParallelCallsShareOneTurn() {
        JsonObject input = Json.object("""
                {"model":"x","input":[{"role":"user","content":"question"},
                {"type":"reasoning","content":[{"type":"reasoning_text","text":"plan "}]},
                {"type":"reasoning","content":[{"type":"reasoning_text","text":"continued"}]},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"checking"}]},
                {"type":"function_call","call_id":"a","name":"f","arguments":"{}"},
                {"type":"function_call","call_id":"b","name":"g","arguments":"{}"},
                {"type":"function_call_output","call_id":"a","output":"one"},
                {"type":"function_call_output","call_id":"b","output":"two"},
                {"type":"reasoning","content":[{"type":"reasoning_text","text":"next plan"}]},
                {"type":"message","role":"assistant","content":"answer"}]}
                """);
        ConversionResult result = kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, input, context(Options.LossPolicy.STRICT));
        assertEquals(5, Json.a(result.value(), "messages").size());
        assertEquals("plan continued", Json.s(message(result, 1), "reasoning_content"));
        assertEquals("checking", ContentCodec.text(message(result, 1).get("content")));
        assertEquals(2, Json.a(message(result, 1), "tool_calls").size());
        assertEquals("a", Json.s(message(result, 2), "tool_call_id"));
        assertEquals("b", Json.s(message(result, 3), "tool_call_id"));
        assertEquals("next plan", Json.s(message(result, 4), "reasoning_content"));
        assertEquals("answer", Json.s(message(result, 4), "content"));
    }
    @Test void distinctAssistantMessagesAndNewReasoningDoNotMergeTurns() {
        JsonObject input = Json.object("""
                {"model":"x","input":[{"role":"assistant","content":"one"},
                {"role":"assistant","content":"two"},
                {"type":"reasoning","content":[{"type":"reasoning_text","text":"three plan"}]},
                {"type":"function_call","call_id":"c","name":"f","arguments":"{}"},
                {"role":"user","content":"four"},
                {"type":"custom_tool_call","call_id":"d","name":"g","input":"raw"}]}
                """);
        ConversionResult result = kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, input, context(Options.LossPolicy.ALLOW));
        assertEquals(5, Json.a(result.value(), "messages").size());
        assertEquals("two", Json.s(message(result, 1), "content"));
        assertEquals("three plan", Json.s(message(result, 2), "reasoning_content"));
        assertEquals("c", Json.s(Json.o(Json.a(message(result, 2), "tool_calls").get(0)), "id"));
        assertEquals("d", Json.s(Json.o(Json.a(message(result, 4), "tool_calls").get(0)), "id"));
    }
    @Test void summariesAndEncryptedReasoningNeverMasqueradeAsReplayableText() {
        JsonObject input = Json.object("""
                {"model":"x","input":[{"type":"reasoning","summary":[{"type":"summary_text","text":"summary only"}],
                "encrypted_content":"opaque"},{"type":"function_call","call_id":"a","name":"f","arguments":"{}"}]}
                """);
        ConversionResult result = kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, input, context(Options.LossPolicy.ALLOW));
        assertFalse(message(result, 0).has("reasoning_content")); assertEquals(2, result.diagnostics().size());
        assertFalse(result.json().contains("summary only"));
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, input, context(Options.LossPolicy.SAFE)));
        Json.o(Json.a(input, "input").get(0)).add("content", Json.arr(Json.obj("type", "unknown", "text", "not reasoning")));
        ConversionResult unknown = kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, input, context(Options.LossPolicy.ALLOW));
        assertTrue(unknown.diagnostics().stream().anyMatch(d -> d.code().equals("reasoning_content_omitted")));
    }
    @Test void chatReasoningCannotBecomeUnsignedNativeOrVisibleText() {
        JsonObject input = Json.object("""
                {"model":"x","max_tokens":4096,"messages":[{"role":"assistant","content":"visible","reasoning_content":"private"}]}
                """);
        for (Format target : List.of(Format.CLAUDE, Format.GEMINI, Format.OPENAI_RESPONSES)) {
            ConversionResult result = kit.convertRequest(Format.OPENAI_CHAT, target, input, context(Options.LossPolicy.ALLOW));
            assertFalse(result.json().contains("private")); assertTrue(result.json().contains("visible"));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("reasoning_history_not_replayable")));
            assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, target, input, context(Options.LossPolicy.SAFE)));
        }
    }
    @Test void signedNativeBlocksSurviveOnlyTheirOwnDialect() {
        JsonObject input = Json.object("""
                {"model":"x","max_tokens":4096,"messages":[{"role":"assistant","content":[
                {"type":"thinking","text":"private","signature":"claude-signature"},{"type":"text","text":"visible"}]}]}
                """);
        JsonObject claude = kit.convertRequest(Format.OPENAI_CHAT, Format.CLAUDE, input, context(Options.LossPolicy.STRICT)).value();
        assertEquals("claude-signature", Json.s(Json.o(Json.a(Json.o(Json.a(claude, "messages").get(0)), "content").get(0)), "signature"));
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, input, context(Options.LossPolicy.SAFE)));
        JsonObject thought = Json.o(Json.a(Json.o(Json.a(input, "messages").get(0)), "content").get(0));
        thought.remove("signature"); Json.put(thought, "thoughtSignature", "gemini-signature");
        JsonObject gemini = kit.convertRequest(Format.OPENAI_CHAT, Format.GEMINI, input, context(Options.LossPolicy.STRICT)).value();
        assertEquals("gemini-signature", Json.s(Json.o(Json.a(Json.o(Json.a(gemini, "contents").get(0)), "parts").get(0)), "thoughtSignature"));
    }
    @Test void toolResultsPrecedeMixedUserContentWithoutDroppingMedia() {
        JsonObject input = Json.object("""
                {"model":"x","max_tokens":100,"messages":[{"role":"user","content":[{"type":"text","text":"after results"},
                {"type":"tool_result","tool_use_id":"a","content":[{"type":"text","text":"one"},
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aA=="}}]},
                {"type":"tool_result","tool_use_id":"b","content":"two"}]}]}
                """);
        ConversionResult result = kit.convertRequest(Format.CLAUDE, Format.OPENAI_CHAT, input, context(Options.LossPolicy.STRICT));
        assertEquals(3, Json.a(result.value(), "messages").size());
        assertEquals("a", Json.s(message(result, 0), "tool_call_id"));
        assertEquals("b", Json.s(message(result, 1), "tool_call_id"));
        assertEquals("user", Json.s(message(result, 2), "role"));
        assertEquals(2, Json.a(message(result, 0), "content").size());
        assertEquals("image_url", Json.s(Json.o(Json.a(message(result, 0), "content").get(1)), "type"));
    }
    @Test void profileExtractsInternalThinkingOnIdentityButDefaultIdentityDoesNot() {
        JsonObject input = Json.object("""
                {"model":"x","messages":[{"role":"assistant","content":[{"type":"thinking","text":"plan"}]}]}
                """);
        assertEquals(input, kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_CHAT, input, null).value());
        ConversionContext ctx = context(Options.LossPolicy.STRICT); ctx.options.providerProfile = ProviderProfile.deepSeekChat();
        ConversionResult result = kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_CHAT, input, ctx);
        assertTrue(message(result, 0).get("content").isJsonNull());
        assertEquals("plan", Json.s(message(result, 0), "reasoning_content"));
        Json.put(Json.o(Json.a(input, "messages").get(0)), "reasoning_content", "different");
        assertThrows(ConversionException.class, () -> kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_CHAT, input, ctx));
        ctx.options.toolLossPolicy = Options.LossPolicy.ALLOW;
        assertEquals("different", Json.s(message(kit.convertRequest(Format.OPENAI_CHAT, Format.OPENAI_CHAT, input, ctx), 0), "reasoning_content"));
    }
    @Test void finalProfileDoesNotRenderIntermediateReasoningBudgets() {
        ConversionContext ctx = context(Options.LossPolicy.ALLOW); ctx.options.providerProfile = ProviderProfile.deepSeekChat();
        ctx.options.thinkingBudgetPercentage = 0.01;
        JsonObject input = Json.object("""
                {"model":"source","thinking":{"type":"enabled","budget_tokens":16384},"max_tokens":100,
                "messages":[{"role":"user","content":"hi"}]}
                """);
        ConversionResult result = kit.convertRequestVia(Format.CLAUDE, Format.OPENAI_CHAT, input, ctx,
                List.of(Format.CLAUDE, Format.OPENAI_RESPONSES, Format.OPENAI_CHAT));
        assertEquals(100, Json.n(result.value(), "max_tokens"));
        assertEquals("high", Json.s(result.value(), "reasoning_effort"));
        assertFalse(result.diagnostics().stream().anyMatch(d -> d.code().equals("claude_max_tokens_raised")));
    }
    @Test void largeParallelToolTurnRetainsCallOrderAndResultBoundaries() {
        JsonArray items = Json.arr(Json.obj("role", "user", "content", "question"),
                Json.obj("type", "reasoning", "content", Json.arr(Json.obj("type", "reasoning_text", "text", "plan"))));
        for (int i = 0; i < 512; i++) items.add(Json.obj("type", "function_call", "call_id", "call" + i, "name", "f", "arguments", "{}"));
        for (int i = 0; i < 512; i++) items.add(Json.obj("type", "function_call_output", "call_id", "call" + i, "output", "result" + i));
        items.add(Json.obj("type", "reasoning", "content", Json.arr(Json.obj("type", "reasoning_text", "text", "final plan"))));
        items.add(Json.obj("role", "assistant", "content", "answer"));
        ConversionResult result = kit.convertRequest(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT,
                Json.obj("model", "x", "input", items), context(Options.LossPolicy.STRICT));
        assertEquals(515, Json.a(result.value(), "messages").size());
        JsonArray calls = Json.a(message(result, 1), "tool_calls"); assertEquals(512, calls.size());
        for (int i = 0; i < 512; i++) {
            assertEquals("call" + i, Json.s(Json.o(calls.get(i)), "id"));
            assertEquals("call" + i, Json.s(message(result, i + 2), "tool_call_id"));
        }
        assertEquals("final plan", Json.s(message(result, 514), "reasoning_content"));
        assertEquals("answer", Json.s(message(result, 514), "content"));
    }
}
