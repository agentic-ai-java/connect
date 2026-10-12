package io.github.agenticai.connect;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {
    private record Result(int exit, String out, String err) {}
    private static Result run(String input, String... args) throws IOException, InterruptedException {
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        // Instrument the real CLI subprocess as well, without introducing a runtime dependency.
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("org.jacoco"))
                .forEach(command::add);
        command.addAll(List.of("-Xverify:all", "-cp", classpath, ConnectCli.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "CLI 未在 10 秒内结束");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }
    @Test void help() throws IOException, InterruptedException {
        Result result = run("", "--help"); assertEquals(0, result.exit()); assertTrue(result.out().contains("用法"));
    }
    @Test void requestActuallyRuns() throws IOException, InterruptedException {
        Result result = run("{\"model\":\"claude-test\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}",
                "request", "openai", "claude");
        assertEquals(0, result.exit(), result.err());
        assertTrue(result.out().contains("你好")); assertEquals(100, Json.n(Json.object(result.out()), "max_tokens"));
    }
    @Test void responseActuallyRuns() throws IOException, InterruptedException {
        Result result = run(Json.stringify(ConnectTest.resource("response/claude.json")), "response", "claude", "openai");
        assertEquals(0, result.exit(), result.err()); assertTrue(result.out().contains("get_weather"));
    }
    @Test void streamActuallyRuns() throws IOException, InterruptedException {
        StringBuilder input = new StringBuilder();
        for (var event : ConnectTest.resource("stream/openai.json").getAsJsonArray()) input.append(Json.stringify(event)).append('\n');
        Result result = run(input.toString(), "stream", "openai", "openai_responses", "test");
        assertEquals(0, result.exit(), result.err()); assertTrue(result.out().contains("response.completed")); assertTrue(result.out().contains("Hello world"));
    }
    @Test void badInputHasNonZeroExit() throws IOException, InterruptedException {
        Result result = run("{}", "request", "openai", "claude");
        assertEquals(2, result.exit()); assertTrue(result.err().contains("转换失败"));
        assertEquals(2, run("", "request").exit());
        assertEquals(2, run("{}", "missing", "openai", "claude").exit());
    }
}
