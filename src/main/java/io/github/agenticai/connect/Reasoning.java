package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import static io.github.agenticai.connect.Json.b;
import static io.github.agenticai.connect.Json.has;
import static io.github.agenticai.connect.Json.n;
import static io.github.agenticai.connect.Json.o;
import static io.github.agenticai.connect.Json.obj;
import static io.github.agenticai.connect.Json.put;
import static io.github.agenticai.connect.Json.s;

/** Portable reasoning intent that preserves exact budgets; hosts explicitly parse model suffixes. */
public final class Reasoning {
    private Reasoning() {}
    public record Intent(String mode, String effort, Integer budget, Boolean includeThoughts) {
        public Intent {
            mode = mode == null ? "" : mode;
            if (!List.of("", "enabled", "disabled", "adaptive", "between_tools").contains(mode))
                throw new ConversionException("无效 thinking mode：" + mode);
            effort = effort == null ? "" : effort.toLowerCase(Locale.ROOT);
            if (!effort.isEmpty() && !List.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra").contains(effort))
                throw new ConversionException("无效 reasoning effort：" + effort);
            if (budget != null && budget < -1) throw new ConversionException("thinking budget 不能小于 -1");
        }
        public boolean disabled() { return mode.equals("disabled") || effort.equals("none") || Integer.valueOf(0).equals(budget); }
        public boolean empty() { return mode.isEmpty() && effort.isEmpty() && budget == null && includeThoughts == null; }
    }
    public record ModelIntent(String model, Intent intent) {}
    public static ModelIntent parseModelSuffix(String model, boolean enabled) {
        if (!enabled) return new ModelIntent(model, null);
        String bare = model.substring(model.lastIndexOf('/') + 1);
        if (!(bare.startsWith("claude-") || bare.startsWith("gemini-"))) return new ModelIntent(model, null);
        int marker = model.lastIndexOf("-thinking-");
        if (marker >= 0) {
            try { return new ModelIntent(model.substring(0, marker), new Intent("enabled", "", Integer.valueOf(model.substring(marker + 10)), true)); }
            catch (NumberFormatException e) { throw new ConversionException("模型尾缀的预算无效：" + model); }
        }
        if (model.endsWith("-nothinking")) return new ModelIntent(model.substring(0, model.length() - 11), new Intent("disabled", "none", null, null));
        if (model.endsWith("-thinking")) return new ModelIntent(model.substring(0, model.length() - 9), new Intent("enabled", "", null, true));
        return parseEffort(model, null, true);
    }
    public static ModelIntent parseOpenAIEffortSuffix(String model, Predicate<String> preserve) {
        String bare = model.substring(model.lastIndexOf('/') + 1);
        if ((preserve != null && preserve.test(model)) || !(bare.startsWith("gpt-") || bare.matches("o[1-9].*")))
            return new ModelIntent(model, null);
        return parseEffort(model, preserve, false);
    }
    private static ModelIntent parseEffort(String model, Predicate<String> preserve, boolean thoughts) {
        if (preserve != null && preserve.test(model)) return new ModelIntent(model, null);
        for (String effort : List.of("max", "xhigh", "high", "medium", "low", "minimal", "none")) {
            if (model.endsWith("-" + effort)) return new ModelIntent(model.substring(0, model.length() - effort.length() - 1),
                    new Intent(effort.equals("none") ? "disabled" : "enabled", effort, null, thoughts && !effort.equals("none") ? true : null));
        }
        return new ModelIntent(model, null);
    }
    public static ModelIntent parseModifiers(String model) {
        int end = model.length();
        Map<String, String> modifiers = new LinkedHashMap<>();
        while (true) {
            int at = model.lastIndexOf('@', end - 1);
            if (at <= 0) break;
            String segment = model.substring(at + 1, end);
            int colon = segment.indexOf(':');
            if (colon <= 0 || !segment.substring(0, colon).matches("[A-Za-z][A-Za-z0-9_-]*")) break;
            modifiers.putIfAbsent(segment.substring(0, colon).toLowerCase(Locale.ROOT), segment.substring(colon + 1));
            end = at;
        }
        if (modifiers.isEmpty()) return new ModelIntent(model, null);
        String mode = "", effort = modifiers.getOrDefault("effort", "");
        Integer budget = null; Boolean thoughts = null;
        for (var entry : modifiers.entrySet()) {
            switch (entry.getKey()) {
                case "thinking" -> {
                    String v = entry.getValue().toLowerCase(Locale.ROOT);
                    switch (v) {
                        case "on" -> mode = "enabled";
                        case "adaptive" -> mode = "adaptive";
                        case "off" -> { mode = "disabled"; effort = "none"; }
                        default -> {
                            try { budget = Integer.valueOf(v); mode = budget == 0 ? "disabled" : "enabled"; }
                            catch (NumberFormatException e) { throw new ConversionException("无效 @thinking 修饰符"); }
                        }
                    }
                }
                case "effort" -> { }
                case "include_thoughts" -> {
                    if (!entry.getValue().equals("true") && !entry.getValue().equals("false"))
                        throw new ConversionException("include_thoughts 必须为 true 或 false");
                    thoughts = Boolean.valueOf(entry.getValue());
                }
                default -> throw new ConversionException("未知模型修饰符：" + entry.getKey());
            }
        }
        return new ModelIntent(model.substring(0, end), new Intent(mode, effort, budget, thoughts));
    }
    static Intent read(Format format, JsonObject req) {
        JsonObject config;
        String mode = "", effort = ""; Integer budget = null; Boolean thoughts = null;
        switch (format) {
            case OPENAI_CHAT -> {
                effort = s(req, "reasoning_effort"); config = o(req, "reasoning");
                if (effort.isEmpty()) effort = s(config, "effort");
                if (has(config, "max_tokens")) budget = Math.toIntExact(n(config, "max_tokens"));
                if (has(config, "enabled")) mode = b(config, "enabled") ? "enabled" : "disabled";
                if (has(config, "exclude")) thoughts = !b(config, "exclude");
                if (has(o(req, "thinking"), "type")) mode = s(o(req, "thinking"), "type");
            }
            case OPENAI_RESPONSES -> {
                config = o(req, "reasoning"); effort = s(config, "effort");
                if (has(config, "summary")) thoughts = !s(config, "summary").equals("none");
                if (has(config, "budget_tokens")) budget = Math.toIntExact(n(config, "budget_tokens"));
            }
            case CLAUDE -> {
                config = o(req, "thinking"); mode = s(config, "type"); effort = s(o(req, "output_config"), "effort");
                if (has(config, "budget_tokens")) budget = Math.toIntExact(n(config, "budget_tokens"));
                if (has(config, "display")) thoughts = !s(config, "display").equals("omitted");
            }
            case GEMINI -> {
                config = o(o(req, "generationConfig"), "thinkingConfig"); effort = s(config, "thinkingLevel");
                if (has(config, "thinkingBudget")) budget = Math.toIntExact(n(config, "thinkingBudget"));
                if (has(config, "includeThoughts")) thoughts = b(config, "includeThoughts");
            }
        }
        Intent intent = new Intent(mode, effort, budget, thoughts);
        return intent.empty() ? null : intent;
    }
    static String effortFromBudget(int budget) {
        if (budget == 0) return "none";
        if (budget <= 1024 && budget > 0) return "low";
        if (budget <= 8192 && budget > 0) return "medium";
        return "high";
    }
    private static int budgetFromEffort(String effort) {
        return switch (effort) {
            case "none" -> 0; case "minimal" -> 512; case "low" -> 1024;
            case "medium" -> 8192; case "xhigh", "max" -> 32768; default -> 24576;
        };
    }
    static void render(Intent intent, Format target, JsonObject req, Work w) {
        if (intent == null || intent.empty()) return;
        String effort = intent.effort();
        if (effort.equals("ultra")) {
            effort = "max";
            w.warn("reasoning_effort_mapped", "reasoning", "The provider-specific ultra effort was normalized to max");
        }
        if (effort.isEmpty() && intent.budget() != null) effort = effortFromBudget(intent.budget());
        if (intent.disabled()) effort = "none";
        switch (target) {
            case OPENAI_CHAT, OPENAI_RESPONSES -> {
                if (effort.isEmpty() && !intent.mode().isEmpty()) effort = "high";
                if (target == Format.OPENAI_CHAT) {
                    if (!effort.isEmpty()) put(req, "reasoning_effort", effort);
                    if (w.ctx.options.openRouterDialect) req.add("reasoning", obj("effort", effort, "exclude", Boolean.FALSE.equals(intent.includeThoughts())));
                } else {
                    JsonObject r = obj();
                    if (!effort.isEmpty()) put(r, "effort", effort);
                    if (intent.includeThoughts() != null) put(r, "summary", intent.includeThoughts() ? "auto" : "none");
                    req.add("reasoning", r);
                }
                if (intent.budget() != null) w.warn("reasoning_budget_approximated", "reasoning", "目标协议以 effort 表达精确预算；上下文仍保留原预算");
            }
            case CLAUDE -> {
                String model = s(req, "model");
                boolean adaptive = model.matches(".*claude-(opus-(4-[678]|5)|sonnet-(4-6|5)|fable-5|mythos).*");
                boolean noManual = model.matches(".*claude-(opus-(4-[78]|5)|sonnet-5|fable-5|mythos-5).*");
                boolean cannotDisable = model.matches(".*claude-(opus-5-5|sonnet-5-5|fable-5|mythos).*");
                JsonObject thinking;
                if (intent.disabled() && cannotDisable) {
                    thinking = obj("type", model.contains("sonnet-5-5") ? "between_tools" : "adaptive");
                    effort = "low";
                    w.warn("claude_thinking_disable_unsupported", "thinking", "该模型不能关闭推理，使用最低可表达模式");
                } else if (intent.disabled()) thinking = obj("type", "disabled");
                else if ((adaptive && intent.budget() == null) || noManual) {
                    thinking = obj("type", "adaptive");
                    if (effort.isEmpty()) effort = "high";
                    if (intent.budget() != null) w.warn("claude_budget_adjusted", "thinking", "该模型使用 adaptive，预算转换为 effort");
                } else {
                    int max = Math.toIntExact(n(req, "max_tokens"));
                    int budget = intent.budget() != null && intent.budget() >= 0 ? intent.budget()
                            : !effort.isEmpty() ? budgetFromEffort(effort) : (int) (max * w.ctx.options.thinkingBudgetPercentage);
                    if (budget < 1024) { budget = 1024; w.warn("claude_budget_adjusted", "thinking.budget_tokens", "Claude 手动推理预算至少 1024"); }
                    if (max <= budget) {
                        if (budget == Integer.MAX_VALUE) throw new ConversionException("推理预算过大");
                        put(req, "max_tokens", budget + 1); w.warn("claude_max_tokens_raised", "max_tokens", "max_tokens 已提高以容纳推理预算");
                    }
                    thinking = obj("type", "enabled", "budget_tokens", budget);
                }
                if (intent.includeThoughts() != null && !s(thinking, "type").equals("disabled") && !s(thinking, "type").equals("between_tools"))
                    put(thinking, "display", intent.includeThoughts() ? "summarized" : "omitted");
                req.add("thinking", thinking);
                if (adaptive && !effort.isEmpty() && !effort.equals("none")) {
                    JsonObject outputConfig = o(req, "output_config");
                    put(outputConfig, "effort", effort.equals("minimal") ? "low" : effort); req.add("output_config", outputConfig);
                }
                if (!intent.disabled() || noManual) {
                    if (req.has("temperature") || req.has("top_p") || req.has("top_k")) w.warn("claude_sampling_removed", "temperature/top_p/top_k", "推理模式移除不兼容的采样参数");
                    req.remove("temperature"); req.remove("top_k");
                    if (noManual || (has(req, "top_p") && req.get("top_p").getAsDouble() < 0.95)) req.remove("top_p");
                }
            }
            case GEMINI -> {
                JsonObject config = o(req, "generationConfig"), thinking = obj();
                String model = w.model(req);
                if (model.contains("gemini-3")) {
                    if (effort.isEmpty()) effort = intent.disabled() ? "minimal" : "high";
                    if (effort.equals("none") || effort.equals("minimal")) effort = model.contains("pro") ? "low" : "minimal";
                    if (effort.equals("max") || effort.equals("xhigh")) effort = "high";
                    if (model.contains("pro") && effort.equals("medium")) effort = "high";
                    put(thinking, "thinkingLevel", effort);
                    if (intent.budget() != null) w.warn("gemini_budget_to_level", "generationConfig.thinkingConfig", "Gemini 3 使用 thinkingLevel");
                } else {
                    int budget = intent.disabled() ? 0 : intent.budget() != null ? intent.budget() : effort.isEmpty() ? -1 : budgetFromEffort(effort);
                    int limit = model.contains("pro") ? 32768 : 24576;
                    int adjusted = budget;
                    if (budget > limit) adjusted = limit;
                    if (model.contains("2.5-pro") && budget >= 0 && budget < 128) adjusted = 128;
                    if (adjusted != budget) w.warn("gemini_budget_adjusted", "generationConfig.thinkingConfig", "预算已按模型能力归一化");
                    put(thinking, "thinkingBudget", adjusted);
                }
                if (intent.includeThoughts() != null) put(thinking, "includeThoughts", intent.includeThoughts());
                config.add("thinkingConfig", thinking); req.add("generationConfig", config);
            }
        }
    }
}
