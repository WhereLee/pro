package com.lrs.buddy.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档 ↔ DDL ↔ 代码 的契约漂移测试（swap-plan.md M0-5）。
 *
 * <p>存在理由：本项目的设计真相分散在三处载体——Flyway 迁移（枚举闭集与生成列）、
 * 设计文档（状态机与协议清单）、Java 源码（{@code @PreAuthorize} 权限码）。
 * 任何一处单独修改都会让另两处过时，而这类偏差在运行期表现为
 * "这个事件没人处理"或"这个状态建不了单"，排查成本极高。
 * 本测试把"三方一致"变成 CI 硬门禁，而不是靠人记得同步改文档。
 *
 * <p>纯文件解析，不启动 Spring；解析失败即断言失败并指明文件与缺失项。
 */
class SwapDdlContractTest {

    private static final Path MIGRATION_DIR = locate("src/main/resources/db/migration");
    private static final Path DOCS_DIR = locate("docs");
    private static final Path MAIN_JAVA = locate("src/main/java");

    /** 迁移脚本中的枚举标记约定：{@code -- @enum 表名.列名: A|B|C}（把文档枚举变成机器可读）。 */
    private static final Pattern ENUM_MARKER =
            Pattern.compile("--\\s*@enum\\s+([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+):\\s*([A-Za-z0-9_| ]+)");

