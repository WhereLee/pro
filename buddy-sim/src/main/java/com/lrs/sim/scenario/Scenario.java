package com.lrs.sim.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景脚本（swap-simulator.md §7.2）：一份**线性步骤**的 JSON，用来把"注入什么 + 做什么 + 断言什么"
 * 写成数据而不是写成代码。
 *
 * 只做线性步骤，**刻意不做分支与循环**（计划里的风险 R4）：
 * 一旦 DSL 里出现 if/while，它就会长成一门没有测试、没有调试器、也没有人选型过的语言；
 * 真需要分支时，正确做法是拆成两个场景文件或回到测试代码，而不是给这个格式加语法。
 *
 * 顶层字段：
 * <pre>
 * {
 *   "name": "fi05-out-of-order",
 *   "seed": 42,                     // 所有随机都吃它：同一份文件跑两次必须得到同一条时间线
 *   "description": "…",
 *   "steps": [ {"do":"fault", …}, {"do":"send", …}, {"do":"assert", …} ]
 * }
 * </pre>
 */
public record Scenario(String name, long seed, String description, List<JsonNode> steps) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 允许的步骤名（不在表内的直接失败，不"忽略未知步骤"——静默忽略等于断言没跑）。 */
    public static final List<String> KNOWN_STEPS = List.of(
            "fault", "send", "sendExpired", "sendBadSign", "sendReplayNonce", "resend", "action", "link", "wait",
            "assert", "log");

    public static Scenario read(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /**
     * 按位置加载：先当文件路径（支持仓库根与模块目录两种工作目录），再退回 jar 内的 classpath。
     *
     * 场景文件的**唯一存放处是仓库根的 `protocol/scenarios/`**（共享数据资产），不往 src/main/resources
     * 复一份：复一份就会一份改一份不改，而场景是断言来源，漂移后红的是错的地方。
     */
    public static Scenario readAny(String location) throws IOException {
        for (String candidate : new String[]{location,
                Path.of("protocol", "scenarios", location).toString(),
                Path.of("..", "protocol", "scenarios", location).toString()}) {
            Path file = Path.of(candidate);
            if (Files.exists(file)) {
                return read(file);
            }
        }
        String resource = location.startsWith("classpath:")
                ? location.substring("classpath:".length()) : "scenarios/" + location;
        try (InputStream in = Scenario.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("场景文件不存在：" + location + "（工作目录与 jar 内都没找到）");
            }
            return parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** 列出仓库根 `protocol/scenarios/` 下的全部场景（矩阵用例靠它保证"每项都有至少一条"）。 */
    public static List<Path> listAvailable() {
        for (String dir : new String[]{"protocol/scenarios", "../protocol/scenarios"}) {
            Path base = Path.of(dir);
            if (Files.isDirectory(base)) {
                try (var stream = Files.list(base)) {
                    return stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
                } catch (IOException e) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    public static Scenario parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        String name = root.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IOException("场景缺少 name");
        }
        long seed = root.path("seed").asLong(42L);
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new IOException("场景 " + name + " 的 steps 为空");
        }
        List<JsonNode> steps = new ArrayList<>();
        for (JsonNode step : stepsNode) {
            String doWhat = step.path("do").asText(null);
            if (doWhat == null || !KNOWN_STEPS.contains(doWhat)) {
                throw new IOException("场景 " + name + " 含未知步骤 do=" + doWhat
                        + "（支持：" + KNOWN_STEPS + "）");
            }
            steps.add(step);
        }
        return new Scenario(name, seed, root.path("description").asText(""), List.copyOf(steps));
    }
}
