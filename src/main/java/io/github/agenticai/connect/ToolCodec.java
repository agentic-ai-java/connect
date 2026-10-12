package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

final class ToolCodec {
    private ToolCodec() {}
    static JsonArray toChat(Format source, JsonObject req, Work w) {
        JsonArray out = new JsonArray();
        if (source == Format.OPENAI_CHAT) {
            for (JsonElement t : Json.a(req, "tools")) out.add(t.deepCopy());
            for (JsonElement f : Json.a(req, "functions")) out.add(Json.obj("type", "function", "function", f));
            if (req.has("web_search_options")) out.add(Json.obj("type", "web_search", "config", req.get("web_search_options")));
            return out;
        }
        for (JsonElement entry : Json.a(req, "tools")) {
            JsonObject tool = Json.o(entry);
            if (source == Format.GEMINI) {
                JsonArray declarations = tool.has("functionDeclarations") ? Json.a(tool, "functionDeclarations") : Json.a(tool, "function_declarations");
                for (JsonElement declaration : declarations) {
                    JsonObject f = Json.o(declaration).deepCopy();
                    if (f.has("parametersJsonSchema")) { f.add("parameters", f.remove("parametersJsonSchema")); }
                    out.add(Json.obj("type", "function", "function", f));
                }
                if (tool.has("googleSearch") || tool.has("google_search") || tool.has("googleSearchRetrieval"))
                    out.add(Json.obj("type", "web_search", "config", tool));
                if (declarations.isEmpty() && !tool.has("googleSearch") && !tool.has("google_search") && !tool.has("googleSearchRetrieval"))
                    out.add(Json.obj("type", "hosted", "native", tool));
            } else if (source == Format.CLAUDE) {
                String type = Json.s(tool, "type");
                if (type.startsWith("web_search")) out.add(Json.obj("type", "web_search", "config", tool));
                else if (!type.isEmpty() && !type.equals("custom")) out.add(Json.obj("type", type, "native", tool));
                else {
                    JsonObject f = Json.obj("name", Json.s(tool, "name"));
                    Json.copy(tool, f, "description"); Json.rename(tool, "input_schema", f, "parameters");
                    Json.copy(tool, f, "cache_control"); out.add(Json.obj("type", "function", "function", f));
                }
            } else addResponsesTool(tool, "", out, w);
        }
        return out;
    }
    private static void addResponsesTool(JsonObject tool, String namespace, JsonArray out, Work w) {
        String type = Json.s(tool, "type");
        if (type.equals("namespace")) {
            for (JsonElement child : Json.a(tool, "tools")) addResponsesTool(Json.o(child), Json.s(tool, "name"), out, w);
        } else if (type.equals("function") || type.equals("custom")) {
            String name = Json.s(tool, "name");
            String encoded = namespace.isEmpty() && type.equals("function") ? name : "rk_" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((namespace + ":" + type + ":" + name).getBytes(StandardCharsets.UTF_8));
            if (encoded.length() > 64) {
                encoded = "rk_" + java.util.UUID.nameUUIDFromBytes((namespace + ":" + type + ":" + name).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            }
            w.ctx.toolEncodings.put(encoded, new ConversionContext.ToolEncoding(type, name, namespace));
            JsonObject f = Json.obj("name", encoded);
            Json.copy(tool, f, "description", "strict");
            if (type.equals("custom")) {
                f.add("parameters", Json.obj("type", "object", "properties", Json.obj("input", Json.obj("type", "string")),
                        "required", Json.arr(new com.google.gson.JsonPrimitive("input")), "additionalProperties", false));
                if (tool.has("format")) w.warn("custom_tool_format_approximated", "tools.format", "目标 function 工具以 input 字符串传递 custom 输入");
            } else Json.rename(tool, "parameters", f, "parameters");
            out.add(Json.obj("type", "function", "function", f));
        } else if (type.startsWith("web_search")) out.add(Json.obj("type", "web_search", "config", tool));
        else out.add(Json.obj("type", type, "native", tool));
    }
    static String encodeName(String name, String namespace, ConversionContext ctx) {
        for (Map.Entry<String, ConversionContext.ToolEncoding> entry : ctx.toolEncodings.entrySet()) {
            var encoding = entry.getValue();
            if (encoding.name().equals(name) && encoding.namespace().equals(namespace)) return entry.getKey();
        }
        return name;
    }
    static JsonArray fromChat(Format target, JsonArray tools, JsonObject req, Work w) {
        JsonArray out = new JsonArray(), declarations = new JsonArray();
        for (JsonElement entry : tools) {
            JsonObject tool = Json.o(entry);
            String type = Json.s(tool, "type");
            if (type.equals("function")) {
                JsonObject f = Json.o(tool, "function").deepCopy();
                switch (target) {
                    case OPENAI_CHAT -> out.add(tool.deepCopy());
                    case OPENAI_RESPONSES -> {
                        ConversionContext.ToolEncoding encoding = w.ctx.toolEncodings.get(Json.s(f, "name"));
                        if (encoding != null) {
                            Json.put(f, "name", encoding.name());
                            Json.put(f, "type", encoding.type());
                            if (encoding.type().equals("custom")) { f.remove("parameters"); f.remove("strict"); }
                            if (!encoding.namespace().isEmpty()) {
                                out.add(Json.obj("type", "namespace", "name", encoding.namespace(), "tools", Json.arr(f))); continue;
                            }
                        } else Json.put(f, "type", "function");
                        out.add(f);
                    }
                    case CLAUDE -> {
                        JsonObject c = Json.obj("name", Json.s(f, "name"), "input_schema",
                                f.has("parameters") ? f.get("parameters") : Json.obj("type", "object", "properties", Json.obj()));
                        Json.copy(f, c, "description", "cache_control");
                        if (Json.b(f, "strict")) w.warn("strict_schema_approximated", "tools.strict", "Claude 未保留 OpenAI strict 标记");
                        out.add(c);
                    }
                    case GEMINI -> {
                        JsonObject g = Json.obj("name", Json.s(f, "name")); Json.copy(f, g, "description");
                        if (f.has("parameters")) g.add("parameters", geminiSchema(f.get("parameters"), w, "tools.parameters"));
                        if (Json.b(f, "strict")) w.warn("strict_schema_approximated", "tools.strict", "Gemini 未保留 OpenAI strict 标记");
                        declarations.add(g);
                    }
                }
            } else if (type.equals("web_search") || type.startsWith("web_search")) {
                JsonObject config = Json.o(tool, "config");
                JsonObject custom = w.ctx.options.webSearchEncoder == null ? null : w.ctx.options.webSearchEncoder.apply(target, config.deepCopy());
                if (custom != null) {
                    if (target == Format.OPENAI_CHAT) req.add("web_search_options", custom); else out.add(custom);
                } else switch (target) {
                    case OPENAI_CHAT -> req.add("web_search_options", Json.obj());
                    case OPENAI_RESPONSES -> out.add(Json.obj("type", "web_search_preview"));
                    case CLAUDE -> out.add(Json.obj("type", w.ctx.options.claudeWebSearchVersion, "name", "web_search"));
                    case GEMINI -> out.add(Json.obj("googleSearch", Json.obj()));
                }
                if (config.has("filters") || config.has("allowed_domains") || config.has("user_location"))
                    w.warn("web_search_options_approximated", "tools.web_search", "部分搜索约束需要宿主编码钩子");
            } else w.loss("hosted_tool_omitted", "tools", "目标协议无法执行托管工具：" + type);
        }
        if (!declarations.isEmpty()) out.add(Json.obj("functionDeclarations", declarations));
        return out;
    }
    static JsonElement geminiSchema(JsonElement schema, Work w, String path) {
        if (schema.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement v : schema.getAsJsonArray()) result.add(geminiSchema(v, w, path));
            return result;
        }
        if (!schema.isJsonObject()) return schema.deepCopy();
        JsonObject result = Json.obj();
        for (Map.Entry<String, JsonElement> e : schema.getAsJsonObject().entrySet()) {
            if (List.of("$schema", "$id", "additionalProperties", "strict", "default").contains(e.getKey())) {
                w.warn("gemini_schema_keyword_omitted", path + "." + e.getKey(), "Gemini 不支持该 schema 关键字"); continue;
            }
            if (e.getKey().equals("$ref") || e.getKey().equals("$defs") || e.getKey().equals("definitions")) {
                w.loss("gemini_schema_reference_unsupported", path, "请在跨协议转换前展开 JSON Schema 引用"); continue;
            }
            if (e.getKey().equals("type") && e.getValue().isJsonArray()) {
                JsonArray types = e.getValue().getAsJsonArray();
                String selected = "";
                for (JsonElement t : types) {
                    if (t.getAsString().equals("null")) Json.put(result, "nullable", true);
                    else if (selected.isEmpty()) selected = t.getAsString();
                    else w.loss("gemini_union_type_approximated", path, "多种非空类型仅保留第一种");
                }
                Json.put(result, "type", selected); continue;
            }
            result.add(e.getKey(), geminiSchema(e.getValue(), w, path + "." + e.getKey()));
        }
        return result;
    }
}
