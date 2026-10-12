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
- Applies optional provider profiles once to the final request, including same-format conversions.

The library operates on Gson `JsonObject` payloads. The host application handles HTTP transport, authentication, SSE framing, routing, and billing.

## Build and installation

Requires JDK 17+ and Maven 3.9+.

```bash
mvn -B verify
```

The build produces `target/connect-0.1.0.jar`. Gson is resolved separately by Maven; the JAR is not shaded. With dependencies already cached, use `mvn -o -B verify` for offline verification.

`verify` enforces at least 90% line coverage through JaCoCo, without excluding production classes. The HTML report is `target/site/jacoco/index.html`; the XML report is `target/site/jacoco/jacoco.xml`. CLI child JVMs contribute their real execution coverage.

[GitHub Actions CI](.github/workflows/ci.yml) runs Maven verification on Java 17 and 21 for pushes and pull requests to `main`. It also supports manual runs and retains test reports for seven days when a job fails.

For released versions, add the following dependency to the consuming project. Maven Central is Maven's default repository; no GitHub Packages credentials or custom repository declaration are required. For local development, use `mvn -B install` instead.

```xml
<dependency>
  <groupId>io.github.agentic-ai-java</groupId>
  <artifactId>connect</artifactId>
  <version>0.1.0</version>
</dependency>
```

### CI releases

[Release CI](.github/workflows/release.yml) follows the ARGI publication flow: check an existing tag and matching GitHub draft, run tests and the coverage gate, attach sources/Javadoc, sign the POM and JARs, audit the resolved runtime dependencies against OSV, verify the gateway examples, publish to Maven Central, and only then publish the GitHub release. Normal CI also checks unsigned release attachments on Java 17 and 21. No tests are skipped during publication.

Maintainers configure `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, and `GPG_PRIVATE_KEY` as repository secrets; an encrypted key also needs `GPG_PASSPHRASE`. Never put these values in source control. Create an immutable `v<version>` tag and a draft release with the exact commit SHA as its target, then run `Release` with that tag. `publish=false` validates the signed build without uploading to Central; `publish=true` waits for Central publication before making the GitHub release visible.

Published Central versions cannot be overwritten or rolled back. If CI stops after uploading, inspect the recorded Central deployment ID before retrying: a successful Central deployment only needs GitHub release finalization, not another upload. Consumers roll back by pinning an earlier version; publish corrections under a new version. Before the first release is available, a rollback requires the previous local build or existing host implementation.

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

## Adapt a provider dialect

Protocol format and provider identity are separate. An OpenAI-compatible endpoint may still require its own reasoning, history, and parameter rules. Select a profile explicitly; connect does not infer one from a model name or URL.

```java
import io.github.agenticai.connect.ProviderProfile;

Options options = new Options();
options.providerProfile = ProviderProfile.deepSeekChat();
options.toolLossPolicy = Options.LossPolicy.SAFE;
ConversionContext context = new ConversionContext(options);
context.upstreamModel = "deepseek-flash";

ConversionResult result = new Connect().convertRequest(
    Format.OPENAI_RESPONSES,
    Format.OPENAI_CHAT,
    Json.object("""
        {"model":"client-alias","input":"Hello","reasoning":{"effort":"medium"}}
        """),
    context
);
// Inspect warnings before sending result.value() through the host's transport.
System.out.println(result.json());
```

The DeepSeek Chat profile requires an `OPENAI_CHAT` target. It is based on the public DeepSeek documentation reviewed on October 12, 2026, not a universal adapter for all DeepSeek API surfaces or older model dialects:

- Renders `thinking.type` and `reasoning_effort`; maps `minimal` to `low`, `medium`/`xhigh` to `high`, and `ultra` to `max`, with warnings for changed effort. Explicitly disabled reasoning uses `none`. Without controls, it leaves the provider's default intact.
- Maps `max_completion_tokens` to `max_tokens`, with the former taking precedence; validates a positive integer token limit and the supported `top_p` range. It diagnoses ignored sampling controls and adjusts thinking-mode `top_p` values below `0.95`.
- Keeps complete assistant `reasoning_content` separate from visible text. Thinking requests with nonempty tools require reasoning history for every assistant turn, not only turns with tool calls. Missing history is an ERROR; no reasoning is synthesized.
- Diagnoses forced tool choices in thinking mode, `json_schema` output, legacy function fields, and non-text assistant content as ERRORs rather than silently discarding them. User/tool multimodal payloads are not flattened by the profile.

The default policy remains `ALLOW`: ERROR diagnostics alone do not reject a request. Use `SAFE` to reject these combinations, or `STRICT` to reject warnings as well. Invalid required fields and numeric controls throw regardless of policy. Native DeepSeek Anthropic/Responses endpoints are outside this profile's scope.

Custom providers implement `ProviderProfile.adapt(request, adaptation)`. The hook receives a copied final payload, source/target formats, request context, portable reasoning intent, and diagnostic methods. Override `rendersReasoning()` only when the profile owns final reasoning rendering. Profiles do not modify responses or streams. A shared custom profile must be thread-safe; each independent request needs a separate `ConversionContext`, reused only for its corresponding response or stream.

### Routed model migration

`context.upstreamModel` now explicitly overrides a request's `model`, including same-format conversions. Previously it was a fallback for missing models. Hosts that want to retain the incoming model must leave it empty; hosts that already map client aliases should set the actual upstream model. Gemini's endpoint model still belongs to the host rather than a generated body field. No migration script is required; review context initialization before replacing the library.

## Convert a response

```java
ConversionResult result = connect.convertResponse(
    Format.CLAUDE, Format.OPENAI_CHAT, upstreamResponse, context);

