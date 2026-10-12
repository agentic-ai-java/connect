package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** DeepSeek Chat rules checked against the public API documentation on 2026-10-12. */
final class DeepSeekChatProfile implements ProviderProfile {
    static final ProviderProfile INSTANCE = new DeepSeekChatProfile();
    private DeepSeekChatProfile() {}
    @Override public boolean rendersReasoning() { return true; }

    @Override public void adapt(JsonObject request, Adaptation adaptation) {
        if (adaptation.to() != Format.OPENAI_CHAT)
            throw new ConversionException("The DeepSeek Chat profile requires an OPENAI_CHAT target");
        if (Json.s(request, "model").isBlank() || !Json.has(request, "messages")
                || !request.get("messages").isJsonArray() || Json.a(request, "messages").isEmpty())
            throw new ConversionException("DeepSeek Chat requires a model and a nonempty messages array");

        Reasoning.Intent intent = adaptation.reasoning();
        boolean thinking = intent == null || !intent.disabled();
        if (intent != null) {
            String effort = intent.disabled() ? "none" : intent.effort();
            if (effort.isEmpty() && intent.budget() != null) effort = Reasoning.effortFromBudget(intent.budget());
            String mapped = switch (effort) {
                case "minimal" -> "low";
                case "medium", "xhigh" -> "high";
                case "ultra" -> "max";
                default -> effort;
            };
            if (!mapped.equals(effort)) adaptation.warning("deepseek_effort_mapped", "reasoning_effort",
                    "DeepSeek maps " + effort + " to " + mapped);
            JsonObject toggle = Json.o(request, "thinking").deepCopy();
            Json.put(toggle, "type", thinking ? "enabled" : "disabled");
            request.add("thinking", toggle);
            if (!mapped.isEmpty()) Json.put(request, "reasoning_effort", mapped);
            else request.remove("reasoning_effort");
            if (request.has("reasoning")) {
                request.remove("reasoning");
                adaptation.warning("provider_reasoning_dialect_removed", "reasoning",
                        "DeepSeek Chat uses thinking and reasoning_effort rather than the reasoning object");
            }
            if (intent.budget() != null) adaptation.warning("reasoning_budget_approximated", "reasoning",
                    "DeepSeek Chat expresses reasoning budgets through effort, not exact token budgets");
            if (intent.includeThoughts() != null) adaptation.warning("reasoning_visibility_unsupported", "reasoning",
                    "DeepSeek Chat does not expose a portable reasoning visibility control");
        }

        if (Json.has(request, "max_completion_tokens")) {
            if (Json.has(request, "max_tokens") && !request.get("max_tokens").equals(request.get("max_completion_tokens")))
                adaptation.warning("provider_token_limit_overridden", "max_tokens",
                        "max_completion_tokens takes precedence over max_tokens");
            request.add("max_tokens", request.remove("max_completion_tokens"));
        }
        if (Json.has(request, "max_tokens")) {
            double limit = number(request, "max_tokens");
            if (limit <= 0 || request.get("max_tokens").getAsBigDecimal().stripTrailingZeros().scale() > 0)
                throw new ConversionException("max_tokens must be a positive integer");
        }

        for (String field : List.of("presence_penalty", "frequency_penalty")) ignored(request, field, adaptation);
        if (thinking) ignored(request, "temperature", adaptation);
        if (Json.has(request, "top_p")) {
            double topP = number(request, "top_p");
            if (topP <= 0 || topP > 1)
                throw new ConversionException("top_p must be greater than zero and at most one");
            if (!thinking) ignored(request, "top_p", adaptation);
            else if (topP < 0.95) {
                Json.put(request, "top_p", 0.95);
                adaptation.warning("deepseek_top_p_adjusted", "top_p", "DeepSeek thinking mode clamps top_p to at least 0.95");
            }
        }
        if (thinking && Json.has(request, "tool_choice")) {
            JsonElement choice = request.get("tool_choice");
            if (choice.isJsonObject() || choice.isJsonPrimitive() && choice.getAsString().equals("required"))
                adaptation.error("deepseek_thinking_tool_choice_unsupported", "tool_choice",
                        "Forced tool choices require thinking to be disabled; the requested choice was not changed");
        }
        if (Json.s(Json.o(request, "response_format"), "type").equals("json_schema"))
            adaptation.error("deepseek_response_format_unsupported", "response_format",
                    "DeepSeek Chat documents text and json_object output, not json_schema; no schema was discarded");
        if (Json.has(request, "functions") || Json.has(request, "function_call"))
            adaptation.error("deepseek_legacy_tools_unsupported", "functions",
                    "DeepSeek Chat requires tools and tool_choice; legacy function fields were preserved for inspection");

        JsonArray messages = Json.a(request, "messages");
        for (int i = 0; i < messages.size(); i++) {
            JsonObject message = Json.o(messages.get(i));
            if (!Json.s(message, "role").equals("assistant")) continue;
            JsonElement content = message.get("content");
            if (content != null && content.isJsonArray()) {
                boolean textOnly = true;
                for (JsonElement part : content.getAsJsonArray())
                    if (!Json.s(Json.o(part), "type").equals("text")) textOnly = false;
                if (textOnly) Json.put(message, "content", ContentCodec.text(content));
                else adaptation.error("deepseek_assistant_content_unsupported", "messages[" + i + "].content",
                        "DeepSeek Chat assistant content must be text or null; non-text content was preserved for inspection");
            } else if (content != null && !content.isJsonNull() && (!content.isJsonPrimitive()
                    || !content.getAsJsonPrimitive().isString()))
                adaptation.error("deepseek_assistant_content_unsupported", "messages[" + i + "].content",
                        "DeepSeek Chat assistant content must be text or null; the original content was preserved for inspection");
            if (thinking && !Json.a(request, "tools").isEmpty()) {
                JsonElement reasoning = message.get("reasoning_content");
                if (reasoning == null || reasoning.isJsonNull() || !reasoning.isJsonPrimitive()
                        || !reasoning.getAsJsonPrimitive().isString() || reasoning.getAsString().isEmpty())
                    adaptation.error("deepseek_reasoning_history_missing", "messages[" + i + "].reasoning_content",
                            "Thinking requests with tools require complete assistant reasoning history; it cannot be synthesized");
            }
        }
    }
    private static double number(JsonObject request, String field) {
        JsonElement value = request.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))
            throw new ConversionException(field + " must be a finite number");
        return value.getAsDouble();
    }
    private static void ignored(JsonObject request, String field, Adaptation adaptation) {
        if (Json.has(request, field)) adaptation.warning("provider_parameter_ignored", field,
                "DeepSeek ignores this parameter in the selected mode; the original value was preserved");
    }
}
