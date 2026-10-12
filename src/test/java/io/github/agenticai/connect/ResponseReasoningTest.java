package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Display conversion must warn that reasoning summaries cannot serve as complete replay history. */
class ResponseReasoningTest {
    private final Connect kit = new Connect();

    @Test void summariesRemainVisibleWithReplayWarningsAcrossTargets() {
        JsonObject response = Json.object("""
                {"id":"resp_test","model":"x","status":"completed","output":[
                {"type":"reasoning","summary":[{"type":"summary_text","text":"display summary"}]},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"answer"}]}]}
                """);
        JsonObject original = response.deepCopy();
        Options options = new Options(); options.toolLossPolicy = Options.LossPolicy.STRICT;
        for (Format target : List.of(Format.OPENAI_CHAT, Format.CLAUDE, Format.GEMINI)) {
            ConversionResult result = kit.convertResponse(Format.OPENAI_RESPONSES, target, response,
                    new ConversionContext(options));
            assertTrue(result.json().contains("display summary"));
            assertTrue(result.json().contains("answer"));
            assertEquals(1, result.diagnostics().stream()
                    .filter(d -> d.code().equals("reasoning_summary_not_replayable")
                            && d.severity() == Diagnostic.Severity.WARNING).count());
        }
        assertEquals(original, response);
        assertEquals(response, kit.convertResponse(Format.OPENAI_RESPONSES, Format.OPENAI_RESPONSES, response, null).value());
    }

    @Test void summaryFragmentsEmitOneReplayWarningWhilePreservingDisplayText() {
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, null, null);
        StringBuilder text = new StringBuilder();
        for (String fragment : List.of("display ", "summary")) {
            for (ConversionResult result : state.accept(Json.obj("type", "response.reasoning_summary_text.delta", "delta", fragment))) {
                JsonObject choice = Json.o(Json.a(result.value(), "choices").get(0));
                text.append(Json.s(Json.o(choice, "delta"), "reasoning_content"));
                assertEquals(1, result.diagnostics().size());
            }
        }
        assertEquals("display summary", text.toString());
        assertEquals(1, state.diagnostics().size());
        assertEquals("reasoning_summary_not_replayable", state.diagnostics().get(0).code());
        state.finish();
        assertEquals(1, state.diagnostics().size());
    }

    @Test void fullReasoningDeltasAndTextOnlyResponsesDoNotWarnAboutSummaries() {
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, null, null);
        List<ConversionResult> events = state.accept(Json.obj("type", "response.reasoning_text.delta", "delta", "full reasoning"));
        assertTrue(events.stream().anyMatch(r -> r.json().contains("full reasoning")));
        assertTrue(state.diagnostics().isEmpty());
        state.finish();
        JsonObject response = Json.obj("status", "completed", "output", Json.arr(Json.obj("type", "message", "role", "assistant",
                "content", Json.arr(Json.obj("type", "output_text", "text", "answer")))));
        assertTrue(kit.convertResponse(Format.OPENAI_RESPONSES, Format.OPENAI_CHAT, response, null).diagnostics().isEmpty());
    }
}
