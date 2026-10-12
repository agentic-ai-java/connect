package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderUsageTest {
    @Test void cacheHitMissFallbackDoesNotDoubleCountNativePromptTotals() {
        JsonObject input = Json.object("""
                {"usage":{"prompt_cache_hit_tokens":8,"prompt_cache_miss_tokens":2,"completion_tokens":3,"provider_bill":"original"}}
                """);
        Usage usage = Usage.from(Format.OPENAI_CHAT, input);
        assertEquals(10, usage.inputTokens()); assertEquals(8, usage.cachedTokens()); assertEquals(13, usage.totalTokens());
        assertEquals("original", Json.s(usage.raw(), "provider_bill"));
        Json.put(Json.o(input, "usage"), "prompt_tokens", 10);
        assertEquals(10, Usage.from(Format.OPENAI_CHAT, input).inputTokens());
        Json.put(Json.o(input, "usage"), "prompt_tokens", 0);
        Json.o(input, "usage").add("prompt_tokens_details", Json.obj("cached_tokens", 0));
        assertEquals(0, Usage.from(Format.OPENAI_CHAT, input).inputTokens());
        assertEquals(0, Usage.from(Format.OPENAI_CHAT, input).cachedTokens());
    }
    @Test void completeResponseAndStreamUseTheSameProviderFallback() {
        Connect kit = new Connect();
        JsonObject input = Json.object("""
                {"id":"c","model":"x","choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
                "usage":{"prompt_cache_hit_tokens":8,"prompt_cache_miss_tokens":2,"completion_tokens":3}}
                """);
        ConversionResult response = kit.convertResponse(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, input, null);
        assertEquals(10, response.usage().inputTokens());
        assertEquals(8, Json.n(Json.o(Json.o(response.value(), "usage"), "input_tokens_details"), "cached_tokens"));
        ResponseStreamState state = kit.newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, null, null);
        state.accept(Json.obj("choices", Json.arr(), "usage", Json.obj("prompt_cache_hit_tokens", 8, "prompt_cache_miss_tokens", 2)));
        state.accept(Json.obj("choices", Json.arr(), "usage", Json.obj("completion_tokens", 3)));
        state.finish();
        assertEquals(response.usage().inputTokens(), state.usage().inputTokens());
        assertEquals(response.usage().cachedTokens(), state.usage().cachedTokens());
        assertEquals(13, state.usage().totalTokens());
        assertEquals(8, Json.n(state.usage().raw(), "prompt_cache_hit_tokens"));
    }
    @Test void streamKeepsNewExplicitZeroInsteadOfUsingCacheFallback() {
        ResponseStreamState state = new Connect().newResponseStream(Format.OPENAI_CHAT, Format.OPENAI_CHAT, null, null);
        state.accept(Json.obj("choices", Json.arr(), "usage", Json.obj("prompt_cache_hit_tokens", 8, "prompt_cache_miss_tokens", 2)));
        state.accept(Json.obj("choices", Json.arr(), "usage", Json.obj("prompt_tokens", 0, "prompt_tokens_details", Json.obj("cached_tokens", 0))));
        assertEquals(0, state.usage().inputTokens()); assertEquals(0, state.usage().cachedTokens());
        assertTrue(state.usage().raw().has("prompt_tokens")); state.finish();
    }
}
