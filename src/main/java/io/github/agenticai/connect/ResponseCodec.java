package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;

final class ResponseCodec {
    private ResponseCodec() {}
    static JsonObject toChat(Format source, JsonObject input, Work w) {
        if (source == Format.OPENAI_CHAT) return input.deepCopy();
        JsonObject out = Json.obj("id", Json.s(input, "id").isEmpty() ? Connect.newId("chatcmpl-") : Json.s(input, "id"),
                "object", "chat.completion", "created", Instant.now().getEpochSecond(), "model",
                Json.s(input, "model").isEmpty() ? w.ctx.upstreamModel : Json.s(input, "model"));
        JsonArray choices = new JsonArray();
        if (Json.has(input, "error")) {
            out.add("error", input.get("error").deepCopy());
            out.add("choices", choices); out.add("usage", Usage.from(source, input).toJson(Format.OPENAI_CHAT));
            w.loss("upstream_response_failed", "error", "上游响应返回错误");
            return out;
        }
        switch (source) {
            case CLAUDE -> {
                JsonObject message = claudeMessage(input, w);
                choices.add(Json.obj("index", 0, "message", message, "finish_reason", FinishReasons.fromClaude(Json.s(input, "stop_reason"))));
            }
            case GEMINI -> {
                Json.put(out, "id", Json.s(input, "responseId").isEmpty() ? Json.s(out, "id") : Json.s(input, "responseId"));
                Json.put(out, "model", Json.s(input, "modelVersion").isEmpty() ? Json.s(out, "model") : Json.s(input, "modelVersion"));
                int index = 0;
                for (JsonElement candidate : Json.a(input, "candidates")) {
                    JsonObject c = Json.o(candidate), message = geminiMessage(c, w);
                    String finish = FinishReasons.fromGemini(Json.s(c, "finishReason"));
                    if (message.has("tool_calls") && finish.equals("stop")) finish = "tool_calls";
                    choices.add(Json.obj("index", c.has("index") ? Json.n(c, "index") : index++, "message", message, "finish_reason", finish));
                    if (c.has("groundingMetadata")) w.warn("grounding_metadata_omitted", "candidates.groundingMetadata", "供应商搜索溯源结构未映射");
                }
                if (choices.isEmpty() && input.has("promptFeedback")) {
                    choices.add(Json.obj("index", 0, "message", Json.obj("role", "assistant", "content", ""), "finish_reason", "content_filter"));
                    w.warn("prompt_blocked", "promptFeedback", "Gemini 输入被供应商拦截");
                }
            }
            case OPENAI_RESPONSES -> {
                JsonArray calls = new JsonArray(), annotations = new JsonArray();
                StringBuilder text = new StringBuilder(), reasoning = new StringBuilder(), refusal = new StringBuilder();
                for (JsonElement element : Json.a(input, "output")) {
                    JsonObject item = Json.o(element);
                    switch (Json.s(item, "type")) {
                        case "message" -> {
                            for (JsonElement block : Json.a(item, "content")) {
                                JsonObject p = Json.o(block);
                                if (Json.s(p, "type").equals("refusal")) refusal.append(Json.s(p, "refusal"));
                                else if (Json.s(p, "type").equals("output_text")) {
                                    text.append(Json.s(p, "text"));
                                    for (JsonElement annotation : Json.a(p, "annotations")) annotations.add(annotation.deepCopy());
                                } else w.loss("response_content_omitted", "output.content", "未知响应内容：" + Json.s(p, "type"));
                            }
                        }
                        case "reasoning" -> {
                            for (JsonElement summary : Json.a(item, "summary")) reasoning.append(Json.s(Json.o(summary), "text"));
                            if (item.has("encrypted_content")) w.warn("encrypted_reasoning_omitted", "output", "供应商加密推理未跨协议传递");
                        }
                        case "function_call", "custom_tool_call" -> {
                            String name = ToolCodec.encodeName(Json.s(item, "name"), Json.s(item, "namespace"), w.ctx);
                            String args = Json.s(item, "type").equals("custom_tool_call")
                                    ? Json.stringify(Json.obj("input", Json.s(item, "input"))) : Json.s(item, "arguments");
                            calls.add(RequestCodec.call(Json.s(item, "call_id"), name, args));
                        }
                        default -> w.loss("hosted_tool_artifact_omitted", "output", "托管工具产物不能映射：" + Json.s(item, "type"));
                    }
                }
                JsonObject message = Json.obj("role", "assistant", "content", text.toString());
                if (reasoning.length() > 0) Json.put(message, "reasoning_content", reasoning.toString());
                if (refusal.length() > 0) Json.put(message, "refusal", refusal.toString());
                if (!calls.isEmpty()) message.add("tool_calls", calls);
                if (!annotations.isEmpty()) message.add("annotations", annotations);
                String finish = Json.s(input, "status").equals("incomplete")
                        ? Json.s(Json.o(input, "incomplete_details"), "reason").equals("content_filter") ? "content_filter" : "length"
                        : !calls.isEmpty() ? "tool_calls" : "stop";
                if (Json.s(input, "status").equals("failed") || input.has("error") && !input.get("error").isJsonNull()) {
                    out.add("error", input.get("error")); w.loss("upstream_response_failed", "error", "上游 Responses 返回失败");
                }
                choices.add(Json.obj("index", 0, "message", message, "finish_reason", finish));
            }
            default -> throw new IllegalArgumentException("源格式");
        }
        out.add("choices", choices);
        out.add("usage", Usage.from(source, input).toJson(Format.OPENAI_CHAT));
        return out;
    }
    static JsonObject claudeMessage(JsonObject input, Work w) {
        StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        JsonArray calls = new JsonArray(), media = new JsonArray();
        for (JsonElement element : Json.a(input, "content")) {
            JsonObject p = Json.o(element);
            switch (Json.s(p, "type")) {
                case "text" -> { text.append(Json.s(p, "text")); if (p.has("citations")) w.warn("citations_omitted", "content.citations", "Claude 引用结构未映射"); }
                case "thinking" -> reasoning.append(Json.s(p, "thinking"));
                case "tool_use" -> calls.add(RequestCodec.call(Json.s(p, "id"), Json.s(p, "name"), Json.stringify(p.has("input") ? p.get("input") : Json.obj())));
                case "image", "document" -> { for (JsonElement part : ContentCodec.toChat(Format.CLAUDE, Json.arr(p), w)) media.add(part); }
                case "redacted_thinking" -> w.warn("redacted_thinking_omitted", "content", "加密思考块未映射");
                default -> w.loss("hosted_tool_artifact_omitted", "content", "供应商托管产物未映射：" + Json.s(p, "type"));
            }
        }
        JsonObject message = Json.obj("role", "assistant", "content", text.toString());
        if (!media.isEmpty()) { if (text.length() > 0) media.add(Json.obj("type", "text", "text", text.toString())); message.add("content", media); }
        if (reasoning.length() > 0) Json.put(message, "reasoning_content", reasoning.toString());
        if (!calls.isEmpty()) message.add("tool_calls", calls);
        return message;
    }
    static JsonObject geminiMessage(JsonObject candidate, Work w) {
        StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        JsonArray calls = new JsonArray(), media = new JsonArray();
        for (JsonElement element : Json.a(Json.o(candidate, "content"), "parts")) {
            JsonObject p = Json.o(element);
            if (p.has("text")) { if (Json.b(p, "thought")) reasoning.append(Json.s(p, "text")); else text.append(Json.s(p, "text")); }
            else if (p.has("functionCall")) {
                JsonObject f = Json.o(p, "functionCall");
                calls.add(RequestCodec.call(Json.s(f, "id").isEmpty() ? Connect.newId("call_") : Json.s(f, "id"), Json.s(f, "name"),
                        Json.stringify(f.has("args") ? f.get("args") : Json.obj())));
            } else for (JsonElement part : ContentCodec.toChat(Format.GEMINI, Json.arr(p), w)) media.add(part);
        }
        JsonObject message = Json.obj("role", "assistant", "content", text.toString());
        if (!media.isEmpty()) { if (text.length() > 0) media.add(Json.obj("type", "text", "text", text.toString())); message.add("content", media); }
        if (reasoning.length() > 0) Json.put(message, "reasoning_content", reasoning.toString());
        if (!calls.isEmpty()) message.add("tool_calls", calls);
        return message;
    }
    static JsonObject fromChat(Format target, JsonObject chat, Usage usage, Work w) {
        if (target == Format.OPENAI_CHAT) {
            JsonObject out = chat.deepCopy(); out.add("usage", usage.toJson(target)); return out;
        }
        JsonArray choices = Json.a(chat, "choices");
        if (choices.size() > 1 && target != Format.GEMINI) w.loss("multiple_choices_omitted", "choices", "目标响应仅映射首个候选");
        JsonObject choice = choices.isEmpty() ? Json.obj() : Json.o(choices.get(0));
        JsonObject message = Json.o(choice, "message");
        String id = Json.s(chat, "id"), model = Json.s(chat, "model"), finish = Json.s(choice, "finish_reason");
        String reasoning = Json.s(message, "reasoning_content");
        return switch (target) {
            case CLAUDE -> {
                JsonArray blocks = new JsonArray();
                if (!reasoning.isEmpty()) blocks.add(Json.obj("type", "thinking", "thinking", reasoning, "signature", ""));
                JsonElement content = ContentCodec.fromChat(target, message.get("content"), "assistant", w);
                if (content.isJsonPrimitive() && !content.getAsString().isEmpty()) blocks.add(Json.obj("type", "text", "text", content.getAsString()));
                else for (JsonElement p : Json.a(content)) blocks.add(p);
                for (JsonElement element : Json.a(message, "tool_calls")) {
                    JsonObject c = Json.o(element), f = Json.o(c, "function");
                    blocks.add(Json.obj("type", "tool_use", "id", Json.s(c, "id"), "name", Json.s(f, "name"), "input", RequestCodec.parseArgs(Json.s(f, "arguments"))));
                }
                if (message.has("refusal")) {
                    blocks.add(Json.obj("type", "text", "text", Json.s(message, "refusal"))); finish = "content_filter";
                }
                JsonObject out = Json.obj("id", id, "type", "message", "role", "assistant", "model", model, "content", blocks,
                        "stop_reason", FinishReasons.toClaude(finish.isEmpty() ? "stop" : finish), "stop_sequence", null, "usage", usage.toJson(target));
                if (chat.has("error")) out = Json.obj("type", "error", "error", chat.get("error"));
                if (message.has("annotations")) w.warn("annotations_omitted", "message.annotations", "Claude 引用结构与 OpenAI 不同");
                yield out;
            }
            case OPENAI_RESPONSES -> {
                JsonArray output = new JsonArray();
                if (!reasoning.isEmpty()) output.add(Json.obj("id", Connect.newId("rs_"), "type", "reasoning",
                        "summary", Json.arr(Json.obj("type", "summary_text", "text", reasoning))));
                JsonArray content = Json.a(ContentCodec.fromChat(target, message.get("content"), "assistant", w));
                for (JsonElement p : content) if (Json.s(Json.o(p), "type").equals("output_text")) {
                    JsonObject block = Json.o(p);
                    if (!block.has("annotations")) block.add("annotations", message.has("annotations") ? message.get("annotations").deepCopy() : new JsonArray());
                }
                if (message.has("refusal")) content.add(Json.obj("type", "refusal", "refusal", Json.s(message, "refusal")));
                if (!content.isEmpty()) output.add(Json.obj("id", Connect.newId("msg_"), "type", "message", "role", "assistant", "status", "completed", "content", content));
                for (JsonElement element : Json.a(message, "tool_calls")) {
                    JsonObject c = RequestCodec.responsesCall(Json.o(element), w);
                    Json.put(c, "id", Connect.newId("fc_")); Json.put(c, "status", "completed"); output.add(c);
                }
                boolean incomplete = List.of("length", "content_filter").contains(finish);
                JsonObject out = Json.obj("id", id.startsWith("resp_") ? id : "resp_" + id, "object", "response",
                        "created_at", Json.n(chat, "created"), "model", model, "status", chat.has("error") ? "failed" : incomplete ? "incomplete" : "completed",
                        "output", output, "usage", usage.toJson(target), "error", chat.has("error") ? chat.get("error") : null,
                        "incomplete_details", incomplete ? Json.obj("reason", finish.equals("length") ? "max_output_tokens" : "content_filter") : null);
                yield out;
            }
            case GEMINI -> {
                JsonArray candidates = new JsonArray();
                for (JsonElement element : choices) {
                    JsonObject c = Json.o(element), m = Json.o(c, "message");
                    JsonArray parts = Json.a(ContentCodec.fromChat(target, m.get("content"), "assistant", w));
                    String thoughts = Json.s(m, "reasoning_content");
                    if (!thoughts.isEmpty()) parts.add(Json.obj("text", thoughts, "thought", true));
                    for (JsonElement tool : Json.a(m, "tool_calls")) {
                        JsonObject f = Json.o(Json.o(tool), "function");
                        parts.add(Json.obj("functionCall", Json.obj("name", Json.s(f, "name"), "id", Json.s(Json.o(tool), "id"), "args", RequestCodec.parseArgs(Json.s(f, "arguments")))));
                    }
                    candidates.add(Json.obj("index", Json.n(c, "index"), "content", Json.obj("role", "model", "parts", parts),
                            "finishReason", FinishReasons.toGemini(Json.s(c, "finish_reason").isEmpty() ? "stop" : Json.s(c, "finish_reason"))));
                }
                JsonObject out = Json.obj("responseId", id, "modelVersion", model, "candidates", candidates, "usageMetadata", usage.toJson(target));
                if (chat.has("error")) out.add("error", chat.get("error"));
                yield out;
            }
            default -> throw new IllegalArgumentException("目标格式");
        };
    }
}
