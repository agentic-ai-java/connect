package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Keeps assistant reasoning separate from visible content and preserves tool-turn boundaries. */
final class RequestHistory {
    private RequestHistory() {}

    static void normalizeChat(JsonObject request, Work w) {
        for (JsonElement element : Json.a(request, "messages")) {
            JsonObject message = Json.o(element);
            if (!Json.s(message, "role").equals("assistant")) continue;
            JsonElement content = message.get("content");
            if (content == null || !content.isJsonArray()) continue;
            JsonArray visible = new JsonArray();
            StringBuilder reasoning = new StringBuilder();
            for (JsonElement block : content.getAsJsonArray()) {
                JsonObject part = Json.o(block);
                if (!Json.s(part, "type").equals("thinking")) {
                    visible.add(block.deepCopy());
                    continue;
                }
                reasoning.append(Json.s(part, "text"));
                if (!Json.s(part, "signature").isEmpty() || !Json.s(part, "thoughtSignature").isEmpty())
                    w.warn("reasoning_signature_omitted", "messages.content", "Chat cannot replay provider-specific thinking signatures");
            }
            if (!visible.equals(content)) {
                message.add("content", visible.isEmpty() ? JsonNull.INSTANCE : visible);
                if (!reasoning.isEmpty()) {
                    String existing = Json.s(message, "reasoning_content");
                    if (!existing.isEmpty() && !existing.equals(reasoning.toString()))
                        w.loss("reasoning_history_conflict", "messages.reasoning_content", "Existing reasoning_content differs from thinking blocks; the explicit field wins");
                    else Json.put(message, "reasoning_content", reasoning.toString());
                }
            }
        }
    }

    /** A reasoning item and adjacent calls/message belong to one assistant turn, until a result or another role. */
    static void responses(JsonArray input, JsonArray messages, Work w) {
        JsonObject assistant = null;
        boolean hasMessage = false;
        for (JsonElement element : input) {
            JsonObject item = Json.o(element);
            String type = Json.s(item, "type");
            if (type.isEmpty() || type.equals("message")) {
                String role = Json.s(item, "role");
                if (role.isEmpty()) role = "user";
                JsonElement content = item.get("content");
                JsonElement converted = content != null && content.isJsonPrimitive() ? content.deepCopy()
                        : ContentCodec.toChat(Format.OPENAI_RESPONSES, content, w);
                if (role.equals("assistant")) {
                    if (assistant == null || hasMessage) { assistant = assistant(messages); hasMessage = false; }
                    assistant.add("content", converted);
                    hasMessage = true;
                } else {
                    messages.add(Json.obj("role", role, "content", converted));
                    assistant = null; hasMessage = false;
                }
            } else if (type.equals("function_call") || type.equals("custom_tool_call")) {
                if (assistant == null) assistant = assistant(messages);
                String name = ToolCodec.encodeName(Json.s(item, "name"), Json.s(item, "namespace"), w.ctx);
                String args = type.equals("custom_tool_call") ? Json.stringify(Json.obj("input", Json.s(item, "input")))
                        : Json.s(item, "arguments");
                if (!assistant.has("tool_calls")) assistant.add("tool_calls", new JsonArray());
                Json.a(assistant, "tool_calls").add(RequestCodec.call(Json.s(item, "call_id"), name, args));
                w.ctx.callNames.put(Json.s(item, "call_id"), name);
            } else if (type.equals("function_call_output") || type.equals("custom_tool_call_output")) {
                messages.add(Json.obj("role", "tool", "tool_call_id", Json.s(item, "call_id"),
                        "content", item.has("output") ? item.get("output").deepCopy() : new com.google.gson.JsonPrimitive("")));
                assistant = null; hasMessage = false;
            } else if (type.equals("reasoning")) {
                if (hasMessage) { assistant = null; hasMessage = false; }
                // Summaries are not full reasoning and must never satisfy DeepSeek's replay requirement.
                StringBuilder text = new StringBuilder();
                for (JsonElement part : Json.a(item, "content")) {
                    JsonObject block = Json.o(part);
                    if (Json.s(block, "type").equals("reasoning_text")) text.append(Json.s(block, "text"));
                    else w.loss("reasoning_content_omitted", "input.content", "Unsupported reasoning content cannot be replayed as plain text");
                }
                if (!text.isEmpty()) {
                    if (assistant == null) assistant = assistant(messages);
                    Json.put(assistant, "reasoning_content", Json.s(assistant, "reasoning_content") + text);
                } else if (!Json.a(item, "summary").isEmpty())
                    w.loss("reasoning_summary_not_replayable", "input.summary", "A reasoning summary is not complete assistant reasoning; no history was synthesized");
                if (item.has("encrypted_content"))
                    w.loss("encrypted_reasoning_omitted", "input.encrypted_content", "Chat cannot replay encrypted reasoning history");
            } else {
                w.loss("responses_input_item_omitted", "input", "Unsupported Responses input item: " + type);
                assistant = null; hasMessage = false;
            }
        }
    }

    private static JsonObject assistant(JsonArray messages) {
        JsonObject message = Json.obj("role", "assistant", "content", JsonNull.INSTANCE);
        messages.add(message);
        return message;
    }

    /** Plain Chat reasoning is not a signed native replay block. Diagnose rather than invent a signature. */
    static JsonElement targetContent(JsonObject message, Format target, Work w) {
        JsonElement content = message.get("content");
        if (!Json.s(message, "role").equals("assistant")) return content;
        String reasoning = Json.s(message, "reasoning_content");
        if (!reasoning.isEmpty())
            w.loss("reasoning_history_not_replayable", "messages.reasoning_content",
                    "Plain Chat reasoning cannot be replayed as native " + target.wireName() + " reasoning; it was not inserted into visible content");
        if (content == null || !content.isJsonArray()) return content;
        JsonArray filtered = new JsonArray();
        for (JsonElement block : content.getAsJsonArray()) {
            JsonObject part = Json.o(block);
            if (!Json.s(part, "type").equals("thinking")) { filtered.add(block.deepCopy()); continue; }
            boolean nativeSignature = target == Format.CLAUDE && !Json.s(part, "signature").isEmpty()
                    || target == Format.GEMINI && !Json.s(part, "thoughtSignature").isEmpty();
            if (nativeSignature) filtered.add(block.deepCopy());
            else w.loss("reasoning_history_not_replayable", "messages.content", "Thinking history lacks a target-native signature and was omitted");
        }
        return filtered;
    }
}
