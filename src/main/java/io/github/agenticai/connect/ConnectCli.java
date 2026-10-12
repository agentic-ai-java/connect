package io.github.agenticai.connect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Offline conversion CLI; streaming mode accepts one JSON event per line. */
public final class ConnectCli {
    private ConnectCli() {}
    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("用法：ConnectCli request|response|stream 源协议 目标协议 [模型名]");
            System.out.println("源协议和目标协议：openai、openai_responses、claude、gemini");
            System.out.println("从标准输入读取 JSON；stream 模式每行一个事件。媒体 URL 需由宿主解析。");
            return;
        }
        if (args.length < 3 || args.length > 4) {
            System.err.println("参数无效，使用 --help 查看用法"); System.exit(2); return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            Connect kit = new Connect(); Format from = Format.parse(args[1]), to = Format.parse(args[2]);
            ConversionContext context = new ConversionContext();
            if (args.length == 4) context.upstreamModel = args[3];
            if (args[0].equals("stream")) {
                ResponseStreamState state = kit.newResponseStream(from, to, new StreamOptions(), context);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) for (ConversionResult result : state.accept(line)) System.out.println(result.json());
                }
                for (ConversionResult result : state.finish()) System.out.println(result.json());
                for (Diagnostic diagnostic : state.diagnostics()) System.err.println(diagnostic);
            } else {
                StringBuilder input = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) input.append(line).append('\n');
                ConversionResult result = switch (args[0]) {
                    case "request" -> kit.convertRequest(from, to, Json.object(input.toString()), context);
                    case "response" -> kit.convertResponse(from, to, Json.object(input.toString()), context);
                    default -> throw new IllegalArgumentException("未知操作：" + args[0]);
                };
                System.out.println(result.json());
                for (Diagnostic diagnostic : result.diagnostics()) System.err.println(diagnostic);
            }
        } catch (RuntimeException e) {
            System.err.println("转换失败：" + e.getMessage()); System.exit(2);
        }
    }
}
