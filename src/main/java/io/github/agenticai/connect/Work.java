package io.github.agenticai.connect;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static io.github.agenticai.connect.Json.s;

final class Work {
    final Format from, to;
    final ConversionContext ctx;
    final MediaResolver media;
    final List<Diagnostic> diagnostics = new ArrayList<>();
    String requestModel = "";
    Work(Format from, Format to, ConversionContext ctx, MediaResolver media) {
        this.from = from; this.to = to; this.ctx = ctx; this.media = media;
    }
    void warn(String code, String path, String message) { add(code, path, message, Diagnostic.Severity.WARNING); }
    void loss(String code, String path, String message) { add(code, path, message, Diagnostic.Severity.ERROR); }
    void add(String code, String path, String message, Diagnostic.Severity severity) {
        Diagnostic diagnostic = new Diagnostic(code, path, message, severity, from, to);
        if (!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
    }
    String model(JsonObject request) { return !ctx.upstreamModel.isEmpty() ? ctx.upstreamModel : !s(request, "model").isEmpty() ? s(request, "model") : requestModel; }
    MediaResolver.MediaData resolve(String source) {
        if (source.startsWith("data:")) return MediaResolver.decodeDataUrl(source);
        if (media == null) throw new ConversionException("需要 MediaResolver 解析媒体：" + source);
        return Objects.requireNonNull(media.resolve(source), "MediaResolver 返回 null");
    }
    void rejectLoss() {
        List<Diagnostic> rejected = diagnostics.stream().filter(d -> ctx.options.toolLossPolicy == Options.LossPolicy.STRICT
                || ctx.options.toolLossPolicy == Options.LossPolicy.SAFE && d.severity() == Diagnostic.Severity.ERROR).toList();
        if (!rejected.isEmpty()) throw new ConversionException("转换会丢失协议语义", rejected);
    }
}
