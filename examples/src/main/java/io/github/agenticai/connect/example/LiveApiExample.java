package io.github.agenticai.connect.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.agenticai.connect.Connect;
import io.github.agenticai.connect.ConversionContext;
import io.github.agenticai.connect.ConversionResult;
import io.github.agenticai.connect.Format;
import io.github.agenticai.connect.Json;
import io.github.agenticai.connect.ResponseStreamState;
import io.github.agenticai.connect.StreamOptions;
import io.github.agenticai.connect.Usage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 从环境读取凭据及地址，通过真实接口验证协议转换；不记录请求头或密钥。 */
public final class LiveApiExample {
    private static final Connect CONNECT = new Connect();
    private static final String MARKER = "CONNECT_OK";
    private final HttpClient http;
    private int passed;
    private int failed;
    private int skipped;

    private record Endpoint(Format format, String model, String key, String baseUrl, boolean bearer) {
        URI uri(boolean stream) {
            return URI.create(baseUrl + switch (format) {
                case OPENAI_CHAT -> "/chat/completions";
                case OPENAI_RESPONSES -> "/responses";
                case CLAUDE -> "/v1/messages";
                case GEMINI -> "/models/" + model
                        + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
            });
        }
        // 避免 record 默认实现将凭据带入诊断输出。
        @Override public String toString() { return format.wireName() + " / " + model; }
    }

