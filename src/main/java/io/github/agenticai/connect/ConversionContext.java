package io.github.agenticai.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Per-request context; reuse tool encodings when converting the corresponding response or stream. */
public final class ConversionContext {
    public String originModel = "";
    /** Explicit routed model override; leave empty to retain the request model. */
    public String upstreamModel = "";
    public Reasoning.Intent reasoning;
    public final Options options;
    final Map<String, ToolEncoding> toolEncodings = new LinkedHashMap<>();
    final Map<String, String> callNames = new LinkedHashMap<>();
    public ConversionContext() { this(new Options()); }
    public ConversionContext(Options options) { this.options = Objects.requireNonNull(options); }
    public record ToolEncoding(String type, String name, String namespace) {}
}
