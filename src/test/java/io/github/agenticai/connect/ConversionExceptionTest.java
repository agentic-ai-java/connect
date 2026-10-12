package io.github.agenticai.connect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversionExceptionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializationPreservesMessageAndImmutableDiagnostics(boolean includeDiagnostic) throws Exception {
        Diagnostic diagnostic = new Diagnostic("unsupported_tool", "tools[0]", "Tool cannot be converted",
                Diagnostic.Severity.ERROR, Format.OPENAI_RESPONSES, Format.CLAUDE);
        List<Diagnostic> expected = includeDiagnostic ? List.of(diagnostic) : List.of();
        ArrayList<Diagnostic> source = new ArrayList<>(expected);
        ConversionException original = new ConversionException("Conversion failed", source);
        source.clear();
        assertEquals(expected, original.diagnostics());
        assertThrows(UnsupportedOperationException.class, () -> original.diagnostics().add(diagnostic));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(buffer)) {
            output.writeObject(original);
        }
        ConversionException restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            restored = (ConversionException) input.readObject();
        }
        assertEquals(original.getMessage(), restored.getMessage());
        assertEquals(expected, restored.diagnostics());
        assertThrows(UnsupportedOperationException.class, () -> restored.diagnostics().add(diagnostic));
    }
}
