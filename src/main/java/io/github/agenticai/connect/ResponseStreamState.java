package io.github.agenticai.connect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Per-stream state: accept decoded events and call finish to emit terminal events once. */
public final class ResponseStreamState {
    private final Format from, to;
    private final ConversionContext context;
    private final Work work;
    private final boolean includeUsage, sequenceNumbers;
    private String id, model;
    private final long created = Instant.now().getEpochSecond();
    private long sequence;
    private boolean started, finalized, upstreamTerminal;
    private Usage usage = Usage.empty();
    private final Map<Integer, Choice> choices = new LinkedHashMap<>();
    private final Map<Integer, Integer> sourceBlocks = new LinkedHashMap<>();
    private final Map<String, Integer> sourceItems = new LinkedHashMap<>();
    private final List<OutputItem> outputItems = new ArrayList<>();
    private final Map<String, OutputItem> targetBlocks = new LinkedHashMap<>();
    private int nextToolIndex;

    private static final class Choice {
        final StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        final Map<Integer, Tool> tools = new LinkedHashMap<>();
        String finish = "stop";
        boolean stopped;
    }
    private static final class Tool {
        String id = "", name = "";
        final StringBuilder args = new StringBuilder();
    }
    private static final class OutputItem {
        final int index;
        final String id, kind;
        final StringBuilder text = new StringBuilder();
        final Tool tool;
        OutputItem(int index, String id, String kind, Tool tool) { this.index = index; this.id = id; this.kind = kind; this.tool = tool; }
    }
    private record Delta(String kind, int choice, int index, String text, String id, String name) {
        static Delta text(String kind, int choice, String text) { return new Delta(kind, choice, 0, text, "", ""); }
    }
    ResponseStreamState(Format from, Format to, StreamOptions options, ConversionContext context, MediaResolver media) {
        this.from = Objects.requireNonNull(from); this.to = Objects.requireNonNull(to);
        this.context = context; work = new Work(from, to, context, media);
        includeUsage = options.includeUsage; sequenceNumbers = options.emitSequenceNumber;
        id = options.id; model = options.model.isEmpty() ? context.upstreamModel : options.model;
        if (id.isEmpty()) id = Connect.newId(to == Format.OPENAI_RESPONSES ? "resp_" : to == Format.CLAUDE ? "msg_" : "chatcmpl-");
    }
    public synchronized Usage usage() { return usage; }
    public synchronized List<Diagnostic> diagnostics() { return List.copyOf(work.diagnostics); }
    public synchronized boolean finalized() { return finalized; }
    public Format from() { return from; }
    public Format to() { return to; }
    public synchronized List<ConversionResult> accept(String json) { return accept(Json.object(json)); }
    public synchronized List<ConversionResult> accept(JsonObject event) {
        if (finalized) throw new IllegalStateException("流已结束");
        Objects.requireNonNull(event);
        readUsage(event);
        if (from == to) {
            String type = Json.s(event, "type");
            if (type.equals("response.completed") || type.equals("response.failed") || type.equals("response.incomplete") || type.equals("message_stop")) upstreamTerminal = true;
            return List.of(result(event.deepCopy()));
        }
        List<Delta> deltas = decode(event);
        List<ConversionResult> out = new ArrayList<>();
        for (Delta delta : deltas) {
            if (delta.kind().equals("error")) { out.addAll(fail(Json.obj("type", "upstream_error", "message", delta.text()))); return List.copyOf(out); }
            Choice choice = choices.computeIfAbsent(delta.choice(), key -> new Choice());
            if (delta.kind().equals("finish")) { choice.finish = delta.text(); choice.stopped = true; continue; }
            if (upstreamTerminal || choice.stopped) { work.warn("late_stream_content_ignored", "stream", "上游终止后的内容已忽略"); continue; }
            if (delta.kind().equals("role")) { start(out); continue; }
            if (to != Format.OPENAI_CHAT && delta.choice() > 0) {
                work.loss("multiple_choices_omitted", "choices", "流式目标仅支持首个候选"); continue;
            }
            Tool tool = null;
            if (delta.kind().equals("text")) choice.text.append(delta.text());
            else if (delta.kind().equals("reasoning")) choice.reasoning.append(delta.text());
            else if (delta.kind().equals("tool")) {
                tool = choice.tools.computeIfAbsent(delta.index(), key -> new Tool());
                if (!delta.id().isEmpty()) tool.id = delta.id();
                if (!delta.name().isEmpty()) tool.name += delta.name();
                if (tool.id.isEmpty()) tool.id = Connect.newId("call_");
                tool.args.append(delta.text());
            } else continue;
            start(out);
            emit(delta, tool, out);
        }
        return List.copyOf(out);
    }
    private void readUsage(JsonObject event) {
        JsonObject usageEvent = event;
        if (from == Format.CLAUDE && Json.s(event, "type").equals("message_start")) usageEvent = Json.o(event, "message");
        if (from == Format.OPENAI_RESPONSES && event.has("response")) usageEvent = Json.o(event, "response");
        usage = usage.mergeNonZero(Usage.from(from, usageEvent));
        if (model.isEmpty()) {
            model = Json.s(usageEvent, from == Format.GEMINI ? "modelVersion" : "model");
        }
    }
    private List<Delta> decode(JsonObject event) {
        List<Delta> out = new ArrayList<>();
        if (event.has("error") && !event.get("error").isJsonNull() && !event.has("response")) {
            out.add(Delta.text("error", 0, Json.s(Json.o(event, "error"), "message"))); return out;
        }
        switch (from) {
            case OPENAI_CHAT -> {
                for (JsonElement element : Json.a(event, "choices")) {
                    JsonObject c = Json.o(element), d = Json.o(c, "delta");
                    int index = Math.toIntExact(Json.n(c, "index"));
                    if (d.has("role")) out.add(Delta.text("role", index, ""));
                    if (Json.has(d, "content")) out.add(Delta.text("text", index, Json.s(d, "content")));
                    if (Json.has(d, "reasoning_content")) out.add(Delta.text("reasoning", index, Json.s(d, "reasoning_content")));
                    else if (Json.has(d, "reasoning")) out.add(Delta.text("reasoning", index, Json.s(d, "reasoning")));
                    for (JsonElement tool : Json.a(d, "tool_calls")) {
                        JsonObject t = Json.o(tool), f = Json.o(t, "function");
                        out.add(new Delta("tool", index, Math.toIntExact(Json.n(t, "index")), Json.s(f, "arguments"), Json.s(t, "id"), Json.s(f, "name")));
                    }
                    if (Json.has(c, "finish_reason")) out.add(Delta.text("finish", index, Json.s(c, "finish_reason")));
                }
            }
            case CLAUDE -> {
                int block = Math.toIntExact(Json.n(event, "index"));
                switch (Json.s(event, "type")) {
                    case "message_start" -> out.add(Delta.text("role", 0, ""));
                    case "content_block_start" -> {
                        JsonObject p = Json.o(event, "content_block");
                        switch (Json.s(p, "type")) {
                            case "tool_use" -> {
                                int index = nextToolIndex++; sourceBlocks.put(block, index);
                                JsonElement args = p.get("input");
                                out.add(new Delta("tool", 0, index, args != null && args.isJsonObject() && args.getAsJsonObject().size() > 0 ? Json.stringify(args) : "",
                                        Json.s(p, "id"), Json.s(p, "name")));
                            }
                            case "text" -> { if (!Json.s(p, "text").isEmpty()) out.add(Delta.text("text", 0, Json.s(p, "text"))); }
                            case "thinking" -> { if (!Json.s(p, "thinking").isEmpty()) out.add(Delta.text("reasoning", 0, Json.s(p, "thinking"))); }
                            default -> work.loss("hosted_tool_artifact_omitted", "content_block", "未知 Claude 流式内容块");
                        }
                    }
                    case "content_block_delta" -> {
                        JsonObject d = Json.o(event, "delta");
                        switch (Json.s(d, "type")) {
                            case "text_delta" -> out.add(Delta.text("text", 0, Json.s(d, "text")));
                            case "thinking_delta" -> out.add(Delta.text("reasoning", 0, Json.s(d, "thinking")));
                            case "input_json_delta" -> {
                                Integer index = sourceBlocks.get(block);
                                if (index == null) throw new ConversionException("工具参数事件缺少对应 content_block_start");
                                out.add(new Delta("tool", 0, index, Json.s(d, "partial_json"), "", ""));
                            }
                            case "signature_delta" -> work.warn("thought_signature_omitted", "delta.signature", "思考签名未跨协议传递");
                            case "citations_delta" -> work.warn("citations_omitted", "delta.citation", "引用增量未映射");
                            default -> work.loss("unsupported_stream_delta", "delta", "未知 Claude 流式增量");
                        }
                    }
                    case "message_delta" -> {
                        String reason = Json.s(Json.o(event, "delta"), "stop_reason");
                        if (!reason.isEmpty()) out.add(Delta.text("finish", 0, FinishReasons.fromClaude(reason)));
                    }
                    case "message_stop" -> upstreamTerminal = true;
                    case "ping", "content_block_stop" -> { }
                    case "error" -> out.add(Delta.text("error", 0, Json.s(Json.o(event, "error"), "message")));
                    default -> work.warn("unsupported_stream_event", "type", "未知 Claude 事件：" + Json.s(event, "type"));
                }
            }
            case GEMINI -> {
                for (JsonElement element : Json.a(event, "candidates")) {
                    JsonObject candidate = Json.o(element);
                    int index = Math.toIntExact(Json.n(candidate, "index"));
                    for (JsonElement part : Json.a(Json.o(candidate, "content"), "parts")) {
                        JsonObject p = Json.o(part);
                        if (p.has("text")) out.add(Delta.text(Json.b(p, "thought") ? "reasoning" : "text", index, Json.s(p, "text")));
                        else if (p.has("functionCall")) {
                            JsonObject f = Json.o(p, "functionCall");
                            out.add(new Delta("tool", index, nextToolIndex++, Json.stringify(f.has("args") ? f.get("args") : Json.obj()),
                                    Json.s(f, "id"), Json.s(f, "name")));
                        } else work.loss("stream_media_omitted", "candidates.content.parts", "流式跨协议未映射此非文本内容");
                    }
                    if (Json.has(candidate, "finishReason")) {
                        Choice choice = choices.get(index);
                        boolean hasCalls = out.stream().anyMatch(d -> d.choice() == index && d.kind().equals("tool")) || choice != null && !choice.tools.isEmpty();
                        String reason = FinishReasons.fromGemini(Json.s(candidate, "finishReason"));
                        if (reason.equals("stop") && hasCalls) reason = "tool_calls";
                        out.add(Delta.text("finish", index, reason));
                    }
                    if (candidate.has("groundingMetadata")) work.warn("grounding_metadata_omitted", "groundingMetadata", "流式溯源未映射");
                }
            }
            case OPENAI_RESPONSES -> decodeResponses(event, out);
        }
        return out;
    }
    private void decodeResponses(JsonObject event, List<Delta> out) {
        String type = Json.s(event, "type");
        switch (type) {
            case "response.created", "response.in_progress" -> out.add(Delta.text("role", 0, ""));
            case "response.output_text.delta" -> out.add(Delta.text("text", 0, Json.s(event, "delta")));
            case "response.reasoning_summary_text.delta" -> {
                // Work deduplicates this warning across summary fragments without growing stream state.
                work.warn("reasoning_summary_not_replayable", "delta",
                        "Reasoning summaries are mapped for display only; retain original history rather than replaying them as complete reasoning_content");
                out.add(Delta.text("reasoning", 0, Json.s(event, "delta")));
            }
            case "response.reasoning_text.delta" -> out.add(Delta.text("reasoning", 0, Json.s(event, "delta")));
            case "response.refusal.delta" -> { out.add(Delta.text("text", 0, Json.s(event, "delta"))); work.warn("refusal_as_text", "delta", "拒绝内容以文本传递"); }
            case "response.output_item.added" -> {
                JsonObject item = Json.o(event, "item");
                if (List.of("function_call", "custom_tool_call").contains(Json.s(item, "type"))) {
                    int index = nextToolIndex++;
                    sourceItems.put(Json.s(item, "id"), index);
                    sourceItems.put("output:" + Json.n(event, "output_index"), index);
                    String name = ToolCodec.encodeName(Json.s(item, "name"), Json.s(item, "namespace"), context);
                    out.add(new Delta("tool", 0, index, Json.s(item, "arguments"), Json.s(item, "call_id"), name));
                    if (Json.s(item, "type").equals("custom_tool_call")) {
                        sourceItems.put("custom:" + index, index);
                        if (!Json.s(item, "input").isEmpty()) out.add(new Delta("tool", 0, index, Json.stringify(Json.obj("input", Json.s(item, "input"))), "", ""));
                    }
                } else if (!List.of("message", "reasoning").contains(Json.s(item, "type")))
                    work.loss("hosted_tool_artifact_omitted", "item", "托管工具事件未映射：" + Json.s(item, "type"));
            }
            case "response.function_call_arguments.delta", "response.custom_tool_call_input.delta" -> {
                int index = sourceToolIndex(event);
                if (type.equals("response.custom_tool_call_input.delta")) {
                    // Buffer custom-tool input before wrapping the complete string as JSON arguments.
                    String key = "custom-text:" + index;
                    customInputs.computeIfAbsent(key, k -> new StringBuilder()).append(Json.s(event, "delta"));
                } else out.add(new Delta("tool", 0, index, Json.s(event, "delta"), "", ""));
            }
            case "response.custom_tool_call_input.done" -> {
                int index = sourceToolIndex(event);
                StringBuilder accumulated = customInputs.remove("custom-text:" + index);
                String input = accumulated == null ? Json.s(event, "input") : accumulated.toString();
                out.add(new Delta("tool", 0, index, Json.stringify(Json.obj("input", input)), "", ""));
            }
            case "response.completed", "response.incomplete" -> {
                String reason = type.equals("response.incomplete")
                        ? Json.s(Json.o(Json.o(event, "response"), "incomplete_details"), "reason").equals("content_filter") ? "content_filter" : "length"
                        : choices.containsKey(0) && !choices.get(0).tools.isEmpty() ? "tool_calls" : "stop";
                out.add(Delta.text("finish", 0, reason));
            }
            case "response.failed", "error" -> out.add(Delta.text("error", 0, Json.s(Json.o(Json.o(event, "response"), "error"), "message")));
            case "response.output_item.done", "response.content_part.added", "response.content_part.done",
                    "response.output_text.done", "response.function_call_arguments.done", "response.reasoning_summary_part.added",
                    "response.reasoning_summary_part.done", "response.reasoning_summary_text.done", "response.reasoning_text.done", "response.refusal.done" -> { }
            case "response.output_text.annotation.added" -> work.warn("annotations_omitted", "annotation", "流式注释未映射");
            default -> work.warn("unsupported_stream_event", "type", "未映射 Responses 事件：" + type);
        }
    }
    private final Map<String, StringBuilder> customInputs = new LinkedHashMap<>();
    private int sourceToolIndex(JsonObject event) {
        Integer index = sourceItems.get(Json.s(event, "item_id"));
        if (index == null) index = sourceItems.get("output:" + Json.n(event, "output_index"));
        if (index == null) throw new ConversionException("工具增量缺少对应 output_item.added");
        return index;
    }
    private void start(List<ConversionResult> out) {
        if (started) return;
        started = true;
        switch (to) {
            case OPENAI_CHAT -> out.add(result(chatChunk(0, Json.obj("role", "assistant"), null)));
            case CLAUDE -> out.add(result(Json.obj("type", "message_start", "message", Json.obj("id", id, "type", "message", "role", "assistant",
                    "model", model, "content", new JsonArray(), "stop_reason", null, "stop_sequence", null, "usage", usage.toJson(to)))));
            case OPENAI_RESPONSES -> {
                JsonObject initial = response("in_progress");
                out.add(result(responseEvent("response.created", "response", initial)));
                out.add(result(responseEvent("response.in_progress", "response", initial)));
            }
            case GEMINI -> { }
        }
    }
    private void emit(Delta delta, Tool tool, List<ConversionResult> out) {
        switch (to) {
            case OPENAI_CHAT -> {
                JsonObject d = Json.obj();
                if (delta.kind().equals("text")) Json.put(d, "content", delta.text());
                else if (delta.kind().equals("reasoning")) Json.put(d, "reasoning_content", delta.text());
                else {
                    JsonObject f = Json.obj("arguments", delta.text());
                    if (!delta.name().isEmpty()) Json.put(f, "name", delta.name());
                    JsonObject tc = Json.obj("index", delta.index(), "type", "function", "function", f);
                    if (!delta.id().isEmpty() || tool.args.toString().equals(delta.text())) Json.put(tc, "id", tool.id);
                    d.add("tool_calls", Json.arr(tc));
                }
                out.add(result(chatChunk(delta.choice(), d, null)));
            }
            case CLAUDE -> {
                String key = delta.kind().equals("tool") ? "tool:" + delta.index() : delta.kind();
                OutputItem block = targetBlocks.get(key);
                if (block == null) {
                    block = new OutputItem(outputItems.size(), "", delta.kind(), tool); outputItems.add(block); targetBlocks.put(key, block);
                    JsonObject content = delta.kind().equals("tool") ? Json.obj("type", "tool_use", "id", tool.id, "name", tool.name, "input", Json.obj())
                            : delta.kind().equals("reasoning") ? Json.obj("type", "thinking", "thinking", "", "signature", "") : Json.obj("type", "text", "text", "");
                    out.add(result(Json.obj("type", "content_block_start", "index", block.index, "content_block", content)));
                }
                if (!delta.text().isEmpty()) {
                    block.text.append(delta.text());
                    JsonObject d = delta.kind().equals("tool") ? Json.obj("type", "input_json_delta", "partial_json", delta.text())
                            : delta.kind().equals("reasoning") ? Json.obj("type", "thinking_delta", "thinking", delta.text())
                            : Json.obj("type", "text_delta", "text", delta.text());
                    out.add(result(Json.obj("type", "content_block_delta", "index", block.index, "delta", d)));
                }
            }
            case GEMINI -> {
                if (delta.kind().equals("tool")) return; // Gemini requires complete arguments, emitted at finish.
                JsonObject part = Json.obj("text", delta.text());
                if (delta.kind().equals("reasoning")) Json.put(part, "thought", true);
                out.add(result(geminiChunk(Json.arr(part), null)));
            }
            case OPENAI_RESPONSES -> emitResponses(delta, tool, out);
        }
    }
    private void emitResponses(Delta delta, Tool tool, List<ConversionResult> out) {
        String key = delta.kind().equals("tool") ? "tool:" + delta.index() : delta.kind();
        OutputItem item = targetBlocks.get(key);
        if (item == null) {
            String prefix = delta.kind().equals("tool") ? "fc_" : delta.kind().equals("reasoning") ? "rs_" : "msg_";
            item = new OutputItem(outputItems.size(), Connect.newId(prefix), delta.kind(), tool);
            outputItems.add(item); targetBlocks.put(key, item);
            out.add(result(responseEvent("response.output_item.added", "output_index", item.index, "item", itemJson(item, false))));
            if (delta.kind().equals("text")) out.add(result(responseEvent("response.content_part.added", "item_id", item.id, "output_index", item.index,
                    "content_index", 0, "part", Json.obj("type", "output_text", "text", "", "annotations", new JsonArray()))));
            if (delta.kind().equals("reasoning")) out.add(result(responseEvent("response.reasoning_summary_part.added", "item_id", item.id,
                    "output_index", item.index, "summary_index", 0, "part", Json.obj("type", "summary_text", "text", ""))));
        }
        if (delta.text().isEmpty()) return;
        item.text.append(delta.text());
        String type = delta.kind().equals("tool") ? "response.function_call_arguments.delta"
                : delta.kind().equals("reasoning") ? "response.reasoning_summary_text.delta" : "response.output_text.delta";
        ConversionContext.ToolEncoding encoding = tool == null ? null : context.toolEncodings.get(tool.name);
        if (encoding != null && encoding.type().equals("custom")) return; // Restore input after JSON is complete.
        JsonObject event = responseEvent(type, "item_id", item.id, "output_index", item.index, "delta", delta.text());
        if (delta.kind().equals("text")) Json.put(event, "content_index", 0);
        if (delta.kind().equals("reasoning")) Json.put(event, "summary_index", 0);
        out.add(result(event));
    }
    private JsonObject itemJson(OutputItem item, boolean done) {
        return switch (item.kind) {
            case "text" -> Json.obj("id", item.id, "type", "message", "role", "assistant", "status", done ? "completed" : "in_progress",
                    "content", done ? Json.arr(Json.obj("type", "output_text", "text", item.text.toString(), "annotations", new JsonArray())) : new JsonArray());
            case "reasoning" -> Json.obj("id", item.id, "type", "reasoning", "summary",
                    done ? Json.arr(Json.obj("type", "summary_text", "text", item.text.toString())) : new JsonArray());
            case "tool" -> {
                JsonObject call = RequestCodec.responsesCall(RequestCodec.call(item.tool.id, item.tool.name, done ? item.tool.args.toString() : ""), work);
                Json.put(call, "id", item.id); Json.put(call, "status", done ? "completed" : "in_progress");
                yield call;
            }
            default -> throw new IllegalStateException("未知输出项");
        };
    }
    public synchronized List<ConversionResult> finish() {
        if (finalized) return List.of();
        if (from == to) { finalized = true; return List.of(); }
        List<ConversionResult> out = new ArrayList<>();
        // Flush custom input even when the upstream stream omitted its done event.
        for (Map.Entry<String, StringBuilder> entry : customInputs.entrySet()) {
            int index = Integer.parseInt(entry.getKey().substring("custom-text:".length()));
            Choice c = choices.computeIfAbsent(0, k -> new Choice()); Tool t = c.tools.get(index);
            if (t != null) t.args.append(Json.stringify(Json.obj("input", entry.getValue().toString())));
        }
        customInputs.clear();
        start(out);
        Choice first = choices.computeIfAbsent(0, k -> new Choice());
        if (!first.tools.isEmpty() && first.finish.equals("stop")) first.finish = "tool_calls";
        if (to == Format.GEMINI) for (Choice c : choices.values()) for (Tool tool : c.tools.values())
            RequestCodec.parseArgs(tool.args.toString());
        switch (to) {
            case OPENAI_CHAT -> {
                for (Map.Entry<Integer, Choice> entry : choices.entrySet()) out.add(result(chatChunk(entry.getKey(), Json.obj(), entry.getValue().finish)));
                if (includeUsage) {
                    JsonObject event = chatChunk(0, Json.obj(), null); event.add("choices", new JsonArray()); event.add("usage", usage.toJson(to)); out.add(result(event));
                }
            }
            case CLAUDE -> {
                for (OutputItem block : outputItems) out.add(result(Json.obj("type", "content_block_stop", "index", block.index)));
                out.add(result(Json.obj("type", "message_delta", "delta", Json.obj("stop_reason", FinishReasons.toClaude(first.finish), "stop_sequence", null),
                        "usage", usage.toJson(to))));
                out.add(result(Json.obj("type", "message_stop")));
            }
            case GEMINI -> {
                JsonArray parts = new JsonArray();
                for (Tool tool : first.tools.values()) parts.add(Json.obj("functionCall", Json.obj("id", tool.id, "name", tool.name, "args", RequestCodec.parseArgs(tool.args.toString()))));
                JsonObject event = geminiChunk(parts, FinishReasons.toGemini(first.finish));
                if (includeUsage) event.add("usageMetadata", usage.toJson(to)); out.add(result(event));
            }
            case OPENAI_RESPONSES -> {
                for (OutputItem item : outputItems) completeItem(item, out);
                String status = first.finish.equals("length") || first.finish.equals("content_filter") ? "incomplete" : "completed";
                JsonObject finalResponse = response(status);
                if (status.equals("incomplete")) finalResponse.add("incomplete_details", Json.obj("reason", first.finish.equals("length") ? "max_output_tokens" : "content_filter"));
                out.add(result(responseEvent("response." + status, "response", finalResponse)));
            }
        }
        finalized = true;
        return List.copyOf(out);
    }
    private void completeItem(OutputItem item, List<ConversionResult> out) {
        switch (item.kind) {
            case "text" -> {
                out.add(result(responseEvent("response.output_text.done", "item_id", item.id, "output_index", item.index, "content_index", 0, "text", item.text.toString())));
                out.add(result(responseEvent("response.content_part.done", "item_id", item.id, "output_index", item.index, "content_index", 0,
                        "part", Json.obj("type", "output_text", "text", item.text.toString(), "annotations", new JsonArray()))));
            }
            case "reasoning" -> {
                out.add(result(responseEvent("response.reasoning_summary_text.done", "item_id", item.id, "output_index", item.index, "summary_index", 0, "text", item.text.toString())));
                out.add(result(responseEvent("response.reasoning_summary_part.done", "item_id", item.id, "output_index", item.index, "summary_index", 0,
                        "part", Json.obj("type", "summary_text", "text", item.text.toString()))));
            }
            case "tool" -> {
                JsonObject call = itemJson(item, true);
                boolean custom = Json.s(call, "type").equals("custom_tool_call");
                if (custom) out.add(result(responseEvent("response.custom_tool_call_input.delta", "item_id", item.id, "output_index", item.index, "delta", Json.s(call, "input"))));
                out.add(result(responseEvent(custom ? "response.custom_tool_call_input.done" : "response.function_call_arguments.done",
                        "item_id", item.id, "output_index", item.index, custom ? "input" : "arguments", custom ? Json.s(call, "input") : item.tool.args.toString())));
            }
            default -> throw new IllegalStateException("未知输出项");
        }
        out.add(result(responseEvent("response.output_item.done", "output_index", item.index, "item", itemJson(item, true))));
    }
    public synchronized List<ConversionResult> failResponsesStream(String code, String message) {
        if (to != Format.OPENAI_RESPONSES) throw new IllegalStateException("该流的目标不是 Responses");
        return fail(Json.obj("code", code, "message", message));
    }
    private List<ConversionResult> fail(JsonObject error) {
        if (finalized) return List.of();
        List<ConversionResult> out = new ArrayList<>(); start(out);
        work.loss("upstream_stream_failed", "error", Json.s(error, "message"));
        if (to == Format.OPENAI_RESPONSES) {
            out.add(result(responseEvent("error", "code", Json.s(error, "code"), "message", Json.s(error, "message"))));
            JsonObject response = response("failed"); response.add("error", error.deepCopy());
            out.add(result(responseEvent("response.failed", "response", response)));
        } else out.add(result(to == Format.CLAUDE ? Json.obj("type", "error", "error", error) : Json.obj("error", error)));
        finalized = true; return List.copyOf(out);
    }
    private JsonObject response(String status) {
        JsonArray output = new JsonArray();
        if (!status.equals("in_progress")) for (OutputItem item : outputItems) output.add(itemJson(item, true));
        JsonObject response = Json.obj("id", id, "object", "response", "created_at", created, "model", model, "status", status,
                "output", output, "error", null, "incomplete_details", null);
        if (includeUsage && !status.equals("in_progress")) response.add("usage", usage.toJson(to));
        return response;
    }
    private JsonObject responseEvent(String type, Object... pairs) {
        JsonObject event = Json.obj(pairs); Json.put(event, "type", type);
        if (sequenceNumbers) Json.put(event, "sequence_number", sequence++);
        return event;
    }
    private JsonObject chatChunk(int index, JsonObject delta, String reason) {
        return Json.obj("id", id, "object", "chat.completion.chunk", "created", created, "model", model,
                "choices", Json.arr(Json.obj("index", index, "delta", delta, "finish_reason", reason)));
    }
    private JsonObject geminiChunk(JsonArray parts, String reason) {
        JsonObject candidate = Json.obj("index", 0, "content", Json.obj("role", "model", "parts", parts));
        if (reason != null) Json.put(candidate, "finishReason", reason);
        return Json.obj("responseId", id, "modelVersion", model, "candidates", Json.arr(candidate));
    }
    private ConversionResult result(JsonObject event) {
        return Connect.result(event, from, to, Connect.streamSteps(from, to), work.diagnostics, usage, true);
    }
}
