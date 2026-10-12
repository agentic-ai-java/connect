package io.github.agenticai.connect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import static io.github.agenticai.connect.Json.n;
import static io.github.agenticai.connect.Json.o;
import static io.github.agenticai.connect.Json.obj;

/** Normalized usage: input includes cache reads and writes; output includes reasoning. */
public record Usage(long inputTokens, long outputTokens, long totalTokens, long cachedTokens,
        long cacheWriteTokens, long cacheWrite5mTokens, long cacheWrite1hTokens,
        long reasoningTokens, Format source, JsonObject raw) {
    public Usage { raw = raw == null ? new JsonObject() : raw.deepCopy(); }
    @Override public JsonObject raw() { return raw.deepCopy(); }
    public static Usage empty() { return new Usage(0, 0, 0, 0, 0, 0, 0, 0, null, null); }
    public static Usage from(Format format, JsonObject value) {
        JsonObject u = o(value, format == Format.GEMINI ? "usageMetadata" : "usage");
        long input, output, cache, write = 0, five = 0, hour = 0, thought;
        switch (format) {
            case OPENAI_CHAT -> {
                // Native totals and standard details win, including an explicit zero.
                input = Json.has(u, "prompt_tokens") ? n(u, "prompt_tokens")
                        : n(u, "prompt_cache_hit_tokens") + n(u, "prompt_cache_miss_tokens");
                output = n(u, "completion_tokens");
                cache = Json.has(o(u, "prompt_tokens_details"), "cached_tokens")
                        ? n(o(u, "prompt_tokens_details"), "cached_tokens") : n(u, "prompt_cache_hit_tokens");
                write = n(o(u, "prompt_tokens_details"), "cached_creation_tokens");
                if (write == 0) write = n(o(u, "prompt_tokens_details"), "cache_write_tokens");
                thought = n(o(u, "completion_tokens_details"), "reasoning_tokens");
            }
            case OPENAI_RESPONSES -> {
                input = n(u, "input_tokens"); output = n(u, "output_tokens");
                cache = n(o(u, "input_tokens_details"), "cached_tokens");
                thought = n(o(u, "output_tokens_details"), "reasoning_tokens");
            }
            case CLAUDE -> {
                cache = n(u, "cache_read_input_tokens"); write = n(u, "cache_creation_input_tokens");
                five = n(o(u, "cache_creation"), "ephemeral_5m_input_tokens");
                hour = n(o(u, "cache_creation"), "ephemeral_1h_input_tokens");
                if (write == 0) write = five + hour;
                if (five + hour == 0) five = write;
                input = n(u, "input_tokens") + cache + write;
                output = n(u, "output_tokens"); thought = 0;
            }
            case GEMINI -> {
                input = n(u, "promptTokenCount"); cache = n(u, "cachedContentTokenCount");
                thought = n(u, "thoughtsTokenCount"); output = n(u, "candidatesTokenCount") + thought;
            }
            default -> throw new IllegalArgumentException("未知 usage 格式");
        }
        long total = n(u, format == Format.GEMINI ? "totalTokenCount" : "total_tokens");
        if (total == 0) total = input + output;
        return new Usage(input, output, total, cache, write, five, hour, thought, format, u);
    }
    /** Merges native fields so output-only Claude updates preserve input and cache counts. */
    public Usage mergeNonZero(Usage later) {
        if (later == null || later.raw.size() == 0) return this;
        if (source == null || source != later.source) return later;
        JsonObject merged = mergeObjects(raw, later.raw);
        return from(source, obj(source == Format.GEMINI ? "usageMetadata" : "usage", merged));
    }
    public static JsonObject mergeObjects(JsonObject earlier, JsonObject later) {
        JsonObject result = earlier.deepCopy();
        for (var entry : later.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) result.add(entry.getKey(), mergeObjects(o(result, entry.getKey()), value.getAsJsonObject()));
            // Retain a newly reported zero: its presence can take precedence over provider fallback fields.
            else if (!value.isJsonNull() && !(Json.has(result, entry.getKey()) && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber() && value.getAsDouble() == 0))
                result.add(entry.getKey(), value.deepCopy());
        }
        return result;
    }
    public JsonObject toJson(Format target) {
        if (target == source) return raw();
        return switch (target) {
            case OPENAI_CHAT -> obj("prompt_tokens", inputTokens, "completion_tokens", outputTokens,
                    "total_tokens", totalTokens, "prompt_tokens_details", obj("cached_tokens", cachedTokens,
                    "cache_write_tokens", cacheWriteTokens), "completion_tokens_details", obj("reasoning_tokens", reasoningTokens));
            case OPENAI_RESPONSES -> obj("input_tokens", inputTokens, "output_tokens", outputTokens,
                    "total_tokens", totalTokens, "input_tokens_details", obj("cached_tokens", cachedTokens),
                    "output_tokens_details", obj("reasoning_tokens", reasoningTokens));
            case CLAUDE -> obj("input_tokens", Math.max(0, inputTokens - cachedTokens - cacheWriteTokens),
                    "output_tokens", outputTokens, "cache_read_input_tokens", cachedTokens,
                    "cache_creation_input_tokens", cacheWriteTokens, "cache_creation",
                    obj("ephemeral_5m_input_tokens", cacheWrite5mTokens, "ephemeral_1h_input_tokens", cacheWrite1hTokens));
            case GEMINI -> obj("promptTokenCount", inputTokens, "candidatesTokenCount", Math.max(0, outputTokens - reasoningTokens),
                    "totalTokenCount", totalTokens, "cachedContentTokenCount", cachedTokens, "thoughtsTokenCount", reasoningTokens);
        };
    }
}