    private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE\\s+([A-Za-z0-9_]+)\\s*\\(");
    private static final Pattern CHECK_IN =
            Pattern.compile("CHECK\\s*\\(\\s*([A-Za-z0-9_]+)\\s+IN\\s*\\(([^)]*)\\)\\s*\\)", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");
    private static final Pattern UPPER_TOKEN = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*\\b");
    private static final Pattern LOWER_TOKEN = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

    @Test
    @DisplayName("@enum 标记必须指向真实表与列，且与其 CHECK 枚举集合完全相等")
    void enumMarkersResolveToRealColumnsAndMatchCheckSets() {
        Map<String, String> tables = tableChunks(migrationSql());
        Map<String, Set<String>> markers = enumMarkers(migrationSql());
        Map<String, Set<String>> checkEnums = checkEnumColumns(migrationSql());

        assertThat(markers).as("迁移脚本中应存在 @enum 标记，否则本测试失效").isNotEmpty();

        markers.forEach((key, marked) -> {
            String table = key.substring(0, key.indexOf('.'));
            String column = key.substring(key.indexOf('.') + 1);
            String chunk = tables.get(table);
            assertThat(chunk).as("@enum 标记指向不存在的表：" + key).isNotNull();
            assertThat(chunk).as("@enum 标记指向不存在的列：" + key)
                    .containsPattern(Pattern.compile("\\b" + column + "\\b\\s+"));

            Set<String> checked = checkEnums.get(key);
            if (checked != null) {
                assertThat(marked).as("@enum 标记与 CHECK 枚举集合不一致：" + key)
                        .containsExactlyInAnyOrderElementsOf(checked);
            }
        });
    }

    @Test
    @DisplayName("每个带 CHECK(... IN (...)) 的枚举列都必须有 @enum 标记（禁止半声明枚举）")
    void everyCheckEnumColumnIsDocumented() {
        Map<String, Set<String>> markers = enumMarkers(migrationSql());
        Map<String, Set<String>> checkEnums = checkEnumColumns(migrationSql());

        // 排除非状态语义的取值集合（Q2：本项目的枚举列均应显式声明）
        Set<String> exempt = Set.of("iot_product.category");

        List<String> undocumented = checkEnums.keySet().stream()
                .filter(k -> !markers.containsKey(k))
                .filter(k -> !exempt.contains(k))
                .sorted()
                .collect(Collectors.toList());
        assertThat(undocumented).as("以下枚举列有 CHECK 但缺 @enum 标记").isEmpty();
    }

    @Test
    @DisplayName("FSM §4.1 声明的订单状态集合必须等于 swap_order.order_state 的 DB 枚举集合")
    void fsmOrderStatesMatchDatabaseEnum() {
        Set<String> docStates = fsmStatesFrom41();
        Set<String> dbStates = unquotedSet(checkEnumOfColumn(migrationSql(), "swap_order", "ck_ord_state"));

        assertThat(docStates).as("FSM §4.1 未解析到状态，解析已失效").isNotEmpty();
        assertThat(docStates).as("文档状态集合与 DB 枚举不一致（新增/改名必须两处同步）")
                .containsExactlyInAnyOrderElementsOf(dbStates);
    }

    @Test
    @DisplayName("FSM 非终态集合必须等于 swap_order.active_user 生成列的在途状态列表")
    void fsmNonTerminalSetMatchesActiveUserGeneratedColumn() {
        Set<String> nonTerminal = fsmStatesFrom41().stream()
                .filter(s -> !fsmTerminalStates().contains(s))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> inFlight = inFlightStatesFromActiveUserColumn();

        assertThat(nonTerminal).as("FSM 未解析到非终态，解析已失效").isNotEmpty();
        // 这条断言防的是两类真实事故：漏掉 SUSPENDED 等挂起态 → 用户可刷双单；
        // 多算终态 → 用户永久无法新建订单。两者在功能测试里都很难暴露。
        assertThat(inFlight).as("生成列在途集合与 FSM 非终态集合不一致")
                .containsExactlyInAnyOrderElementsOf(nonTerminal);
    }

    @Test
    @DisplayName("FSM §4.2 的步骤码与步骤态必须等于 swap_order_step 的 DB 枚举集合")
    void fsmStepCodesAndStatesMatchDatabaseEnum() {
        String section = section(fsmDoc(), "### 4.2 步骤定义", "### 4.3");
        Set<String> docCodes = new LinkedHashSet<>();
        for (String cell : firstCellsOfTableRows(section)) {
            Matcher m = UPPER_TOKEN.matcher(cell.replaceAll("\\*\\*", ""));
            while (m.find()) {
                String token = m.group();
                if (!token.matches("S\\d")) {
                    docCodes.add(token);
                }
            }
        }
        assertThat(docCodes).as("FSM §4.2 步骤码解析结果").isNotEmpty();
        assertThat(docCodes).containsExactlyInAnyOrderElementsOf(
                unquotedSet(checkEnumOfColumn(migrationSql(), "swap_order_step", "ck_step_code")));

        String stepStateLine = fsmDoc().lines()
                .filter(l -> l.startsWith("步骤态："))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FSM 缺少『步骤态：』行，解析已失效"));
        Set<String> docStates = new LinkedHashSet<>();
        Matcher sm = UPPER_TOKEN.matcher(stepStateLine);
        while (sm.find()) {
            docStates.add(sm.group());
        }
        assertThat(docStates).as("DB 与文档的步骤态集合不一致").containsExactlyInAnyOrderElementsOf(
                unquotedSet(checkEnumOfColumn(migrationSql(), "swap_order_step", "ck_step_state")));
    }

    @Test
    @DisplayName("FSM 迁移表引用的事件与指令必须都在协议清单中定义")
    void fsmEventAndCommandReferencesResolveToProtocol() {
        String migrationSection = section(fsmDoc(), "## 5. 完整迁移表", "## 6.");
        String protocol = protocolDoc();
        String eventTable = section(protocol, "### 5.1 事件清单", "### 5.2");
        Set<String> protocolEvents = new LinkedHashSet<>();
        for (String cell : firstCellsOfTableRows(eventTable)) {
            Matcher m = LOWER_TOKEN.matcher(cell);
            while (m.find()) {
                protocolEvents.add(m.group());
            }
        }
        protocolEvents.add("online");
        protocolEvents.add("offline");

        Set<String> referencedEvents = collect(migrationSection, "evt:([a-z_]+)");
        assertThat(referencedEvents).as("FSM §5 未解析到 evt: 引用，解析已失效").isNotEmpty();
        assertThat(protocolEvents).as("协议 §5.1 未解析到事件清单，解析已失效").isNotEmpty();
        assertThat(protocolEvents).as("FSM 引用了协议未定义的事件")
                .containsAll(referencedEvents);

        String cmdMatrix = section(protocol, "### 4.1 换电指令集", "### 4.2");
        Set<String> protocolCmds = new LinkedHashSet<>();
        for (String cell : firstCellsOfTableRows(cmdMatrix)) {
            Matcher m = UPPER_TOKEN.matcher(cell);
            while (m.find()) {
                protocolCmds.add(m.group());
            }
        }
        Set<String> referencedCmds = collect(migrationSection, "dispatch\\(([A-Z_]+)\\)");
        assertThat(protocolCmds).as("协议 §4.1 未解析到指令矩阵，解析已失效").isNotEmpty();
        assertThat(protocolCmds).as("FSM 下发协议未定义的指令").containsAll(referencedCmds);
    }

    @Test
    @DisplayName("Java 源码使用的权限码必须都在 Flyway sys_menu 种子中定义")
    void preAuthorizePermsAreDefinedInMigrations() {
        Set<String> defined = new LinkedHashSet<>();
        Matcher dm = Pattern.compile("'([a-zA-Z][\\w]*(?::[\\w]+)+)'").matcher(migrationSql());
        while (dm.find()) {
            defined.add(dm.group(1));
        }
        assertThat(defined).as("迁移中未解析到权限码，解析已失效").hasSizeGreaterThan(10);

        Set<String> used = new LinkedHashSet<>();
        Pattern perm = Pattern.compile("hasAuthority\\(\\s*'([^']+)'\\s*\\)");
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                String src = read(p);
                Matcher m = perm.matcher(src);
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(used).as("源码未解析到任何 hasAuthority，解析已失效").isNotEmpty();
        List<String> drift = used.stream().filter(u -> !defined.contains(u)).sorted().collect(Collectors.toList());
        assertThat(drift).as("源码引用了 Flyway 未定义的权限码（接口会永久 403 或菜单不显）").isEmpty();
    }

    // ---------------- 解析辅助 ----------------

    private static String migrationSql() {
        try (Stream<Path> s = Files.list(MIGRATION_DIR)) {
            return s.filter(p -> p.getFileName().toString().matches("V\\d+.*\\.sql"))
                    .sorted(Comparator.comparingInt(SwapDdlContractTest::versionOf))
                    .map(SwapDdlContractTest::read)
                    .collect(Collectors.joining("\n\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int versionOf(Path p) {
        String n = p.getFileName().toString();
        return Integer.parseInt(n.substring(1, n.indexOf("__")));
    }

    /** 表名 → 该表 CREATE TABLE 语句块（到下一个 CREATE TABLE 或文件末尾）。 */
    private static Map<String, String> tableChunks(String sql) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = CREATE_TABLE.matcher(sql);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
            spans.add(new int[]{m.start(), m.end()});
        }
        for (int i = 0; i < names.size(); i++) {
            int from = spans.get(i)[0];
            int to = (i + 1 < names.size()) ? spans.get(i + 1)[0] : sql.length();
            map.put(names.get(i), sql.substring(from, to));
        }
        return map;
    }

    private static Map<String, Set<String>> enumMarkers(String sql) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        Matcher m = ENUM_MARKER.matcher(sql);
        while (m.find()) {
            Set<String> values = new LinkedHashSet<>();
            for (String v : m.group(3).split("\\|")) {
                if (!v.isBlank()) {
                    values.add(v.trim());
                }
            }
            map.put(m.group(1) + "." + m.group(2), values);
        }
        return map;
    }

    private static Map<String, Set<String>> checkEnumColumns(String sql) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        tableChunks(sql).forEach((table, chunk) -> {
            Matcher m = CHECK_IN.matcher(chunk);
            while (m.find()) {
                Set<String> values = new LinkedHashSet<>();
                Matcher q = QUOTED.matcher(m.group(2));
                while (q.find()) {
                    values.add(q.group(1));
                }
                if (!values.isEmpty()) {
                    map.put(table + "." + m.group(1), values);
                }
            }
        });
        return map;
    }

    private static String checkEnumOfColumn(String sql, String table, String constraintName) {
        String chunk = tableChunks(sql).get(table);
        assertThat(chunk).as("迁移中找不到表 " + table).isNotNull();
        Matcher m = Pattern.compile("CONSTRAINT\\s+" + constraintName + "\\s+CHECK\\s*\\(([^;]*?)\\)\\s*[,\n]",
                Pattern.DOTALL).matcher(chunk);
        assertThat(m.find()).as("表 " + table + " 缺少约束 " + constraintName).isTrue();
        return m.group(1);
    }

    private static Set<String> unquotedSet(String raw) {
        Set<String> values = new LinkedHashSet<>();
        Matcher q = QUOTED.matcher(raw);
        while (q.find()) {
            values.add(q.group(1));
        }
        assertThat(values).as("未解析到枚举取值，正则可能失配").isNotEmpty();
        return values;
    }

    private static Set<String> inFlightStatesFromActiveUserColumn() {
        String chunk = tableChunks(migrationSql()).get("swap_order");
        assertThat(chunk).as("迁移中找不到 swap_order").isNotNull();
        Matcher m = Pattern.compile("active_user\\s+BIGINT\\s+AS\\s*\\(CASE\\s+WHEN\\s+order_state\\s+IN\\s*\\(([^)]*)\\)",
                Pattern.DOTALL).matcher(chunk);
        assertThat(m.find()).as("swap_order.active_user 生成列定义与解析约定不符（改动需同步本测试）").isTrue();
        return unquotedSet(m.group(1));
    }

    private static Set<String> fsmStatesFrom41() {
        String section = section(fsmDoc(), "### 4.1 订单状态", "### 4.2");
        Set<String> states = new LinkedHashSet<>();
        for (String cell : firstCellsOfTableRows(section)) {
            Matcher b = Pattern.compile("`([A-Z][A-Z_]+)`").matcher(cell);
            if (b.find()) {
                states.add(b.group(1));
            }
        }
        return states;
    }

    private static Set<String> fsmTerminalStates() {
        String section = section(fsmDoc(), "### 4.1 订单状态", "### 4.2");
        Set<String> terminal = new LinkedHashSet<>();
        for (String line : section.lines().filter(l -> l.startsWith("| `")).collect(Collectors.toList())) {
            String[] cells = line.split("\\|");
            // | 状态 | 语义 | 终态 | ...  → cells[1]=状态 cells[3]=终态
            if (cells.length > 3 && cells[3].contains("是")) {
                Matcher b = Pattern.compile("`([A-Z][A-Z_]+)`").matcher(cells[1]);
                if (b.find()) {
                    terminal.add(b.group(1));
                }
            }
        }
        assertThat(terminal).as("FSM §4.1 未解析到终态，解析已失效").isNotEmpty();
        return terminal;
    }

    /** markdown 表格每行的第一个数据单元格（跳过表头与分隔行）。 */
    private static List<String> firstCellsOfTableRows(String section) {
        List<String> cells = new ArrayList<>();
        for (String line : section.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|") || t.startsWith("|---") || t.startsWith("|-")) {
                continue;
            }
            String[] parts = t.split("\\|");
            if (parts.length < 2 || parts[1].isBlank() || parts[1].contains("---")) {
                continue;
            }
            if (parts[1].trim().matches("[#*\\sA-Za-z]*") && parts[1].isBlank()) {
                continue;
            }
            cells.add(parts[1].trim());
        }
        return cells;
    }

