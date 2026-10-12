package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reusable conversion entry point; mutable state belongs to each context and stream. */
public final class Connect {
    private final MediaResolver mediaResolver;
    public Connect() { this(null); }
    public Connect(MediaResolver mediaResolver) { this.mediaResolver = mediaResolver; }
    public ConversionResult convertRequest(Format from, Format to, String json) { return convertRequest(from, to, Json.object(json), null); }
    public ConversionResult convertRequest(Format from, Format to, JsonObject request, ConversionContext context) {
        return convertRequestVia(from, to, request, context, requestPath(from, to));
    }
    public ConversionResult convertRequestVia(Format from, Format to, JsonObject request, ConversionContext context, List<Format> path) {
        Objects.requireNonNull(request); validatePath(from, to, path);
        ConversionContext ctx = context == null ? new ConversionContext() : context;
        JsonObject value = request.deepCopy(); List<Diagnostic> diagnostics = new ArrayList<>();
        List<ConversionResult.Step> steps = new ArrayList<>();
        Work finalWork = new Work(from, to, ctx, mediaResolver);
        finalWork.requestModel = finalWork.model(request);
        ProviderProfile profile = ctx.options.providerProfile;
        // Resolve intent once. Intermediate protocol defaults must not overwrite the source intent.
        if (path.size() > 1 || profile != null) {
            Reasoning.Intent intent = Reasoning.read(from, request);
            if (ctx.reasoning != null && intent != null && !ctx.reasoning.equals(intent))
                finalWork.warn("reasoning_overridden", "reasoning", "Context reasoning overrides the request controls");
            if (ctx.reasoning == null) ctx.reasoning = intent;
        }
        if (!ctx.upstreamModel.isEmpty() && to != Format.GEMINI) Json.put(value, "model", ctx.upstreamModel);
        for (int i = 1; i < path.size(); i++) {
            Format source = path.get(i - 1), target = path.get(i);
            Work work = new Work(source, target, ctx, mediaResolver);
            value = RequestCodec.convert(source, target, value, work);
            diagnostics.addAll(work.diagnostics); steps.add(step(source, target));
        }
        if ((path.size() > 1 || profile != null) && (profile == null || !profile.rendersReasoning()))
            Reasoning.render(ctx.reasoning, to, value, finalWork);
        if (to == Format.OPENAI_CHAT && (path.size() > 1 || profile != null)) RequestHistory.normalizeChat(value, finalWork);
        if (profile != null) profile.adapt(value, new ProviderProfile.Adaptation(finalWork));
        diagnostics.addAll(finalWork.diagnostics);
        finalWork.diagnostics.clear(); finalWork.diagnostics.addAll(diagnostics); finalWork.rejectLoss();
        return result(value, from, to, steps, diagnostics, Usage.empty(), false);
    }
    public ConversionResult convertResponse(Format from, Format to, String json) { return convertResponse(from, to, Json.object(json), null); }
    public ConversionResult convertResponse(Format from, Format to, JsonObject response, ConversionContext context) {
        Objects.requireNonNull(response); Objects.requireNonNull(from); Objects.requireNonNull(to);
        ConversionContext ctx = context == null ? new ConversionContext() : context;
        Usage usage = Usage.from(from, response);
        if (from == to) return result(response.deepCopy(), from, to, List.of(), List.of(), usage, false);
        JsonObject value = response.deepCopy(); List<Diagnostic> diagnostics = new ArrayList<>();
        List<ConversionResult.Step> steps = new ArrayList<>(); List<Format> path = responsePath(from, to);
        for (int i = 1; i < path.size(); i++) {
            Format source = path.get(i - 1), target = path.get(i);
            Work work = new Work(source, target, ctx, mediaResolver);
            value = ResponseCodec.fromChat(target, ResponseCodec.toChat(source, value, work), usage, work);
            diagnostics.addAll(work.diagnostics); steps.add(step(source, target));
        }
        return result(value, from, to, steps, diagnostics, usage, false);
    }
    public ConversionResult convertRequestById(String id, JsonObject request, ConversionContext context) {
        List<Format> pair = pair(id); return convertRequest(pair.get(0), pair.get(1), request, context);
    }
    public ConversionResult convertResponseById(String id, JsonObject response, ConversionContext context) {
        List<Format> pair = pair(id); return convertResponse(pair.get(0), pair.get(1), response, context);
    }
    public ResponseStreamState newResponseStream(Format from, Format to, StreamOptions options, ConversionContext context) {
        return new ResponseStreamState(from, to, options == null ? new StreamOptions() : options,
                context == null ? new ConversionContext() : context, mediaResolver);
    }
    public ResponseStreamState newResponseStreamById(String id, StreamOptions options, ConversionContext context) {
        List<Format> pair = pair(id); return newResponseStream(pair.get(0), pair.get(1), options, context);
    }
    static String newId(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    public static String converterId(Format from, Format to) { return from.wireName() + "_to_" + to.wireName(); }
    private static List<Format> pair(String id) {
        for (Format from : Format.values()) for (Format to : Format.values())
            if (from != to && converterId(from, to).equals(id)) return List.of(from, to);
        throw new ConversionException("未知转换器：" + id);
    }
    static List<Format> requestPath(Format from, Format to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        if (from == to) return List.of(from);
        if (from == Format.GEMINI && to != Format.OPENAI_CHAT || from == Format.CLAUDE && to == Format.GEMINI)
            return List.of(from, Format.OPENAI_CHAT, to);
        return List.of(from, to);
    }
    static List<Format> responsePath(Format from, Format to) {
        if (from == to) return List.of(from);
        if (from == Format.GEMINI && to != Format.OPENAI_CHAT || from == Format.CLAUDE && to != Format.OPENAI_CHAT
                || from == Format.OPENAI_RESPONSES && to == Format.GEMINI) return List.of(from, Format.OPENAI_CHAT, to);
        return List.of(from, to);
    }
    static List<ConversionResult.Step> streamSteps(Format from, Format to) {
        List<ConversionResult.Step> steps = new ArrayList<>(); List<Format> path = responsePath(from, to);
        for (int i = 1; i < path.size(); i++) steps.add(step(path.get(i - 1), path.get(i)));
        return steps;
    }
    private static ConversionResult.Step step(Format from, Format to) { return new ConversionResult.Step(converterId(from, to), from, to); }
    static ConversionResult result(JsonObject value, Format from, Format to, List<ConversionResult.Step> steps,
            List<Diagnostic> diagnostics, Usage usage, boolean stream) {
        ConversionResult.Quality quality = from == to || (from == Format.OPENAI_CHAT && to == Format.OPENAI_RESPONSES
                || from == Format.OPENAI_RESPONSES && to == Format.OPENAI_CHAT) ? ConversionResult.Quality.GOOD
                : (from == Format.CLAUDE && to == Format.GEMINI || from == Format.GEMINI && to == Format.CLAUDE)
                ? ConversionResult.Quality.DISCOURAGED : ConversionResult.Quality.FAIR;
        return new ConversionResult(value, from, to, from == to ? "identity" : converterId(from, to), quality, steps, diagnostics, usage, stream);
    }
    private static void validatePath(Format from, Format to, List<Format> path) {
        if (path == null || path.isEmpty() || path.get(0) != from || path.get(path.size() - 1) != to)
            throw new ConversionException("指定转换路径的起止格式不匹配");
        for (int i = 1; i < path.size(); i++) if (path.get(i) == null || path.get(i) == path.get(i - 1)) throw new ConversionException("转换路径无效");
    }
}
