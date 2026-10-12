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
- 在最终请求上执行可选的供应商适配，包括同协议请求。

库通过 Gson `JsonObject` 处理协议数据。HTTP 传输、鉴权、SSE 封装、路由和计费由宿主应用负责。

## 构建与安装

要求 JDK 17+、Maven 3.9+。

```bash
mvn -B verify
# Offline verification with cached dependencies:
mvn -o -B verify
```

构建产物为 `target/connect-0.1.0.jar`。Gson 由 Maven 单独解析，不包含在该 JAR 中。依赖已缓存时，可使用 `mvn -o -B verify` 离线验证。

`verify` 通过 JaCoCo 检查行覆盖率至少为 90%，不排除生产类。HTML 报告位于 `target/site/jacoco/index.html`，XML 报告位于 `target/site/jacoco/jacoco.xml`。CLI 子 JVM 的真实执行也计入覆盖率。

[GitHub Actions CI](.github/workflows/ci.yml) 在推送到 `main` 或向 `main` 提交 PR 时，使用 Java 17 和 21 运行 Maven 验证。支持手动触发，任务失败时保留测试报告七天。

使用已发布版本时，在宿主项目中添加以下依赖。Maven Central 是 Maven 的默认仓库，不需要 GitHub Packages 凭据或额外的仓库声明。本地开发时，可执行 `mvn -B install` 安装到本地仓库。

```xml
<dependency>
  <groupId>io.github.agentic-ai-java</groupId>
  <artifactId>connect</artifactId>
  <version>0.1.0</version>
</dependency>
```

### CI 发布

[Release CI](.github/workflows/release.yml) 沿用 ARGI 的发布流程：核对现有 tag 与 GitHub 草稿的目标提交，运行测试和覆盖率门槛，生成 sources、Javadoc，签名 POM 与 JAR，通过 OSV 检查实际解析的运行时依赖，验证网关示例，发布到 Maven Central，最后公开 GitHub Release。普通 CI 也会在 Java 17 和 21 上检查未签名的发布附件。发布过程不跳过测试。

维护者需要配置仓库 Secrets：`CENTRAL_USERNAME`、`CENTRAL_PASSWORD` 和 `GPG_PRIVATE_KEY`；加密密钥还需要 `GPG_PASSPHRASE`。不得将凭据写入源码。创建不可变的 `v<version>` tag，并以准确的提交 SHA 创建 Release 草稿，再手动运行 `Release`。`publish=false` 只验证签名构建，不上传 Central；`publish=true` 等待 Central 发布完成后再公开 GitHub Release。

Central 已发布版本不可覆盖或撤回。若上传后 CI 中断，应先检查日志中的 Central deployment ID：Central 已发布成功时，只需完成 GitHub Release，不应再次上传。使用方通过固定旧版本回滚，修正版本使用新的版本号。首版尚未可用时，回滚需要保留的旧本地构建或宿主原有实现。

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

## 供应商方言适配

协议格式与供应商身份分离。兼容 OpenAI 的端点仍可能具有不同的推理控制、历史回放和参数限制。供应商适配需要显式选择；connect 不会根据模型名或 URL 自动推断。

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

DeepSeek Chat profile 仅支持 `OPENAI_CHAT` 目标，其规则依据 2026 年 10 月 12 日核对的 DeepSeek 官方文档，不代表兼容所有 DeepSeek API 端点或旧模型方言：

- 渲染 `thinking.type` 和 `reasoning_effort`：`minimal` 映射为 `low`，`medium`、`xhigh` 映射为 `high`，`ultra` 映射为 `max`，改变等级时返回警告。显式关闭推理时使用 `none`；未指定控制时保留供应商默认值。
- 将 `max_completion_tokens` 映射为 `max_tokens`，前者优先；检查正整数 token 上限与 `top_p` 范围。被忽略的采样参数返回诊断；思考模式下，小于 `0.95` 的 `top_p` 调整为 `0.95`。
- 将完整的 assistant `reasoning_content` 与可见文本分开。思考模式且工具列表非空时，所有 assistant 历史轮次都需要完整推理，不仅是发生工具调用的轮次。缺失历史返回 ERROR，不补造推理。
- 思考模式下的强制工具选择、`json_schema` 输出、旧式函数字段和 assistant 非文本内容返回 ERROR，不静默丢弃。profile 不会把 user、tool 的多模态内容压成字符串。

默认策略仍是 `ALLOW`，仅有 ERROR 诊断不会拒绝请求。建议使用 `SAFE` 拒绝上述不兼容组合；`STRICT` 连警告也会拒绝。缺失必填字段或数值控制无效时，不论策略都会抛出异常。DeepSeek 原生 Anthropic、Responses 端点不在此 profile 的适配范围内。

