package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/** Uses Chat-shaped content as an internal bridge and diagnoses unsupported blocks. */
final class ContentCodec {
    private ContentCodec() {}
    static JsonArray toChat(Format source, JsonElement content, Work w) {
        JsonArray result = new JsonArray();
        if (content == null || content.isJsonNull()) return result;
        if (content.isJsonPrimitive()) { result.add(Json.obj("type", "text", "text", content.getAsString())); return result; }
        for (JsonElement item : Json.a(content)) {
            JsonObject p = Json.o(item);
            switch (source) {
                case OPENAI_CHAT -> result.add(p.deepCopy());
                case OPENAI_RESPONSES -> {
                    switch (Json.s(p, "type")) {
                        case "input_text", "output_text", "text" -> {
                            JsonObject text = Json.obj("type", "text", "text", Json.s(p, "text"));
                            Json.copy(p, text, "annotations"); result.add(text);
                        }
                        case "input_image" -> {
                            JsonObject image = Json.obj("type", "image_url", "image_url", p.get("image_url"));
                            if (p.has("detail")) image.add("image_url", Json.obj("url", p.get("image_url"), "detail", p.get("detail")));
                            result.add(image);
                        }
                        case "input_file" -> { JsonObject f = p.deepCopy(); f.remove("type"); result.add(Json.obj("type", "file", "file", f)); }
                        case "input_audio" -> result.add(Json.obj("type", "input_audio", "input_audio", p.get("input_audio")));
                        case "refusal" -> result.add(Json.obj("type", "text", "text", Json.s(p, "refusal")));
                        default -> w.loss("unsupported_content_block", "content", "不能表达 Responses 内容块：" + Json.s(p, "type"));
                    }
                }
                case CLAUDE -> {
                    switch (Json.s(p, "type")) {
                        case "text" -> {
                            JsonObject text = Json.obj("type", "text", "text", Json.s(p, "text"));
                            Json.copy(p, text, "cache_control", "citations"); result.add(text);
                        }
                        case "image", "document" -> {
                            JsonObject src = Json.o(p, "source");
                            String url = Json.s(src, "type").equals("url") ? Json.s(src, "url")
                                    : Json.s(src, "type").equals("text") ? "data:text/plain;base64," +
                                    Base64.getEncoder().encodeToString(Json.s(src, "data").getBytes(StandardCharsets.UTF_8))
                                    : "data:" + Json.s(src, "media_type") + ";base64," + Json.s(src, "data");
                            result.add(Json.s(p, "type").equals("image") ? Json.obj("type", "image_url", "image_url", Json.obj("url", url))
                                    : Json.obj("type", "file", "file", Json.obj("file_data", url, "filename", Json.s(p, "title"))));
                        }
                        case "thinking" -> result.add(Json.obj("type", "thinking", "text", Json.s(p, "thinking"), "signature", Json.s(p, "signature")));
                        case "redacted_thinking" -> w.warn("redacted_thinking_omitted", "content", "加密思考块无法跨协议展示");
                        case "tool_use", "tool_result" -> { }
                        default -> w.loss("unsupported_content_block", "content", "不能表达 Claude 内容块：" + Json.s(p, "type"));
                    }
                }
                case GEMINI -> {
                    if (p.has("text")) {
                        JsonObject text = Json.obj("type", Json.b(p, "thought") ? "thinking" : "text", "text", Json.s(p, "text"));
                        Json.copy(p, text, "thoughtSignature"); result.add(text);
                    } else if (p.has("inlineData") || p.has("inline_data")) {
                        JsonObject blob = p.has("inlineData") ? Json.o(p, "inlineData") : Json.o(p, "inline_data");
                        String mime = Json.s(blob, "mimeType");
                        if (mime.isEmpty()) mime = Json.s(blob, "mime_type");
                        String url = "data:" + mime + ";base64," + Json.s(blob, "data");
                        result.add(mime.startsWith("image/") ? Json.obj("type", "image_url", "image_url", Json.obj("url", url))
                                : Json.obj("type", "file", "file", Json.obj("file_data", url)));
                    } else if (p.has("fileData") || p.has("file_data")) {
                        JsonObject f = p.has("fileData") ? Json.o(p, "fileData") : Json.o(p, "file_data");
                        String uri = Json.s(f, "fileUri"); if (uri.isEmpty()) uri = Json.s(f, "file_uri");
                        result.add(Json.obj("type", "file", "file", Json.obj("file_url", uri, "mime_type", Json.s(f, "mimeType"))));
                    } else if (!p.has("functionCall") && !p.has("functionResponse"))
                        w.loss("unsupported_content_block", "parts", "不能表达 Gemini 内容块");
                }
            }
        }
        return result;
    }
    static JsonElement fromChat(Format target, JsonElement content, String role, Work w) {
        if (target == Format.OPENAI_CHAT) return content == null ? com.google.gson.JsonNull.INSTANCE : content.deepCopy();
        if (content != null && content.isJsonPrimitive() && target == Format.CLAUDE) return content.deepCopy();
        JsonArray parts = toChat(Format.OPENAI_CHAT, content, w), result = new JsonArray();
        for (JsonElement item : parts) {
            JsonObject p = Json.o(item);
            switch (Json.s(p, "type")) {
                case "text" -> {
                    JsonObject text = switch (target) {
                        case OPENAI_RESPONSES -> Json.obj("type", role.equals("assistant") ? "output_text" : "input_text", "text", Json.s(p, "text"));
                        case CLAUDE -> Json.obj("type", "text", "text", Json.s(p, "text"));
                        case GEMINI -> Json.obj("text", Json.s(p, "text"));
                        default -> p.deepCopy();
                    };
                    if (target == Format.CLAUDE) Json.copy(p, text, "cache_control", "citations");
                    else if (p.has("cache_control")) w.warn("cache_control_omitted", "content.cache_control", "目标协议无法保留缓存断点");
                    if (target == Format.OPENAI_RESPONSES) Json.copy(p, text, "annotations");
                    else if (p.has("annotations")) w.warn("annotations_omitted", "content.annotations", "目标协议无法保留注释");
                    result.add(text);
                }
                case "thinking" -> {
                    if (target == Format.CLAUDE) result.add(Json.obj("type", "thinking", "thinking", Json.s(p, "text"), "signature", Json.s(p, "signature")));
                    else if (target == Format.GEMINI) {
                        JsonObject thought = Json.obj("text", Json.s(p, "text"), "thought", true);
                        Json.copy(p, thought, "thoughtSignature"); result.add(thought);
                    } else w.warn("thinking_block_omitted", "content", "思考历史块不能作为目标消息内容");
                }
                case "image_url" -> {
                    JsonElement imageUrl = p.get("image_url");
                    String url = imageUrl != null && imageUrl.isJsonPrimitive() ? imageUrl.getAsString() : Json.s(Json.o(imageUrl), "url");
                    if (target == Format.OPENAI_RESPONSES) result.add(Json.obj("type", "input_image", "image_url", url));
                    else {
                        MediaResolver.MediaData data = w.resolve(url);
                        if (target == Format.CLAUDE) result.add(Json.obj("type", "image", "source",
                                Json.obj("type", "base64", "media_type", data.mimeType(), "data", data.base64())));
                        else result.add(Json.obj("inlineData", Json.obj("mimeType", data.mimeType(), "data", data.base64())));
                    }
                }
                case "file" -> {
                    JsonObject f = Json.o(p, "file");
                    if (target == Format.OPENAI_RESPONSES) {
                        JsonObject out = f.deepCopy(); Json.put(out, "type", "input_file"); result.add(out);
                    } else {
                        String source = Json.s(f, "file_data");
                        if (source.isEmpty()) source = Json.s(f, "file_url");
                        if (source.isEmpty()) {
                            w.loss("file_id_unsupported", "content.file.file_id", "目标协议无法解析供应商 file_id"); continue;
                        }
                        if (target == Format.GEMINI && source.startsWith("gs://")) {
                            result.add(Json.obj("fileData", Json.obj("fileUri", source, "mimeType", Json.s(f, "mime_type")))); continue;
                        }
                        MediaResolver.MediaData data = w.resolve(source);
                        if (target == Format.CLAUDE) {
                            if (!Set.of("application/pdf", "text/plain").contains(data.mimeType())) {
                                w.loss("unsupported_media_type", "content.file", "Claude 不支持文件类型：" + data.mimeType()); continue;
                            }
                            JsonObject document = Json.obj("type", "document", "source", Json.obj("type", "base64", "media_type", data.mimeType(), "data", data.base64()));
                            if (Json.has(f, "filename")) Json.put(document, "title", Json.s(f, "filename"));
                            result.add(document);
                        } else result.add(Json.obj("inlineData", Json.obj("mimeType", data.mimeType(), "data", data.base64())));
                    }
                }
                case "input_audio" -> {
                    if (target == Format.OPENAI_RESPONSES) result.add(p.deepCopy());
                    else if (target == Format.GEMINI) {
                        JsonObject audio = Json.o(p, "input_audio");
                        result.add(Json.obj("inlineData", Json.obj("mimeType", "audio/" + Json.s(audio, "format"), "data", Json.s(audio, "data"))));
                    } else w.loss("unsupported_media_type", "content.input_audio", "Claude 消息不支持音频输入");
                }
                default -> w.loss("unsupported_content_block", "content", "不支持内容类型：" + Json.s(p, "type"));
            }
        }
        return result;
    }
    static String text(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        StringBuilder text = new StringBuilder();
        for (JsonElement item : Json.a(content)) {
            JsonObject part = Json.o(item);
            if (Json.s(part, "type").equals("text") || part.has("text")) text.append(Json.s(part, "text"));
        }
        return text.toString();
    }
}
