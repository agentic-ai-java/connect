# connect

English | [简体中文](README.zh-CN.md)

A Java 17 library for converting AI requests, responses, and streaming events across OpenAI Chat Completions, OpenAI Responses, Anthropic Messages, and Gemini generateContent.

Designed for integration into AI gateways, proxies, and applications. Gson 2.13.2 is the sole runtime dependency; JUnit Jupiter is used for testing.

## Capabilities

- Converts requests, complete responses, and streaming events across all twelve protocol pairs.
- Maps function tools, tool results, reasoning controls, images, and files.
- Encodes Responses custom and namespace tools as functions and restores them using the request context.
- Tracks fragmented tool arguments, token usage, lifecycle events, and finish reasons per stream.
- Reports conversion losses through structured diagnostics, with optional request rejection policies.
- Keeps raw provider usage available for billing.

The library operates on Gson `JsonObject` payloads. The host application handles HTTP transport, authentication, SSE framing, routing, and billing.

## Build and installation

Requires JDK 17+ and Maven 3.9+.

```bash
mvn -B verify
```

The build produces `target/connect-0.1.0.jar`. Gson is resolved separately by Maven; the JAR is not shaded. With dependencies already cached, use `mvn -o -B verify` for offline verification.

[GitHub Actions CI](.github/workflows/ci.yml) runs Maven verification on Java 17 and 21 for pushes and pull requests to `main`. It also supports manual runs and retains test reports for seven days when a job fails.

The project has not been published to Maven Central. Run `mvn -B install` to install it locally, then add the following dependency to the consuming project:

```xml
<dependency>
  <groupId>io.github.agentic-ai-java</groupId>
  <artifactId>connect</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Convert a request

```java
import io.github.agenticai.connect.Connect;
import io.github.agenticai.connect.ConversionContext;
import io.github.agenticai.connect.ConversionResult;
import io.github.agenticai.connect.Format;
import io.github.agenticai.connect.Json;
import io.github.agenticai.connect.Options;

Options options = new Options();
options.claudeDefaultMaxTokens = model -> 4096;
ConversionContext context = new ConversionContext(options);
Connect connect = new Connect();

ConversionResult result = connect.convertRequest(
    Format.OPENAI_CHAT,
    Format.CLAUDE,
    Json.object("""
        {"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"Hello"}]}
        """),
    context
);
System.out.println(result.json());
```

The result includes the converted payload, source and target formats, conversion steps, quality, diagnostics, and usage. Requests are copied before conversion.

Claude requires a positive token limit or a `claudeDefaultMaxTokens` callback. Native Gemini requests can obtain their model name from `context.upstreamModel`.

## Convert a response

```java
ConversionResult result = connect.convertResponse(
    Format.CLAUDE, Format.OPENAI_CHAT, upstreamResponse, context);

String downstreamJson = result.json();
long inputTokens = result.usage().inputTokens();
long outputTokens = result.usage().outputTokens();
```

`usage.source()` and `usage.raw()` preserve provider billing data. Normalized input counts include cache reads and writes; output counts include reasoning tokens.

Reuse the request's `ConversionContext` when converting its response or stream so custom and namespace tools can be restored.

## Convert a stream

```java
import io.github.agenticai.connect.ResponseStreamState;
import io.github.agenticai.connect.StreamOptions;

StreamOptions streamOptions = new StreamOptions();
streamOptions.id = "resp_123";
streamOptions.model = "gpt-test";
streamOptions.includeUsage = true;
streamOptions.emitSequenceNumber = true;

ResponseStreamState state = connect.newResponseStream(
    Format.OPENAI_CHAT, Format.OPENAI_RESPONSES, streamOptions, context);

// The host supplies a decoded upstream event and sends each returned event.
for (ConversionResult event : state.accept(upstreamChunk)) {
    emit(event.json());
}

// Call after the upstream stream ends; repeated calls return no events.
for (ConversionResult event : state.finish()) {
    emit(event.json());
}
```

`emit` represents the host's output function. Each stream needs its own state. An input event can produce zero or multiple output events; `finish()` emits pending terminal events and final usage. Gemini tool calls are buffered until complete arguments are available.

For a Responses target, `state.failResponsesStream(code, message)` emits failure events. Read accumulated usage and diagnostics with `state.usage()` and `state.diagnostics()`.

## Configure conversion

| Setting | Behavior |
|---|---|
| `toolLossPolicy = ALLOW` | Convert and return all diagnostics |
| `toolLossPolicy = SAFE` | Reject requests with ERROR-level loss |
| `toolLossPolicy = STRICT` | Reject requests with any diagnostic |
| `webSearchEncoder` | Customize hosted web search request encoding |
| `claudeDefaultMaxTokens` | Supply a missing Claude token limit |
| Gemini callbacks | Configure safety thresholds and image-generation modalities |

Response and stream conversions report losses without applying request rejection policies. Invalid payloads and missing required media resolvers still raise errors.

Base64 data URLs are decoded locally. To convert media URLs, construct `new Connect(mediaResolver)` and let the host resolve the media. Model suffixes are parsed explicitly with `Reasoning`; they are not stripped automatically.

## Command-line interface

After building, use the same conversion flow from standard input:

```bash
GSON_JAR="$HOME/.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
java -cp "target/classes:$GSON_JAR" io.github.agenticai.connect.ConnectCli --help
java -cp "target/classes:$GSON_JAR" io.github.agenticai.connect.ConnectCli request openai claude < request.json
java -cp "target/classes:$GSON_JAR" io.github.agenticai.connect.ConnectCli response claude openai < response.json
java -cp "target/classes:$GSON_JAR" io.github.agenticai.connect.ConnectCli stream openai openai_responses gpt-test < events.jsonl
```

Formats: `openai`, `openai_responses`, `claude`, `gemini`. Stream input and output use one JSON event per line. Results go to stdout, diagnostics to stderr, and conversion failures exit with code 2.

## Compatibility and validation

The local suite covers protocol matrices, 24 baseline snapshots, tool fragments, reasoning, media, usage, failures, exception serialization, and CLI execution in separate JVMs.

Provider-specific hosted tool artifacts, encrypted thinking, citations, and multimodal streaming may not have an equivalent in the target protocol. Unsupported behavior produces diagnostics. Coverage percentage and live-provider end-to-end behavior have not been measured.

Additional conversion boundaries:

- Same-format payloads are copied without rewriting provider-specific fields.
- Claude and Gemini conversions may pass through Chat; the result exposes the actual conversion steps and quality.
- Gemini schema references must be expanded by the host. Unsupported keywords and multi-type unions produce diagnostics.
- Complete Gemini responses retain multiple candidates. Claude and Responses retain the first candidate; streaming targets other than Chat also retain the first candidate, with loss diagnostics.
- Truncated tool arguments remain available for Chat and Responses. Gemini requires a valid complete argument object.
- Media URLs require a host resolver. Provider conversation IDs, hosted tool execution artifacts, and encrypted thinking cannot always be transferred across protocols.

See the [Chinese guide](README.zh-CN.md) for the corresponding usage and compatibility information.

## License

MIT. See [LICENSE](LICENSE) for license terms and [NOTICE](NOTICE) for third-party dependency notices.