    private LiveApiExample() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        String proxy = env("CONNECT_PROXY", "");
        if (!proxy.isBlank()) {
            URI uri = URI.create(proxy);
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("CONNECT_PROXY 应为 http://主机:端口");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort())));
        }
        http = builder.build();
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("运行：java -jar examples/target/connect-live-example.jar");
            System.out.println("环境变量：OPENAI_API_KEY、GEMINI_API_KEY、ANTHROPIC_AUTH_TOKEN 或 ANTHROPIC_API_KEY；至少配置一个。");
            System.out.println("模型：OPENAI_MODEL（gpt-6-astra）、GEMINI_MODEL（gemini-3.8-flash）。");
            System.out.println("Anthropic 接口需指定 ANTHROPIC_MODEL；OPENAI_BASE_URL 包含 /v1，ANTHROPIC_BASE_URL 不含 /v1。");
            System.out.println("可选：OPENAI_WIRE_API=responses|chat|both（默认 both）；各 BASE_URL 默认官方地址。");
            System.out.println("可选：CONNECT_PROXY=http://127.0.0.1:18080；默认直连。");
            System.out.println("每个启用接口最多 5 个基础请求；仅 429、5xx 和超时各请求最多重试一次。");
            System.out.println("真实调用会消耗账户额度，失败返回非零退出码。输出不含密钥。");
            return;
        }
        if (args.length != 0) { System.err.println("参数无效，请使用 --help"); System.exit(2); }
        try {
            LiveApiExample example = new LiveApiExample();
            example.run();
            if (example.failed > 0) System.exit(1);
        } catch (Exception error) {
            System.err.println("启动失败：" + safe(error.getMessage()));
            System.exit(2);
        }
    }

    private void run() throws Exception {
        String openai = env("OPENAI_API_KEY", ""), gemini = env("GEMINI_API_KEY", "");
        String anthropicToken = env("ANTHROPIC_AUTH_TOKEN", "");
        String anthropic = anthropicToken.isBlank() ? env("ANTHROPIC_API_KEY", "") : anthropicToken;
        require(!openai.isBlank() || !gemini.isBlank() || !anthropic.isBlank(), "请先配置接口密钥环境变量");
        String wire = env("OPENAI_WIRE_API", "both");
        require(List.of("responses", "chat", "both").contains(wire), "OPENAI_WIRE_API 无效");
        List<Endpoint> endpoints = new ArrayList<>();
        if (!gemini.isBlank()) endpoints.add(new Endpoint(Format.GEMINI, model("GEMINI_MODEL", "gemini-3.8-flash"),
                gemini, baseUrl("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta"), false));
        if (!openai.isBlank()) {
            String base = baseUrl("OPENAI_BASE_URL", "https://api.openai.com/v1");
            String model = model("OPENAI_MODEL", "gpt-6-astra");
            if (!wire.equals("chat")) endpoints.add(new Endpoint(Format.OPENAI_RESPONSES, model, openai, base, true));
            if (!wire.equals("responses")) endpoints.add(new Endpoint(Format.OPENAI_CHAT, model, openai, base, true));
        }
        if (!anthropic.isBlank()) endpoints.add(new Endpoint(Format.CLAUDE, model("ANTHROPIC_MODEL", ""), anthropic,
                baseUrl("ANTHROPIC_BASE_URL", "https://api.anthropic.com"), !anthropicToken.isBlank()));
        for (Endpoint endpoint : endpoints) {
            if (endpoint.key().isBlank()) { skipped += 5; System.out.println("SKIP " + endpoint + "：未配置密钥（5 项）"); continue; }
            System.out.println("开始 " + endpoint);
            // 首个真实请求同时检查授权及模型可用性，失败后停止该接口的后续付费请求。
            if (!check(endpoint + " 文本", () -> complete(endpoint, false))) {
                skipped += 4; System.out.println("SKIP 该接口后续 4 项：首个用例未通过"); continue;
            }
            check(endpoint + " 工具调用", () -> complete(endpoint, true));
            check(endpoint + " 文本流", () -> stream(endpoint, false));
            check(endpoint + " 工具流", () -> stream(endpoint, true));
            check(endpoint + " 补充请求格式转换", () -> alternateRequest(endpoint));
        }
        System.out.printf("汇总：通过 %d，失败 %d，跳过 %d%n", passed, failed, skipped);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private boolean check(String name, Checked action) throws InterruptedException {
        long start = System.nanoTime();
        try {
            action.run(); passed++;
            System.out.printf("PASS %s（%.2f 秒）%n", name, (System.nanoTime() - start) / 1e9); return true;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error;
        } catch (Exception error) {
            failed++; System.out.println("FAIL " + name + "：" + safe(error.getMessage())); return false;
        }
    }

    private static JsonObject chat(String model, boolean tool) {
        JsonObject request = Json.object("""
                {"messages":[{"role":"user","content":"Reply with exactly CONNECT_OK and nothing else."}],
                 "max_completion_tokens":512}
                """);
        request.addProperty("model", model);
        if (tool) {
            request.getAsJsonArray("messages").get(0).getAsJsonObject()
                    .addProperty("content", "Call add with a=2 and b=3. Do not answer with text.");
            request.add("tools", Json.parse("""
                    [{"type":"function","function":{"name":"add","description":"计算两个整数之和",
                    "parameters":{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},
                    "required":["a","b"],"additionalProperties":false}}}]
                    """));
            request.addProperty("tool_choice", "required");
        }
        return request;
    }

    private static ConversionContext context(Endpoint endpoint) {
        ConversionContext context = new ConversionContext(); context.upstreamModel = endpoint.model(); return context;
    }

    private JsonObject request(Endpoint endpoint, ConversionContext context, boolean tool) {
        JsonObject base = chat(endpoint.model(), tool);
        // Chat 目标使用 Gemini 输入，另外两个目标使用 Chat 输入，确保经过跨协议转换。
        Format source = endpoint.format() == Format.OPENAI_CHAT ? Format.GEMINI : Format.OPENAI_CHAT;
        if (source == Format.GEMINI) base = CONNECT.convertRequest(Format.OPENAI_CHAT, source, base, context).value();
        ConversionResult result = CONNECT.convertRequest(source, endpoint.format(), base, context);
        diagnostics(result);
        return result.value();
    }

    private void complete(Endpoint endpoint, boolean tool) throws Exception {
        ConversionContext context = context(endpoint);
        JsonObject response = Json.object(send(endpoint, request(endpoint, context, tool), false));
        validateResponses(endpoint, context, response, tool);
    }

    private void alternateRequest(Endpoint endpoint) throws Exception {
        ConversionContext context = context(endpoint);
        JsonObject source = Json.object("""
                {"max_tokens":512,"messages":[{"role":"user","content":"Reply with exactly CONNECT_OK and nothing else."}]}
                """);
        source.addProperty("model", endpoint.model());
        Format from = Format.CLAUDE;
        if (endpoint.format() == Format.CLAUDE) {
            from = Format.OPENAI_RESPONSES;
            source = Json.object("{\"input\":\"Reply with exactly CONNECT_OK and nothing else.\",\"max_output_tokens\":512}");
            source.addProperty("model", endpoint.model());
        }
        ConversionResult result = CONNECT.convertRequest(from, endpoint.format(), source, context);
        diagnostics(result);
        JsonObject response = Json.object(send(endpoint, result.value(), false));
        validateResponses(endpoint, context, response, false);
    }

    private static void validateResponses(Endpoint endpoint, ConversionContext context, JsonObject response, boolean tool) {
        Usage nativeUsage = Usage.from(endpoint.format(), response);
        require(nativeUsage.inputTokens() > 0 && nativeUsage.outputTokens() > 0, "原始响应缺少有效用量");
        for (Format target : Format.values()) {
            if (target == endpoint.format()) continue;
            ConversionResult converted = CONNECT.convertResponse(endpoint.format(), target, response, context);
            diagnostics(converted);
            View view = new View(); view.response(target, converted.value()); view.verify(tool);
            require(converted.usage().equals(nativeUsage), "转换后用量与原始响应不一致");
        }
        System.out.printf("  输入 %d，输出 %d，推理 %d tokens；3 种响应目标通过%n",
                nativeUsage.inputTokens(), nativeUsage.outputTokens(), nativeUsage.reasoningTokens());
    }

    private void stream(Endpoint endpoint, boolean tool) throws Exception {
        ConversionContext context = context(endpoint);
        List<JsonObject> events = sse(send(endpoint, request(endpoint, context, tool), true));
        require(!events.isEmpty(), "SSE 没有 JSON 事件");
        Usage nativeUsage = Usage.empty();
        for (JsonObject event : events) {
            JsonObject value = endpoint.format() == Format.OPENAI_RESPONSES && event.has("response")
                    ? event.getAsJsonObject("response") : event;
            if (endpoint.format() == Format.CLAUDE && event.has("message")) value = event.getAsJsonObject("message");
            nativeUsage = nativeUsage.mergeNonZero(Usage.from(endpoint.format(), value));
        }
        for (Format target : Format.values()) {
            if (target == endpoint.format()) continue;
            StreamOptions options = new StreamOptions(); options.model = endpoint.model(); options.emitSequenceNumber = true;
            ResponseStreamState state = CONNECT.newResponseStream(endpoint.format(), target, options, context);
            View view = new View();
            for (JsonObject event : events) for (ConversionResult item : state.accept(event)) view.event(target, item.value());
            for (ConversionResult item : state.finish()) view.event(target, item.value());
            view.verify(tool);
            require(view.finished, "目标流缺少结束事件：" + target.wireName());
            require(state.finish().isEmpty(), "重复 finish 产生了额外事件");
            require(state.usage().inputTokens() == nativeUsage.inputTokens()
                    && state.usage().outputTokens() == nativeUsage.outputTokens()
                    && state.usage().inputTokens() > 0 && state.usage().outputTokens() > 0, "流式用量不一致或缺失");
            state.diagnostics().forEach(d -> System.out.println("  诊断 " + d.code()));
        }
        System.out.printf("  %d 个真实 SSE 事件，3 种流式目标通过%n", events.size());
    }

    private String send(Endpoint endpoint, JsonObject request, boolean stream) throws Exception {
        JsonObject body = request.deepCopy();
        if (endpoint.format() == Format.GEMINI) {
            // Gemini 模型及流模式由 URL 指定，这两个字段属于宿主传输层。
            body.remove("model"); body.remove("stream");
        } else {
            body.addProperty("stream", stream);
            if (endpoint.format() == Format.OPENAI_RESPONSES) body.addProperty("store", false);
            if (stream && endpoint.format() == Format.OPENAI_CHAT)
                body.add("stream_options", Json.object("{\"include_usage\":true}"));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.uri(stream)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header(endpoint.format() == Format.GEMINI ? "x-goog-api-key" : endpoint.bearer() ? "Authorization" : "x-api-key",
                        endpoint.bearer() ? "Bearer " + endpoint.key() : endpoint.key())
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
        if (endpoint.format() == Format.CLAUDE) builder.header("anthropic-version", "2023-06-01");
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> response;
            try {
                // 完整接收 SSE 后逐事件转换，测试协议语义，不将此耗时视为首 token 延迟。
                response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (java.net.http.HttpTimeoutException error) {
                if (attempt == 0) { System.out.println("  超时，2 秒后重试一次"); Thread.sleep(2000); continue; }
                throw new IOException("HTTP 请求超时", error);
            }
            int status = response.statusCode();
            if (status == 200) return response.body();
            if (attempt == 0 && (status == 429 || status >= 500)) {
                int delay = status == 429 ? 20 : 2;
                System.out.printf("  HTTP %d，%d 秒后重试一次%n", status, delay);
                Thread.sleep(delay * 1000L); continue;
            }
            String message = "供应商拒绝请求";
            try {
                JsonObject error = Json.object(response.body()).getAsJsonObject("error");
                if (error != null) message = string(error, "message");
            } catch (RuntimeException ignored) { /* 非 JSON 错误正文不输出。 */ }
            throw new IOException("HTTP " + status + "：" + safe(message));
        }
        throw new IOException("请求未完成");
    }

    static List<JsonObject> sse(String body) {
        List<JsonObject> events = new ArrayList<>(); StringBuilder data = new StringBuilder();
        for (String line : (body + "\n\n").split("\\R", -1)) {
            if (line.isEmpty()) {
                if (data.length() > 0 && !data.toString().equals("[DONE]")) {
                    JsonObject event = Json.object(data.toString());
                    require(!event.has("error") && !string(event, "type").equals("error")
                            && !string(event, "type").equals("response.failed"), "SSE 返回错误事件");
                    events.add(event);
                }
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).replaceFirst("^ ", ""));
            }
        }
        return events;
    }

    private static final class View {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private String name = "";
        private boolean finished;
        void response(Format format, JsonObject response) {
            switch (format) {
                case OPENAI_CHAT -> chatPart(response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message"));
                case OPENAI_RESPONSES -> {
                    for (JsonElement element : response.getAsJsonArray("output")) {
                        JsonObject item = element.getAsJsonObject();
                        if (string(item, "type").equals("function_call")) { name = string(item, "name"); arguments.append(string(item, "arguments")); }
                        else if (string(item, "type").equals("message")) for (JsonElement block : item.getAsJsonArray("content")) text.append(string(block.getAsJsonObject(), "text"));
                    }
                }
                case CLAUDE -> {
                    for (JsonElement element : response.getAsJsonArray("content")) {
                        JsonObject part = element.getAsJsonObject();
                        if (string(part, "type").equals("text")) text.append(string(part, "text"));
                        if (string(part, "type").equals("tool_use")) { name = string(part, "name"); arguments.append(part.get("input")); }
                    }
                }
                case GEMINI -> gemini(response);
            }
        }
        void chatPart(JsonObject part) {
            text.append(string(part, "content"));
            if (part.has("tool_calls")) for (JsonElement element : part.getAsJsonArray("tool_calls")) {
                JsonObject fn = element.getAsJsonObject().getAsJsonObject("function");
                if (fn == null) continue;
                if (fn.has("name")) name = string(fn, "name");
                arguments.append(string(fn, "arguments"));
            }
        }
        void gemini(JsonObject response) {
            if (!response.has("candidates")) return;
            for (JsonElement element : response.getAsJsonArray("candidates")) {
                JsonObject candidate = element.getAsJsonObject();
                finished |= !string(candidate, "finishReason").isEmpty();
                if (!candidate.has("content")) continue;
                for (JsonElement block : candidate.getAsJsonObject("content").getAsJsonArray("parts")) {
                    JsonObject part = block.getAsJsonObject();
                    if (!part.has("thought") || !part.get("thought").getAsBoolean()) text.append(string(part, "text"));
                    if (part.has("functionCall")) {
                        JsonObject fn = part.getAsJsonObject("functionCall"); name = string(fn, "name"); arguments.append(fn.get("args"));
                    }
                }
            }
        }
        void event(Format format, JsonObject event) {
            switch (format) {
                case OPENAI_CHAT -> {
                    for (JsonElement element : event.getAsJsonArray("choices")) {
                        JsonObject choice = element.getAsJsonObject();
                        if (choice.has("delta")) chatPart(choice.getAsJsonObject("delta"));
                        finished |= !string(choice, "finish_reason").isEmpty();
                    }
                }
                case OPENAI_RESPONSES -> {
                    String type = string(event, "type");
                    if (type.equals("response.output_text.delta")) text.append(string(event, "delta"));
                    if (type.equals("response.function_call_arguments.delta")) arguments.append(string(event, "delta"));
                    if (type.equals("response.output_item.added") && string(event.getAsJsonObject("item"), "type").equals("function_call")) name = string(event.getAsJsonObject("item"), "name");
                    finished |= type.equals("response.completed");
                }
                case CLAUDE -> {
                    String type = string(event, "type");
                    if (type.equals("content_block_start")) {
                        JsonObject block = event.getAsJsonObject("content_block");
                        if (string(block, "type").equals("tool_use")) name = string(block, "name");
                    }
                    if (type.equals("content_block_delta")) {
                        JsonObject delta = event.getAsJsonObject("delta");
                        if (string(delta, "type").equals("text_delta")) text.append(string(delta, "text"));
                        if (string(delta, "type").equals("input_json_delta")) arguments.append(string(delta, "partial_json"));
                    }
                    finished |= type.equals("message_stop");
                }
                case GEMINI -> gemini(event);
            }
        }
        void verify(boolean tool) {
            if (tool) {
                require(name.equals("add"), "工具名不正确");
                JsonObject args = Json.object(arguments.toString());
                require(args.has("a") && args.has("b") && args.get("a").getAsInt() == 2 && args.get("b").getAsInt() == 3, "工具参数不正确");
            } else require(text.toString().trim().equals(MARKER), "文本不符合约定，可能被截断或转换丢失");
        }
    }

    private static void diagnostics(ConversionResult result) {
        result.diagnostics().forEach(d -> System.out.println("  诊断 " + d.code()));
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key); return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value;
    }
    private static String model(String variable, String fallback) {
        String value = env(variable, fallback);
        require(value.matches("[A-Za-z0-9._-]+"), variable + " 不是有效模型标识"); return value;
    }
    private static String baseUrl(String variable, String fallback) {
        String value = env(variable, fallback).replaceAll("/+$", "");
        URI uri = URI.create(value);
        require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null, variable + " 必须是无认证信息的 HTTPS 地址");
        return value;
    }
    static String safe(String text) {
        if (text == null) return "未知错误";
        for (String name : List.of("OPENAI_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY")) {
            String key = System.getenv(name); if (key != null && !key.isEmpty()) text = text.replace(key, "[已隐藏]");
        }
        return text.replaceAll("sk-[A-Za-z0-9_-]+", "[已隐藏]");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