    private static Set<String> collect(String text, String regex) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String section(String markdown, String fromHeading, String toHeading) {
        int from = markdown.indexOf(fromHeading);
        assertThat(from).as("文档缺少小节：" + fromHeading).isGreaterThan(-1);
        int to = markdown.indexOf(toHeading, from + fromHeading.length());
        assertThat(to).as("文档缺少小节：" + toHeading).isGreaterThan(from);
        return markdown.substring(from, to);
    }

    private static String fsmDoc() {
        return read(DOCS_DIR.resolve("swap-order-fsm.md"));
    }

    private static String protocolDoc() {
        return read(DOCS_DIR.resolve("swap-protocol.md"));
    }

    private static String read(Path p) {
        assertThat(Files.exists(p)).as("文件不存在：" + p.toAbsolutePath()).isTrue();
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 兼容两种工作目录：Maven 模块目录（buddy/）与仓库根目录。 */
    private static Path locate(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) {
            return direct.normalize();
        }
        Path underModule = Paths.get("buddy").resolve(relative);
        assertThat(Files.exists(underModule))
                .as("无法定位 " + relative + "（试过 " + direct.toAbsolutePath() + " 与 " + underModule.toAbsolutePath() + "）")
                .isTrue();
        return underModule.normalize();
    }
}