自定义供应商可实现 `ProviderProfile.adapt(request, adaptation)`。回调接收深拷贝后的最终请求、源与目标格式、请求上下文、可移植推理意图和诊断方法。仅当 profile 自行负责最终推理渲染时，才覆写 `rendersReasoning()`。profile 不修改响应或流；共享的自定义 profile 必须线程安全。每个独立请求使用单独的 `ConversionContext`，仅为对应响应或流复用上下文。

### 上游模型映射迁移

`context.upstreamModel` 现在显式覆盖请求中的 `model`，同协议请求也生效；此前仅在请求缺失模型时兜底。需要保留传入模型的宿主应将该字段留空；已将客户端别名映射为真实上游模型的宿主应设置映射结果。Gemini 端点中的模型仍由宿主提供，而不是写入生成的请求体。无需迁移脚本，替换库前需检查上下文初始化逻辑。

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

对于 Chat 供应商，缺少 `prompt_tokens` 时使用 `prompt_cache_hit_tokens + prompt_cache_miss_tokens` 兜底；缺少标准 `cached_tokens` 时使用 `prompt_cache_hit_tokens` 兜底。标准字段即使明确为零，也优先于供应商扩展字段；不会重复计入缓存命中，原始字段仍保留。

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
| `providerProfile` | 在最终请求上适配供应商方言；null 保留通用协议行为 |
| `webSearchEncoder` | 自定义托管 web search 请求编码 |
| `claudeDefaultMaxTokens` | 提供缺失的 Claude token 上限 |
| Gemini 回调 | 配置安全阈值与图像生成模态 |

响应和流式转换报告损耗，不应用请求拒绝策略。无效数据或缺少必要媒体解析器时，转换仍会失败。

Base64 data URL 在本地解码。媒体 URL 通过 `new Connect(mediaResolver)` 注册的宿主回调解析。

推理预算保存在 `Reasoning.Intent` 中。源意图只读取一次，在最终目标上渲染一次，避免中间协议的默认值改写精确预算。支持 Claude 手动与 adaptive 模式，以及 Gemini 预算和等级归一化；调整 token 上限或预算时会返回诊断。

Responses 历史中的相邻完整推理、assistant 消息与并行工具调用合并为同一 assistant 轮次；工具结果与新轮次保持边界。推理摘要不等于完整回放历史。Chat 明文推理不能自动变成 Claude、Gemini 的原生签名思考或 Responses 的加密历史；无法回放时返回诊断，不将私有推理插入可见文本。

为保持响应展示兼容性，Responses 摘要及其流式增量仍映射到 Chat `reasoning_content`，同时返回 `reasoning_summary_not_replayable` 警告。这不表示摘要成为了完整推理历史。宿主必须保留供应商原始历史及来源信息，不能将这些展示字段回放为 DeepSeek 思考历史。适配器只检查字段是否存在及类型，无法验证任意字符串的真实性或完整性；仅复制转换后的 assistant 消息到新请求、丢弃诊断，会失去这一区分。

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

本地测试包含 24 份协议基准快照，覆盖协议矩阵、工具参数片段、推理、媒体、usage、失败事件、异常序列化、独立 JVM 中的 CLI 执行、供应商适配契约、独立请求并发隔离及 512 个工具调用的历史批次。行覆盖率由 `verify` 测量并执行门禁；真实供应商网络 E2E、持续压力以及吞吐量、延迟基准尚未验证。

兼容性边界：

- 同协议数据返回深拷贝，保留供应商原生字段；显式模型映射或配置供应商 profile 时，会执行对应适配。
- 不能跨协议表达的托管工具产物、加密推理、引用和流式媒体内容返回损耗诊断。
- Gemini JSON Schema 引用由宿主提前展开，不支持的关键字和多类型联合返回诊断。
- 非流式 Gemini 保留多个候选。Claude、Responses 及 Chat 以外的流式目标保留首个候选，并报告损耗。
- 截断的工具参数在 Chat、Responses 中保留；Gemini 要求完整、有效的参数对象。
- 媒体 URL 需要宿主解析器；供应商会话 ID、托管工具执行结果和加密思考不保证能跨协议传递。

### 设计参考

协议与供应商分层、最终模型映射参考了 Apache ShenYu 提交 `a318c56b519588a69d9b2076f3d9b8686f28d83f` 的 `OpenAiCompatible`、`OpenAi`、`OpenAiChat`，以及 New API 提交 `633b4c5b190dc1af99a2814391354d86948e7943` 的 `relaykit` 和 DeepSeek channel adapter。本实现借鉴设计，不复制 New API 的 AGPL 源码。HTTP、鉴权、端点选择、重试和计费仍由宿主负责。供应商规则依据 [DeepSeek 思考模式指南](https://api-docs.deepseek.com/guides/thinking_mode/) 与 [Chat API 定义](https://api-docs.deepseek.com/api/create-chat-completion/) 核对；供应商后续调整需要重新评估兼容性。

## 许可证

采用 MIT 协议。许可条款见 [LICENSE](LICENSE)，第三方依赖声明见 [NOTICE](NOTICE)。
