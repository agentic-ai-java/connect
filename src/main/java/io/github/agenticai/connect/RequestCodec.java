package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RequestCodec {
    private RequestCodec() {}
    private static final Set<String> CHAT_FIELDS = Set.of("model", "messages", "stream", "stream_options", "max_tokens",
            "max_completion_tokens", "temperature", "top_p", "top_k", "stop", "tools", "tool_choice", "functions",
            "function_call", "parallel_tool_calls", "response_format", "reasoning_effort", "reasoning", "n",
            "web_search_options", "user", "metadata", "seed", "presence_penalty", "frequency_penalty", "logprobs",
            "top_logprobs", "logit_bias", "store", "service_tier");
    private static final Set<String> RESPONSES_FIELDS = Set.of("model", "input", "instructions", "stream", "max_output_tokens",
            "temperature", "top_p", "tools", "tool_choice", "parallel_tool_calls", "text", "reasoning", "user", "metadata",
            "store", "service_tier", "previous_response_id", "conversation", "background", "truncation", "include");
    private static final Set<String> CLAUDE_FIELDS = Set.of("model", "system", "messages", "max_tokens", "stream",
            "temperature", "top_p", "top_k", "tools", "tool_choice", "stop_sequences", "thinking", "output_config", "metadata");
    private static final Set<String> GEMINI_FIELDS = Set.of("model", "contents", "systemInstruction", "system_instruction",
            "generationConfig", "tools", "toolConfig", "safetySettings", "cachedContent");
    static JsonObject convert(Format source, Format target, JsonObject input, Work w) {
        w.requestModel = w.model(input);
        Reasoning.Intent intent = Reasoning.read(source, input);
        if (w.ctx.reasoning != null) {
            if (intent != null && !intent.equals(w.ctx.reasoning)) w.warn("reasoning_overridden", "reasoning", "上下文推理设置覆盖请求设置");
            intent = w.ctx.reasoning;
        }
        w.ctx.reasoning = intent;
        JsonObject chat = toChat(source, input, w);
        JsonObject output = fromChat(target, chat, w);
        Reasoning.render(intent, target, output, w);
        return output;
    }
    private static JsonObject base(JsonObject input, Work w) {
        JsonObject out = Json.obj();
        String model = w.model(input);
        if (model.isEmpty()) throw new ConversionException("请求必须提供模型，Gemini 可通过上下文 upstreamModel 提供");
        Json.put(out, "model", model);
        Json.copy(input, out, "stream", "temperature", "top_p", "top_k", "user", "metadata", "store", "service_tier");
        return out;
    }
    static JsonObject toChat(Format source, JsonObject input, Work w) {
        JsonObject out = base(input, w);
        JsonArray messages = new JsonArray();
        JsonArray tools = ToolCodec.toChat(source, input, w);
        Set<String> known = switch (source) {
            case OPENAI_CHAT -> CHAT_FIELDS; case OPENAI_RESPONSES -> RESPONSES_FIELDS;
            case CLAUDE -> CLAUDE_FIELDS; case GEMINI -> GEMINI_FIELDS;
        };
        for (String field : input.keySet()) if (!known.contains(field))
            w.warn("unsupported_request_field", field, "未映射请求字段");
        switch (source) {
            case OPENAI_CHAT -> {
                if (!input.has("messages") || !input.get("messages").isJsonArray()) throw new ConversionException("messages 必须是数组");
                out = input.deepCopy();
                Json.put(out, "model", w.model(input));
                out.add("tools", tools);
                if (out.has("functions")) out.remove("functions");
                if (out.has("function_call") && !out.has("tool_choice")) out.add("tool_choice", out.remove("function_call"));
                return out;
            }
            case OPENAI_RESPONSES -> {
                if (!input.has("input")) throw new ConversionException("Responses 请求缺少 input");
                if (input.has("instructions")) messages.add(Json.obj("role", "system", "content", input.get("instructions")));
                JsonElement raw = input.get("input");
                if (raw.isJsonPrimitive()) messages.add(Json.obj("role", "user", "content", raw));
                else if (!raw.isJsonArray()) throw new ConversionException("Responses input 必须是字符串或数组");
                else for (JsonElement element : raw.getAsJsonArray()) {
                    JsonObject item = Json.o(element);
                    String type = Json.s(item, "type");
                    if (type.isEmpty() || type.equals("message")) {
                        String role = Json.s(item, "role"); if (role.isEmpty()) role = "user";
                        JsonElement content = item.get("content");
                        messages.add(Json.obj("role", role, "content", content != null && content.isJsonPrimitive()
                                ? content : ContentCodec.toChat(source, content, w)));
                    } else if (type.equals("function_call") || type.equals("custom_tool_call")) {
                        String name = ToolCodec.encodeName(Json.s(item, "name"), Json.s(item, "namespace"), w.ctx);
                        String arguments = type.equals("custom_tool_call") ? Json.stringify(Json.obj("input", Json.s(item, "input"))) : Json.s(item, "arguments");
                        addCallMessage(messages, Json.s(item, "call_id"), name, arguments, null, w);
                    } else if (type.equals("function_call_output") || type.equals("custom_tool_call_output")) {
                        messages.add(Json.obj("role", "tool", "tool_call_id", Json.s(item, "call_id"),
                                "content", item.has("output") ? item.get("output") : new com.google.gson.JsonPrimitive("")));
                    } else if (type.equals("reasoning")) {
                        StringBuilder reasoning = new StringBuilder();
                        for (JsonElement summary : Json.a(item, "summary")) reasoning.append(Json.s(Json.o(summary), "text"));
                        if (reasoning.length() > 0) messages.add(Json.obj("role", "assistant", "content", "", "reasoning_content", reasoning.toString()));
                        if (item.has("encrypted_content")) w.warn("encrypted_reasoning_omitted", "input.encrypted_content", "目标协议无法使用加密推理历史");
                    } else w.loss("responses_input_item_omitted", "input", "不能表达 Responses 输入项：" + type);
                }
                Json.rename(input, "max_output_tokens", out, "max_completion_tokens");
                Json.copy(input, out, "parallel_tool_calls");
                if (input.has("tool_choice")) {
                    JsonElement choice = input.get("tool_choice");
                    if (choice.isJsonObject()) {
                        JsonObject c = Json.o(choice);
                        String type = Json.s(c, "type");
                        if (type.equals("function") || type.equals("custom")) out.add("tool_choice", Json.obj("type", "function", "function",
                                Json.obj("name", ToolCodec.encodeName(Json.s(c, "name"), Json.s(c, "namespace"), w.ctx))));
                        else { w.loss("tool_choice_omitted", "tool_choice", "目标协议无法表达工具选择：" + type); }
                    } else out.add("tool_choice", choice.deepCopy());
                }
                JsonObject format = Json.o(Json.o(input, "text"), "format");
                if (format.size() > 0) {
                    if (Json.s(format, "type").equals("json_schema")) {
                        JsonObject schema = format.deepCopy(); schema.remove("type");
                        out.add("response_format", Json.obj("type", "json_schema", "json_schema", schema));
                    } else out.add("response_format", format.deepCopy());
                }
                for (String key : List.of("previous_response_id", "conversation", "background", "truncation", "include"))
                    if (input.has(key)) w.loss("responses_state_omitted", key, "目标协议无法表达 Responses 服务端状态或控制字段");
            }
            case CLAUDE -> {
                if (input.has("system")) {
                    JsonElement system = input.get("system");
                    messages.add(Json.obj("role", "system", "content", system.isJsonPrimitive() ? system : ContentCodec.toChat(source, system, w)));
                }
                for (JsonElement element : Json.a(input, "messages")) {
                    JsonObject item = Json.o(element);
                    String role = Json.s(item, "role");
                    JsonElement content = item.get("content");
                    JsonArray normal = ContentCodec.toChat(source, content, w);
                    JsonArray calls = new JsonArray(), results = new JsonArray();
                    for (JsonElement block : Json.a(content)) {
                        JsonObject p = Json.o(block);
                        if (Json.s(p, "type").equals("tool_use")) {
                            String id = Json.s(p, "id"), name = Json.s(p, "name");
                            calls.add(call(id, name, Json.stringify(p.has("input") ? p.get("input") : Json.obj())));
                            w.ctx.callNames.put(id, name);
                        } else if (Json.s(p, "type").equals("tool_result")) {
                            JsonElement resultContent = p.get("content");
                            results.add(Json.obj("role", "tool", "tool_call_id", Json.s(p, "tool_use_id"),
                                    "content", resultContent != null && resultContent.isJsonArray()
                                            ? ContentCodec.toChat(source, resultContent, w) : resultContent));
                            if (Json.b(p, "is_error")) w.warn("tool_result_error_flag_omitted", "messages.content.is_error", "目标工具结果没有 is_error 标记");
                        }
                    }
                    if (content != null && content.isJsonPrimitive()) messages.add(Json.obj("role", role, "content", content));
                    else if (!normal.isEmpty() || !calls.isEmpty()) {
                        JsonObject message = Json.obj("role", role, "content", normal.isEmpty() ? null : normal);
                        if (!calls.isEmpty()) message.add("tool_calls", calls);
                        messages.add(message);
                    }
                    for (JsonElement result : results) messages.add(result);
                }
                Json.copy(input, out, "max_tokens"); Json.rename(input, "stop_sequences", out, "stop");
                if (input.has("tool_choice")) out.add("tool_choice", claudeChoiceToChat(Json.o(input, "tool_choice"), w));
                JsonObject format = Json.o(Json.o(input, "output_config"), "format");
                if (format.has("schema")) out.add("response_format", Json.obj("type", "json_schema",
                        "json_schema", Json.obj("name", "response", "schema", format.get("schema"))));
            }
            case GEMINI -> {
                JsonObject system = input.has("systemInstruction") ? Json.o(input, "systemInstruction") : Json.o(input, "system_instruction");
                if (system.has("parts")) messages.add(Json.obj("role", "system", "content", ContentCodec.toChat(source, system.get("parts"), w)));
                for (JsonElement element : Json.a(input, "contents")) {
                    JsonObject item = Json.o(element);
                    String role = Json.s(item, "role").equals("model") ? "assistant" : "user";
                    JsonArray normal = ContentCodec.toChat(source, item.get("parts"), w);
                    JsonArray calls = new JsonArray();
                    for (JsonElement block : Json.a(item, "parts")) {
                        JsonObject p = Json.o(block);
                        if (p.has("functionCall")) {
                            JsonObject f = Json.o(p, "functionCall");
                            String id = Json.s(f, "id"); if (id.isEmpty()) id = Connect.newId("call_");
                            calls.add(call(id, Json.s(f, "name"), Json.stringify(f.has("args") ? f.get("args") : Json.obj())));
                            w.ctx.callNames.put(id, Json.s(f, "name"));
                            if (p.has("thoughtSignature")) w.warn("thought_signature_omitted", "contents.parts.thoughtSignature", "跨协议无法保留供应商思考签名");
                        } else if (p.has("functionResponse")) {
                            JsonObject f = Json.o(p, "functionResponse");
                            String id = Json.s(f, "id");
                            if (id.isEmpty()) id = w.ctx.callNames.entrySet().stream().filter(e -> e.getValue().equals(Json.s(f, "name"))).map(Map.Entry::getKey).reduce((a, b) -> b).orElse(Json.s(f, "name"));
                            messages.add(Json.obj("role", "tool", "tool_call_id", id, "name", Json.s(f, "name"),
                                    "content", f.has("response") ? Json.stringify(f.get("response")) : ""));
                        }
                    }
                    if (!normal.isEmpty() || !calls.isEmpty()) {
                        JsonObject message = Json.obj("role", role, "content", normal.isEmpty() ? null : normal);
                        if (!calls.isEmpty()) message.add("tool_calls", calls);
                        messages.add(message);
                    }
                }
                JsonObject config = Json.o(input, "generationConfig");
                Json.rename(config, "maxOutputTokens", out, "max_tokens"); Json.rename(config, "topP", out, "top_p");
                Json.rename(config, "topK", out, "top_k"); Json.copy(config, out, "temperature");
                Json.rename(config, "stopSequences", out, "stop");
                if (Json.s(config, "responseMimeType").equals("application/json")) {
                    if (config.has("responseSchema") || config.has("responseJsonSchema")) out.add("response_format", Json.obj("type", "json_schema", "json_schema",
                            Json.obj("name", "response", "schema", config.has("responseJsonSchema") ? config.get("responseJsonSchema") : config.get("responseSchema"))));
                    else out.add("response_format", Json.obj("type", "json_object"));
                }
                JsonObject fc = Json.o(Json.o(input, "toolConfig"), "functionCallingConfig");
                switch (Json.s(fc, "mode")) {
                    case "NONE" -> Json.put(out, "tool_choice", "none");
                    case "AUTO" -> Json.put(out, "tool_choice", "auto");
                    case "ANY" -> {
                        JsonArray names = Json.a(fc, "allowedFunctionNames");
                        out.add("tool_choice", names.size() == 1 ? Json.obj("type", "function", "function", Json.obj("name", names.get(0))) : new com.google.gson.JsonPrimitive("required"));
                        if (names.size() > 1) w.loss("allowed_tool_names_approximated", "toolConfig", "多函数候选限制无法原样表达");
                    }
                    default -> { }
                }
                if (input.has("cachedContent")) w.loss("cached_content_omitted", "cachedContent", "目标协议无法使用 Gemini 服务端缓存 ID");
                if (input.has("safetySettings")) w.warn("safety_settings_omitted", "safetySettings", "目标协议无法表达 Gemini 安全阈值");
            }
        }
        out.add("messages", messages);
        out.add("tools", tools);
        return out;
    }
    static JsonObject call(String id, String name, String arguments) {
        return Json.obj("id", id, "type", "function", "function", Json.obj("name", name, "arguments", arguments));
    }
    private static void addCallMessage(JsonArray messages, String id, String name, String args, JsonElement content, Work w) {
        messages.add(Json.obj("role", "assistant", "content", content, "tool_calls", Json.arr(call(id, name, args))));
        w.ctx.callNames.put(id, name);
    }
    static JsonElement claudeChoiceToChat(JsonObject c, Work w) {
        if (Json.b(c, "disable_parallel_tool_use")) w.warn("parallel_tool_limit_approximated", "tool_choice", "禁用并行工具的控制由目标协议近似处理");
        return switch (Json.s(c, "type")) {
            case "tool" -> Json.obj("type", "function", "function", Json.obj("name", Json.s(c, "name")));
            case "any" -> new com.google.gson.JsonPrimitive("required");
            case "none" -> new com.google.gson.JsonPrimitive("none");
            default -> new com.google.gson.JsonPrimitive("auto");
        };
    }
    static JsonObject fromChat(Format target, JsonObject chat, Work w) {
        if (target == Format.OPENAI_CHAT) {
            JsonObject out = chat.deepCopy();
            out.remove("reasoning"); out.remove("reasoning_effort");
            JsonArray tools = ToolCodec.fromChat(target, Json.a(chat, "tools"), out, w);
            if (tools.isEmpty()) out.remove("tools"); else out.add("tools", tools);
            return out;
        }
        JsonObject out = base(chat, w);
        JsonArray messages = Json.a(chat, "messages"), targetMessages = new JsonArray();
        JsonArray system = new JsonArray();
        long max = Json.has(chat, "max_completion_tokens") ? Json.n(chat, "max_completion_tokens") : Json.n(chat, "max_tokens");
        boolean hasMax = Json.has(chat, "max_completion_tokens") || Json.has(chat, "max_tokens");
        if (hasMax && max <= 0) throw new ConversionException("max_tokens 必须大于 0");
        if (Json.n(chat, "n") > 1) {
            if (target == Format.OPENAI_RESPONSES) throw new ConversionException("Responses 不支持 n > 1");
            if (target == Format.CLAUDE) w.loss("multiple_choices_omitted", "n", "Claude 仅返回一个响应");
        }
        for (String field : List.of("seed", "presence_penalty", "frequency_penalty", "logprobs", "top_logprobs", "logit_bias", "stream_options")) {
            if (chat.has(field)) { out.remove(field); w.warn("unsupported_request_field", field, "目标协议不支持该请求控制"); }
        }
        if (target == Format.CLAUDE || target == Format.GEMINI) for (String field : List.of("user", "store", "service_tier")) {
            if (chat.has(field)) { out.remove(field); w.warn("unsupported_request_field", field, "目标协议不支持该请求控制"); }
        }
        for (JsonElement element : messages) {
            JsonObject message = Json.o(element);
            String role = Json.s(message, "role"); JsonElement content = message.get("content");
            if (role.equals("system") || role.equals("developer")) {
                if (target == Format.OPENAI_RESPONSES) system.add(new com.google.gson.JsonPrimitive(ContentCodec.text(content)));
                else for (JsonElement part : Json.a(ContentCodec.fromChat(target, content != null && content.isJsonPrimitive()
                        ? Json.arr(Json.obj("type", "text", "text", content.getAsString())) : content, role, w))) system.add(part);
                continue;
            }
            if (target == Format.OPENAI_RESPONSES) {
                if (role.equals("tool") || role.equals("function")) {
                    String id = Json.s(message, "tool_call_id");
                    if (id.isEmpty()) {
                        w.loss("tool_call_id_missing", "messages.tool_call_id", "工具结果缺少调用 ID");
                        targetMessages.add(Json.obj("role", "user", "content", "[tool_output_missing_call_id] " + ContentCodec.text(content))); continue;
                    }
                    ConversionContext.ToolEncoding enc = w.ctx.toolEncodings.get(w.ctx.callNames.get(id));
                    targetMessages.add(Json.obj("type", enc != null && enc.type().equals("custom") ? "custom_tool_call_output" : "function_call_output",
                            "call_id", id, "output", content == null ? "" : content.isJsonPrimitive() ? content.getAsString() : Json.stringify(content)));
                } else {
                    targetMessages.add(Json.obj("role", role, "content", content == null || content.isJsonNull() ? new com.google.gson.JsonPrimitive("")
                            : content.isJsonPrimitive() ? content : ContentCodec.fromChat(target, content, role, w)));
                    for (JsonElement tool : Json.a(message, "tool_calls")) targetMessages.add(responsesCall(Json.o(tool), w));
                }
            } else if (target == Format.CLAUDE) {
                if (role.equals("tool") || role.equals("function")) {
                    JsonObject result = Json.obj("type", "tool_result", "tool_use_id", Json.s(message, "tool_call_id"),
                            "content", content != null && content.isJsonArray() ? ContentCodec.fromChat(target, content, "user", w) : content == null ? new com.google.gson.JsonPrimitive("") : content);
                    targetMessages.add(Json.obj("role", "user", "content", Json.arr(result)));
                } else {
                    JsonElement blocks = ContentCodec.fromChat(target, content, role, w);
                    JsonArray calls = Json.a(message, "tool_calls");
                    if (!calls.isEmpty()) {
                        JsonArray expanded = blocks.isJsonArray() ? blocks.getAsJsonArray() : blocks.isJsonPrimitive()
                                ? Json.arr(Json.obj("type", "text", "text", blocks.getAsString())) : new JsonArray();
                        if (expanded.isEmpty() && w.from != Format.OPENAI_RESPONSES) expanded.add(Json.obj("type", "text", "text", "..."));
                        for (JsonElement call : calls) {
                            JsonObject c = Json.o(call), f = Json.o(c, "function");
                            expanded.add(Json.obj("type", "tool_use", "id", Json.s(c, "id"), "name", Json.s(f, "name"),
                                    "input", parseArgs(Json.s(f, "arguments"))));
                            w.ctx.callNames.put(Json.s(c, "id"), Json.s(f, "name"));
                        }
                        blocks = expanded;
                    }
                    if (blocks.isJsonArray() && blocks.getAsJsonArray().isEmpty()) blocks = new com.google.gson.JsonPrimitive("...");
                    targetMessages.add(Json.obj("role", role.equals("assistant") ? "assistant" : "user", "content", blocks));
                }
            } else {
                JsonArray parts = Json.a(ContentCodec.fromChat(target, content, role, w));
                if (role.equals("tool") || role.equals("function")) {
                    String id = Json.s(message, "tool_call_id"), name = Json.s(message, "name");
                    if (name.isEmpty()) name = w.ctx.callNames.getOrDefault(id, "");
                    if (name.isEmpty()) throw new ConversionException("Gemini 工具结果无法定位函数名：" + id);
                    JsonElement response;
                    try { response = content == null ? Json.obj() : Json.parse(ContentCodec.text(content)); }
                    catch (RuntimeException e) { response = Json.obj("result", ContentCodec.text(content)); }
                    if (!response.isJsonObject()) response = Json.obj("result", response);
                    parts = Json.arr(Json.obj("functionResponse", Json.obj("name", name, "id", id, "response", response)));
                } else {
                    for (JsonElement tool : Json.a(message, "tool_calls")) {
                        JsonObject call = Json.o(tool), f = Json.o(call, "function");
                        JsonObject part = Json.obj("functionCall", Json.obj("name", Json.s(f, "name"), "id", Json.s(call, "id"),
                                "args", parseArgs(Json.s(f, "arguments"))));
                        if (w.ctx.options.geminiFunctionCallThoughtSignature) Json.put(part, "thoughtSignature", "context_engineering_is_the_way_to_go");
                        parts.add(part); w.ctx.callNames.put(Json.s(call, "id"), Json.s(f, "name"));
                    }
                }
                if (!parts.isEmpty()) targetMessages.add(Json.obj("role", role.equals("assistant") ? "model" : "user", "parts", parts));
            }
        }
        switch (target) {
            case OPENAI_RESPONSES -> {
                StringBuilder instructions = new StringBuilder();
                for (JsonElement part : system) { if (instructions.length() > 0) instructions.append('\n'); instructions.append(part.getAsString()); }
                if (instructions.length() > 0) Json.put(out, "instructions", instructions.toString());
                out.add("input", targetMessages);
                if (hasMax) Json.put(out, "max_output_tokens", max);
                Json.copy(chat, out, "parallel_tool_calls");
                if (chat.has("tool_choice")) {
                    JsonElement c = chat.get("tool_choice");
                    if (c.isJsonObject()) {
                        String name = Json.s(Json.o(Json.o(c), "function"), "name");
                        ConversionContext.ToolEncoding e = w.ctx.toolEncodings.get(name);
                        JsonObject choice = Json.obj("type", e == null ? "function" : e.type(), "name", e == null ? name : e.name());
                        if (e != null && !e.namespace().isEmpty()) Json.put(choice, "namespace", e.namespace());
                        out.add("tool_choice", choice);
                    } else out.add("tool_choice", c.deepCopy());
                }
                JsonObject format = Json.o(chat, "response_format");
                if (format.size() > 0) {
                    JsonObject f = format.deepCopy();
                    if (Json.s(f, "type").equals("json_schema")) {
                        JsonObject nested = Json.o(f, "json_schema"); f.remove("json_schema");
                        for (var entry : nested.entrySet()) f.add(entry.getKey(), entry.getValue().deepCopy());
                    }
                    out.add("text", Json.obj("format", f));
                }
                if (chat.has("stop")) w.warn("stop_sequences_omitted", "stop", "Responses 不支持停止序列");
            }
            case CLAUDE -> {
                if (!hasMax) {
                    if (w.ctx.options.claudeDefaultMaxTokens == null) {
                        if (w.ctx.reasoning != null) max = Math.max(1280, w.ctx.reasoning.budget() == null ? 1280 : (long) w.ctx.reasoning.budget() + 1);
                        else throw new ConversionException("Claude 需要 max_tokens 或 claudeDefaultMaxTokens");
                    } else max = w.ctx.options.claudeDefaultMaxTokens.applyAsInt(Json.s(out, "model"));
                    if (max <= 0) throw new ConversionException("claudeDefaultMaxTokens 必须大于 0");
                }
                Json.put(out, "max_tokens", max);
                if (!system.isEmpty()) out.add("system", system);
                out.add("messages", targetMessages);
                if (chat.has("stop")) out.add("stop_sequences", asArray(chat.get("stop")));
                if (chat.has("tool_choice")) out.add("tool_choice", choiceToClaude(chat.get("tool_choice"), chat));
                JsonObject format = Json.o(chat, "response_format");
                if (Json.s(format, "type").equals("json_schema"))
                    out.add("output_config", Json.obj("format", Json.obj("type", "json_schema", "schema", Json.o(format, "json_schema").get("schema"))));
                else if (format.size() > 0) w.warn("response_format_approximated", "response_format", "Claude 仅映射 JSON Schema 输出格式");
            }
            case GEMINI -> {
                out.remove("model"); out.remove("stream"); out.remove("temperature"); out.remove("top_p"); out.remove("top_k");
                out.remove("user"); out.remove("metadata"); out.remove("store"); out.remove("service_tier");
                if (!system.isEmpty()) out.add("systemInstruction", Json.obj("parts", system));
                out.add("contents", targetMessages);
                JsonObject config = Json.obj();
                Json.copy(chat, config, "temperature"); Json.rename(chat, "top_p", config, "topP"); Json.rename(chat, "top_k", config, "topK");
                if (hasMax) Json.put(config, "maxOutputTokens", max);
                if (chat.has("stop")) config.add("stopSequences", asArray(chat.get("stop")));
                if (chat.has("n")) Json.rename(chat, "n", config, "candidateCount");
                JsonObject format = Json.o(chat, "response_format");
                if (Json.s(format, "type").equals("json_schema")) {
                    Json.put(config, "responseMimeType", "application/json");
                    config.add("responseJsonSchema", Json.o(format, "json_schema").get("schema"));
                } else if (Json.s(format, "type").equals("json_object")) Json.put(config, "responseMimeType", "application/json");
                if (w.ctx.options.geminiSupportsImages.test(w.model(chat))) config.add("responseModalities",
                        Json.arr(new com.google.gson.JsonPrimitive("TEXT"), new com.google.gson.JsonPrimitive("IMAGE")));
                if (config.size() > 0) out.add("generationConfig", config);
                if (chat.has("tool_choice")) out.add("toolConfig", Json.obj("functionCallingConfig", choiceToGemini(chat.get("tool_choice"))));
                if (w.ctx.options.geminiSafetySetting != null) {
                    JsonArray safety = new JsonArray();
                    for (String category : List.of("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")) {
                        String threshold = w.ctx.options.geminiSafetySetting.apply(category);
                        if (threshold != null && !threshold.isEmpty()) safety.add(Json.obj("category", category, "threshold", threshold));
                    }
                    if (!safety.isEmpty()) out.add("safetySettings", safety);
                }
            }
            default -> throw new IllegalArgumentException("目标格式");
        }
        JsonArray tools = ToolCodec.fromChat(target, Json.a(chat, "tools"), out, w);
        if (!tools.isEmpty()) out.add("tools", tools);
        return out;
    }
    static JsonElement parseArgs(String arguments) {
        if (arguments.isBlank()) return Json.obj();
        try {
            JsonElement args = Json.parse(arguments);
            if (!args.isJsonObject()) throw new ConversionException("工具参数必须是 JSON 对象");
            return args;
        } catch (com.google.gson.JsonParseException e) { throw new ConversionException("无效工具参数 JSON"); }
    }
    static JsonObject responsesCall(JsonObject call, Work w) {
        JsonObject f = Json.o(call, "function");
        ConversionContext.ToolEncoding e = w.ctx.toolEncodings.get(Json.s(f, "name"));
        JsonObject result = Json.obj("type", e == null ? "function_call" : e.type().equals("custom") ? "custom_tool_call" : "function_call",
                "call_id", Json.s(call, "id"), "name", e == null ? Json.s(f, "name") : e.name());
        if (e != null && !e.namespace().isEmpty()) Json.put(result, "namespace", e.namespace());
        if (e != null && e.type().equals("custom")) Json.put(result, "input", Json.s(Json.o(parseArgs(Json.s(f, "arguments"))), "input"));
        else Json.put(result, "arguments", Json.s(f, "arguments"));
        return result;
    }
    private static JsonArray asArray(JsonElement value) { return value.isJsonArray() ? value.getAsJsonArray().deepCopy() : Json.arr(value.deepCopy()); }
    private static JsonObject choiceToClaude(JsonElement choice, JsonObject chat) {
        JsonObject result;
        if (choice.isJsonPrimitive()) result = Json.obj("type", switch (choice.getAsString()) { case "required" -> "any"; case "none" -> "none"; default -> "auto"; });
        else result = Json.obj("type", "tool", "name", Json.s(Json.o(Json.o(choice), "function"), "name"));
        if (chat.has("parallel_tool_calls")) Json.put(result, "disable_parallel_tool_use", !Json.b(chat, "parallel_tool_calls"));
        return result;
    }
    private static JsonObject choiceToGemini(JsonElement choice) {
        if (choice.isJsonPrimitive()) return Json.obj("mode", switch (choice.getAsString()) { case "required" -> "ANY"; case "none" -> "NONE"; default -> "AUTO"; });
        return Json.obj("mode", "ANY", "allowedFunctionNames", Json.arr(new com.google.gson.JsonPrimitive(Json.s(Json.o(Json.o(choice), "function"), "name"))));
    }
}