String downstreamJson = result.json();
long inputTokens = result.usage().inputTokens();
long outputTokens = result.usage().outputTokens();
```

`usage.source()` and `usage.raw()` preserve provider billing data. Normalized input counts include cache reads and writes; output counts include reasoning tokens.

For Chat providers, an absent `prompt_tokens` falls back to `prompt_cache_hit_tokens + prompt_cache_miss_tokens`, and absent standard `cached_tokens` falls back to `prompt_cache_hit_tokens`. Standard fields take precedence even when explicitly zero; cache hits are not added twice. Raw provider fields remain available.

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
| `providerProfile` | Adapt the final request to a provider dialect; null keeps generic protocol behavior |
| `webSearchEncoder` | Customize hosted web search request encoding |
| `claudeDefaultMaxTokens` | Supply a missing Claude token limit |
| Gemini callbacks | Configure safety thresholds and image-generation modalities |

Response and stream conversions report losses without applying request rejection policies. Invalid payloads and missing required media resolvers still raise errors.

Base64 data URLs are decoded locally. To convert media URLs, construct `new Connect(mediaResolver)` and let the host resolve the media. Model suffixes are parsed explicitly with `Reasoning`; they are not stripped automatically.

Source reasoning intent is read once and rendered only at the final target, so intermediate protocol defaults cannot change an exact budget. Responses history groups adjacent full reasoning, an assistant message, and parallel calls into one assistant turn; tool results and new turns preserve boundaries. A reasoning summary is not complete replay history. Plain Chat reasoning cannot become signed native thinking in Claude/Gemini or encrypted Responses history; such losses produce diagnostics rather than inserting private reasoning into visible text.

For response-display compatibility, Responses summaries and their streaming deltas still map to Chat `reasoning_content`, with a `reasoning_summary_not_replayable` warning. This does not make them complete reasoning history. Hosts must retain original provider history and provenance and must not replay these display fields as DeepSeek thinking history. The profile checks presence and type, not the authenticity or completeness of arbitrary strings; copying a converted assistant message into a new request without its diagnostics loses this distinction.

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

The local suite covers protocol matrices, 24 baseline snapshots, tool fragments, reasoning, media, usage, failures, exception serialization, CLI execution in separate JVMs, provider-profile contracts, concurrent request isolation, and a 512-call history batch.

Provider-specific hosted tool artifacts, encrypted thinking, citations, and multimodal streaming may not have an equivalent in the target protocol. Unsupported behavior produces diagnostics. Line coverage is measured and checked by `verify`; live-provider end-to-end behavior, sustained load, and throughput/latency benchmarks are not established by this suite.

Additional conversion boundaries:

- Same-format payloads are deep-copied without rewriting provider-specific fields unless an explicit model mapping or provider profile is configured.
- Claude and Gemini conversions may pass through Chat; the result exposes the actual conversion steps and quality.
- Gemini schema references must be expanded by the host. Unsupported keywords and multi-type unions produce diagnostics.
- Complete Gemini responses retain multiple candidates. Claude and Responses retain the first candidate; streaming targets other than Chat also retain the first candidate, with loss diagnostics.
- Truncated tool arguments remain available for Chat and Responses. Gemini requires a valid complete argument object.
- Media URLs require a host resolver. Provider conversation IDs, hosted tool execution artifacts, and encrypted thinking cannot always be transferred across protocols.

See the [Chinese guide](README.zh-CN.md) for the corresponding usage and compatibility information.

### Design references

The protocol/provider separation and final model mapping were reviewed against Apache ShenYu's `OpenAiCompatible`, `OpenAi`, and `OpenAiChat` at commit `a318c56b519588a69d9b2076f3d9b8686f28d83f`, and New API's `relaykit` and DeepSeek channel adapter at commit `633b4c5b190dc1af99a2814391354d86948e7943`. This implementation borrows design concepts, not New API's AGPL source. HTTP, authentication, endpoint selection, retries, and billing remain host responsibilities. Provider rules are checked against the [DeepSeek thinking guide](https://api-docs.deepseek.com/guides/thinking_mode/) and [Chat API schema](https://api-docs.deepseek.com/api/create-chat-completion/); future provider changes require a new compatibility review.

## License

MIT. See [LICENSE](LICENSE) for license terms and [NOTICE](NOTICE) for third-party dependency notices.
