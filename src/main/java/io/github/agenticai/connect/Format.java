package io.github.agenticai.connect;

import java.util.Locale;

/** Protocol formats; hosts can supply Gemini model names through the conversion context. */
public enum Format {
    OPENAI_CHAT("openai"), OPENAI_RESPONSES("openai_responses"),
    CLAUDE("claude"), GEMINI("gemini");

    private final String wireName;
    Format(String wireName) { this.wireName = wireName; }
    public String wireName() { return wireName; }
    public static Format parse(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "openai", "chat", "openai_chat" -> OPENAI_CHAT;
            case "responses", "openai_responses" -> OPENAI_RESPONSES;
            case "claude", "anthropic" -> CLAUDE;
            case "gemini" -> GEMINI;
            default -> throw new IllegalArgumentException("未知协议：" + name);
        };
    }
}
