# connect

[English](README.md) | 简体中文

connect 是一个 Java 17 协议转换库，支持 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages 和 Gemini generateContent 的请求、响应及流式事件转换。

适用于 AI 网关、代理和应用集成。Gson 2.13.2 是唯一的运行时依赖；JUnit Jupiter 用于测试。

## 功能

- 支持四种协议之间十二个方向的请求、完整响应和流式事件转换。
- 转换函数工具、工具结果、推理控制、图像与文件。
- 将 Responses custom 和 namespace 工具编码为函数，并通过请求上下文还原。
- 按流追踪工具参数片段、token 用量、生命周期事件与结束原因。
- 通过结构化诊断报告转换损耗，支持可配置的请求拒绝策略。
- 保留供应商原始用量数据，供宿主计费。

库通过 Gson `JsonObject` 处理协议数据。HTTP 传输、鉴权、SSE 封装、路由和计费由宿主应用负责。

## 构建与安装

要求 JDK 17+、Maven 3.9+。

```bash
mvn -B verify
# Offline verification with cached dependencies:
mvn -o -B verify
```

构建产物为 `target/connect-0.1.0.jar`。Gson 由 Maven 单独解析，不包含在该 JAR 中。依赖已缓存时，可使用 `mvn -o -B verify` 离线验证。

[GitHub Actions CI](.github/workflows/ci.yml) 在推送到 `main` 或向 `main` 提交 PR 时，使用 Java 17 和 21 运行 Maven 验证。支持手动触发，任务失败时保留测试报告七天。

项目尚未发布到 Maven Central。执行 `mvn -B install` 安装到本地仓库后，在宿主项目中添加以下依赖：

```xml
<dependency>
  <groupId>io.github.agentic-ai-java</groupId>
  <artifactId>connect</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 请求转换

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
        {"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"你好"}]}
        """),
    context
);
System.out.println(result.json());
```

转换结果包含目标协议数据、源协议与目标协议、转换路径、质量、诊断和用量信息。请求在转换前复制。

Claude 请求需要正数 token 上限，或通过 `claudeDefaultMaxTokens` 回调提供默认值。Gemini 原生请求可以通过 `context.upstreamModel` 提供模型名称。

可通过 `convertRequestVia` 指定转换路径，或通过 `convertRequestById`、`convertResponseById`、`newResponseStreamById` 选择转换器，例如 `openai_to_claude`。

## 非流式响应

```java
ConversionResult result = connect.convertResponse(
    Format.CLAUDE, Format.OPENAI_CHAT, upstreamResponse, context);
String downstreamJson = result.json();
long inputTokens = result.usage().inputTokens();
long outputTokens = result.usage().outputTokens();
```

`usage.source()` 与 `usage.raw()` 保留供应商原始计量，供宿主计费。归一化后的输入计数包含缓存读写，输出计数包含推理 token。Claude 的 5 分钟和 1 小时缓存写入分别保留；Gemini 的输出包含 `thoughtsTokenCount`。

跨协议响应使用目标协议的标准 usage 字段；扩展计费信息保存在 Java `Usage` 结果中。

转换响应或流时，应复用对应请求的 `ConversionContext`，以便还原 custom 和 namespace 工具。

## 流式转换

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

