package io.github.agenticai.connect;

import java.util.Locale;

public final class FinishReasons {
    private FinishReasons() {}
    public static String fromClaude(String reason) {
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens", "pause_turn" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> reason;
        };
    }
    public static String toClaude(String reason) {
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "stop" -> "end_turn";
            case "length", "max_tokens" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "refusal";
            default -> reason;
        };
    }
    static String fromGemini(String reason) {
        return switch (reason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> "content_filter";
            default -> reason.toLowerCase(Locale.ROOT);
        };
    }
    static String toGemini(String reason) {
        return switch (reason) {
            case "stop", "tool_calls" -> "STOP";
            case "length" -> "MAX_TOKENS";
            case "content_filter" -> "SAFETY";
            default -> reason.toUpperCase(Locale.ROOT);
        };
    }
}
