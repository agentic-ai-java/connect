package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Per-request options, configured by the host before conversion begins. */
public final class Options {
    public enum LossPolicy { ALLOW, SAFE, STRICT }
    public LossPolicy toolLossPolicy = LossPolicy.ALLOW;
    /** Optional final-request adapter; null preserves generic protocol behavior. */
    public ProviderProfile providerProfile;
    public ToIntFunction<String> claudeDefaultMaxTokens;
    public String claudeWebSearchVersion = "web_search_20250305";
    public double thinkingBudgetPercentage = 0.6;
    public boolean geminiFunctionCallThoughtSignature;
    public Function<String, String> geminiSafetySetting;
    public Predicate<String> geminiSupportsImages = model -> false;
    public boolean openRouterDialect;
    public BiFunction<Format, JsonObject, JsonObject> webSearchEncoder;
}
