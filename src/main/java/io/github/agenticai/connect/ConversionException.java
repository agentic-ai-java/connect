package io.github.agenticai.connect;

import java.util.ArrayList;
import java.util.List;

/** Conversion failure with optional loss diagnostics for host error handling. */
public class ConversionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ArrayList<Diagnostic> diagnostics;
    public ConversionException(String message) { this(message, List.of()); }
    public ConversionException(String message, List<Diagnostic> diagnostics) {
        super(message); this.diagnostics = new ArrayList<>(List.copyOf(diagnostics));
    }
    public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }
}
