package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    @ParameterizedTest
    @CsvSource({"0,0,300,300", "100,200,300,300", "500,200,300,500", "100,700,300,700", "-1,-2,-3,0"})
    void cacheWriteAliasesUseTheirMaximumWithoutInflatingInput(long legacy, long write, long creation, long expected) {
        for (Format format : new Format[] {Format.OPENAI_CHAT, Format.OPENAI_RESPONSES}) {
            String inputKey = format == Format.OPENAI_CHAT ? "prompt_tokens" : "input_tokens";
            String detailsKey = format == Format.OPENAI_CHAT ? "prompt_tokens_details" : "input_tokens_details";
            JsonObject source = Json.obj("usage", Json.obj(inputKey, 1500, detailsKey,
                    Json.obj("cached_creation_tokens", legacy, "cache_write_tokens", write, "cache_creation_input_tokens", creation)));
            Usage usage = Usage.from(format, source);
            assertEquals(1500, usage.inputTokens()); assertEquals(1500, usage.totalTokens());
            assertEquals(expected, usage.cacheWriteTokens());
            assertEquals(source.get("usage"), usage.toJson(format));
            Format target = format == Format.OPENAI_CHAT ? Format.OPENAI_RESPONSES : Format.OPENAI_CHAT;
            Usage converted = Usage.from(target, Json.obj("usage", usage.toJson(target)));
            assertEquals(expected, converted.cacheWriteTokens()); assertEquals(1500, converted.inputTokens());
        }
    }

    @Test void cacheLifetimesAndModalityDetailsRoundTripWithoutAliasing() {
        JsonObject source = Json.object("""
                {"usage":{"input_tokens":12000,"output_tokens":100,"input_tokens_details":{
                  "cached_tokens":4000,"cache_write_tokens":7000,
                  "cache_write_tokens_details":{"ttl_30m_tokens":3000,"ttl_12h_tokens":4000},
                  "cached_tokens_details":{"text_tokens":3500,"image_tokens":500}}}}
                """);
        Usage usage = Usage.from(Format.OPENAI_RESPONSES, source);
        assertEquals(7000, usage.cacheWriteTokens()); assertEquals(3000, usage.cacheWrite30mTokens());
        assertEquals(4000, usage.cacheWrite12hTokens());
        assertEquals(0, usage.cacheWrite5mTokens()); assertEquals(0, usage.cacheWrite1hTokens());
        JsonObject chat = usage.toJson(Format.OPENAI_CHAT);
        assertEquals(Json.o(Json.o(source, "usage"), "input_tokens_details"), Json.o(chat, "prompt_tokens_details"));
        Usage roundTrip = Usage.from(Format.OPENAI_CHAT, Json.obj("usage", chat));
        assertEquals(Json.o(usage.raw(), "input_tokens_details"),
                Json.o(roundTrip.toJson(Format.OPENAI_RESPONSES), "input_tokens_details"));
        assertEquals(usage.inputTokens(), roundTrip.inputTokens());
        assertEquals(usage.outputTokens(), roundTrip.outputTokens());
        assertEquals(usage.totalTokens(), roundTrip.totalTokens());
        Json.o(Json.o(chat, "prompt_tokens_details"), "cache_write_tokens_details").addProperty("ttl_12h_tokens", 99);
        Json.o(Json.o(Json.o(source, "usage"), "input_tokens_details"), "cache_write_tokens_details").addProperty("ttl_12h_tokens", 1);
        Json.o(Json.o(usage.raw(), "input_tokens_details"), "cache_write_tokens_details").addProperty("ttl_12h_tokens", 2);
        assertEquals(4000, usage.cacheWrite12hTokens()); assertEquals(4000, roundTrip.cacheWrite12hTokens());
        assertEquals(0, Usage.empty().cacheWrite12hTokens()); assertEquals(0, Usage.empty().cacheWrite30mTokens());
        Usage claude = Usage.from(Format.CLAUDE, Json.object("""
                {"usage":{"input_tokens":10,"cache_creation_input_tokens":5,"cache_creation":{"ephemeral_1h_input_tokens":5}}}
                """));
        assertEquals(0, claude.cacheWrite12hTokens()); assertEquals(5, claude.cacheWrite1hTokens());
        assertEquals(5, Json.n(Json.o(claude.toJson(Format.OPENAI_RESPONSES), "input_tokens_details"), "cache_write_tokens"));
    }

    @ParameterizedTest
    @CsvSource({"9000,7000", "-1,0", "0,0", "4000,4000"})
    void cacheLifetimeAccessorsAreBoundedButRawReportsRemainIntact(long reported, long bounded) {
        Usage usage = Usage.from(Format.OPENAI_CHAT, Json.obj("usage", Json.obj("prompt_tokens_details",
                Json.obj("cache_write_tokens", 7000, "cache_write_tokens_details", Json.obj("ttl_12h_tokens", reported)))));
        assertEquals(bounded, usage.cacheWrite12hTokens());
        assertEquals(reported, Json.n(Json.o(Json.o(usage.toJson(Format.OPENAI_RESPONSES), "input_tokens_details"), "cache_write_tokens_details"), "ttl_12h_tokens"));
    }

    @Test void streamingCacheSnapshotsPreserveSplitAndAliasOnlyUpdates() {
        for (Format source : new Format[] {Format.OPENAI_CHAT, Format.OPENAI_RESPONSES}) {
            String detailsKey = source == Format.OPENAI_CHAT ? "prompt_tokens_details" : "input_tokens_details";
            String countKey = source == Format.OPENAI_CHAT ? "prompt_tokens" : "input_tokens";
            Format target = source == Format.OPENAI_CHAT ? Format.OPENAI_RESPONSES : Format.OPENAI_CHAT;
            ResponseStreamState stream = new Connect().newResponseStream(source, target, null, null);
            JsonObject first = Json.obj("usage", Json.obj(countKey, 12000, detailsKey,
                    Json.obj("cache_creation_input_tokens", 7000, "cache_write_tokens_details", Json.obj("ttl_30m_tokens", 3000))));
            JsonObject later = Json.obj("usage", Json.obj(detailsKey,
                    Json.obj("cache_write_tokens", 6000, "cache_write_tokens_details", Json.obj("ttl_30m_tokens", 0, "ttl_12h_tokens", 4000))));
            if (source == Format.OPENAI_RESPONSES) {
                stream.accept(Json.obj("type", "response.created", "response", first));
                stream.accept(Json.obj("type", "response.in_progress", "response", later));
            } else {
                stream.accept(first); stream.accept(later);
            }
            var terminal = stream.finish();
            assertEquals(7000, stream.usage().cacheWriteTokens()); assertEquals(12000, stream.usage().inputTokens());
            assertEquals(3000, stream.usage().cacheWrite30mTokens()); assertEquals(4000, stream.usage().cacheWrite12hTokens());
            JsonObject wire = terminal.stream().map(ConversionResult::value)
                    .map(e -> e.has("response") ? Json.o(e, "response") : e).filter(e -> e.has("usage")).findFirst().orElseThrow();
            Usage converted = Usage.from(target, wire);
            assertEquals(7000, converted.cacheWriteTokens()); assertEquals(4000, converted.cacheWrite12hTokens());
            assertEquals(3000, converted.cacheWrite30mTokens());
            Json.o(Json.o(later, "usage"), detailsKey).addProperty("cache_creation_input_tokens", 99);
            assertEquals(7000, stream.usage().cacheWriteTokens());
        }
    }
}
