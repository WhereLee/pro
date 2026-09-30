package com.lrs.buddy.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 种子与审计面的两条契约（都是"看起来在工作、其实没工作"的防假绿断言）。
 *
 * 1 **每个产品必须有 PUBLISHED 物模型**。M1 的 ThingModelValidator 在缺模型时按设计静默跳过校验
 *    （后台没配模型不该阻断主链路）。但静默跳过的另一面是：遥测校验可能几个月都在“零校验”状态下绿着。
 *    这条断言把"配了模型"变成可验证事实，而不是靠人去后台点。
 * 2 **返回一次性明文密钥的接口必须禁止记录返回结果**。@OperateLog 默认把响应写进
 *    sys_operate_log.json_result；一旦有人新增一个发密钥的接口却忘了 logResult=false，
 *    密钥就明文落库了。这类错在功能测试里永远不会暴露，只能靠结构断言拦住。
 */
class IotSeedContractTest {

    /** 一行元组都以 (id, '业务主键' 开头，所以逐行取而不是只取第一行。 */
    private static final Pattern ROW_HEAD = Pattern.compile("\\(\\s*\\d+,\\s*'([A-Za-z0-9_-]+)'");
    private static final Pattern STATEMENT_BLOCK = Pattern.compile(
            "INSERT INTO (iot_product|iot_thing_model)[^;]*;", Pattern.DOTALL);

    @Test
    @DisplayName("每个 iot_product 种子产品都必须有已发布物模型")
    void everyProductHasPublishedThingModel() throws IOException {
        String sql = readMigrations();
        List<String> products = new ArrayList<>();
        List<String> modelKeys = new ArrayList<>();
        Matcher blocks = STATEMENT_BLOCK.matcher(sql);
        while (blocks.find()) {
            String table = blocks.group(1);
            String block = blocks.group();
            // 只有 state 为 PUBLISHED 的模型块才算数：DRAFT 不会被校验器读到
            if ("iot_thing_model".equals(table) && !block.contains("'PUBLISHED'")) {
                continue;
            }
            Matcher rows = ROW_HEAD.matcher(block);
            while (rows.find()) {
                ("iot_product".equals(table) ? products : modelKeys).add(rows.group(1));
            }
        }
        assertThat(products).as("接入基座应至少种子两个产品（柜机 + 电池）").hasSizeGreaterThanOrEqualTo(2);
        for (String productKey : products) {
            assertThat(modelKeys).as("产品 " + productKey + " 缺少 PUBLISHED 物模型，遥测校验会静默跳过")
                    .contains(productKey);
        }
    }

    @Test
    @DisplayName("物模型的 spec_json 必须带 properties 与 commands，否则校验等于空转")
    void thingModelSpecIsUsable() throws IOException {
        String sql = readMigrations();
        Matcher blocks = STATEMENT_BLOCK.matcher(sql);
        int checked = 0;
        while (blocks.find()) {
            String block = blocks.group();
            if (!"iot_thing_model".equals(blocks.group(1)) || !block.contains("'PUBLISHED'")) {
                continue;
            }
            assertThat(block).as("spec_json 必须声明 properties（否则越界无从判定）").contains("\"properties\"");
            assertThat(block).as("spec_json 必须声明 commands（否则指令矩阵与设备能力脱节）")
                    .contains("\"commands\"");
            checked++;
        }
        assertThat(checked).isPositive();
    }

    @Test
    @DisplayName("返回一次性明文密钥的接口必须 logResult = false")
    void secretBearingEndpointsDoNotLogResult() throws IOException {
        Path root = Paths.get("..").toAbsolutePath().normalize().resolve("src/main/java");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src/main/java");
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.contains("masterSecret")) {
                    continue;
                }
                // 逐个注解块检查：注解后紧跟的方法若返回含密钥的类型，必须关掉结果记录
                Matcher matcher = Pattern.compile("@OperateLog\\(([^)]*)\\)\\s*(?:public|\\s)([^\\n]*)")
                        .matcher(text);
                while (matcher.find()) {
                    String annotation = matcher.group(1);
                    String signature = matcher.group(2);
                    boolean returnsSecret = signature.contains("Credential") || signature.contains("masterSecret");
                    if (returnsSecret && !annotation.contains("logResult = false")) {
                        offenders.add(file.getFileName() + " -> " + signature.trim());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("明文密钥一旦被 @OperateLog 记录，就会以 JSON 形式长期留在审计表里")
                .isEmpty();
    }

    private static String readMigrations() throws IOException {
        Path dir = migrationDir();
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append("\n");
            }
        }
        return all.toString();
    }

    private static Path migrationDir() {
        Path direct = Paths.get("src/main/resources/db/migration");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return Paths.get("buddy/src/main/resources/db/migration");
    }

    private static List<String> matchAll(Pattern pattern, String text, int group) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(group));
        }
        return values;
    }
}
