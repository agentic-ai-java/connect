package io.github.agenticai.connect;

import java.io.Serializable;

public record Diagnostic(String code, String path, String message, Severity severity, Format from, Format to)
        implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Severity { WARNING, ERROR }
}