// Accept a decoded upstream event; one input may produce multiple output events.
for (ConversionResult event : state.accept(upstreamChunk)) {
    emit(event.json());
}
// Finalize after the upstream stream ends; repeated calls emit no additional events.
for (ConversionResult event : state.finish()) {
    emit(event.json());
}
```

`emit` 表示宿主应用的输出函数。每条流使用独立状态；单个输入事件可能产生零个或多个输出事件。`finish()` 输出待发送的终止事件与最终用量，重复调用不会产生额外事件。Gemini 工具调用会缓冲至完整参数可用。

状态保存文本、推理、并行工具调用片段、用量和结束原因。工具参数截断时，Chat/Responses 保留原始片段与 incomplete 状态；无法构造完整 Gemini 参数对象时，转换会失败。

目标为 Responses 时，`state.failResponsesStream(code, message)` 生成 `error` 和 `response.failed`；`sequence_number` 按事件递增。流结束后继续 `accept` 会抛出异常。`state.usage()`、`state.diagnostics()` 可以随时查询累计结果。

## 转换配置

| 配置 | 行为 |
|---|---|
| `toolLossPolicy = ALLOW` | 转换请求并返回所有诊断 |
| `toolLossPolicy = SAFE` | 拒绝存在 ERROR 级损耗的请求 |
| `toolLossPolicy = STRICT` | 拒绝产生任何诊断的请求 |
| `webSearchEncoder` | 自定义托管 web search 请求编码 |
| `claudeDefaultMaxTokens` | 提供缺失的 Claude token 上限 |
| Gemini 回调 | 配置安全阈值与图像生成模态 |

响应和流式转换报告损耗，不应用请求拒绝策略。无效数据或缺少必要媒体解析器时，转换仍会失败。

Base64 data URL 在本地解码。媒体 URL 通过 `new Connect(mediaResolver)` 注册的宿主回调解析。

推理预算保存在 `Reasoning.Intent` 中，多步转换保留精确值。支持 Claude 手动与 adaptive 模式，以及 Gemini 预算和等级归一化；调整 token 上限或预算时会返回诊断。

模型后缀需要由宿主显式解析。调用 `Reasoning.parseModelSuffix(model, enabled)` 或 `parseOpenAIEffortSuffix` 后，将基础模型写入请求，将 `intent` 放入上下文。`@thinking:` 和 `@effort:` 修饰符通过 `parseModifiers` 解析。

## 命令行

先构建，然后在 Bash 中执行（Gson 来自 Maven 缓存）：

```bash
GSON_JAR="$HOME/.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
java -cp "target/classes:$GSON_JAR" io.github.agenticai.connect.ConnectCli --help
java -cp "target/classes:$GSON_JAR" io.github.agenticai.connect.ConnectCli request openai claude < request.json
java -cp "target/classes:$GSON_JAR" io.github.agenticai.connect.ConnectCli response claude openai < response.json
java -cp "target/classes:$GSON_JAR" io.github.agenticai.connect.ConnectCli stream openai openai_responses gpt-test < events.jsonl
```

协议标识为 `openai`、`openai_responses`、`claude`、`gemini`。流式输入和输出均为每行一个 JSON 事件。结果写入 stdout，诊断写入 stderr；转换失败时退出码为 2。

## 兼容性与测试

十二个跨协议方向均覆盖请求、响应和流式。OpenAI Chat 与 Responses 标记 GOOD；Claude 与 Gemini 之间经 Chat 中转，标记 DISCOURAGED；其余标记 FAIR。结果中的 steps 表示实际转换路径。

本地测试包含 24 份协议基准快照，覆盖协议矩阵、工具参数片段、推理、媒体、usage、失败事件、异常序列化及独立 JVM 中的 CLI 执行。覆盖率百分比及真实供应商网络 E2E 尚未测量。

兼容性边界：

- 同协议数据返回深拷贝，保留供应商原生字段。
- 不能跨协议表达的托管工具产物、加密推理、引用和流式媒体内容返回损耗诊断。
- Gemini JSON Schema 引用由宿主提前展开，不支持的关键字和多类型联合返回诊断。
- 非流式 Gemini 保留多个候选。Claude、Responses 及 Chat 以外的流式目标保留首个候选，并报告损耗。
- 截断的工具参数在 Chat、Responses 中保留；Gemini 要求完整、有效的参数对象。
- 媒体 URL 需要宿主解析器；供应商会话 ID、托管工具执行结果和加密思考不保证能跨协议传递。

## 许可证

采用 MIT 协议。许可条款见 [LICENSE](LICENSE)，第三方依赖声明见 [NOTICE](NOTICE)。
