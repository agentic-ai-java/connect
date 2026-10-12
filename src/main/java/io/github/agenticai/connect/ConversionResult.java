package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import java.util.List;

public record ConversionResult(JsonObject value, Format from, Format to, String converter,
        Quality quality, List<Step> steps, List<Diagnostic> diagnostics, Usage usage, boolean stream) {
    public enum Quality { GOOD, FAIR, DISCOURAGED }
    public record Step(String converter, Format from, Format to) {}
    public ConversionResult {
        steps = List.copyOf(steps); diagnostics = List.copyOf(diagnostics);
    }
    public String json() { return Json.stringify(value); }
}
