import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * FlashMart 限时抢购平台 —— 压测 / 实验工具（纯 JDK 单文件，无任何第三方依赖）。
 *
 * <p>用途：把系统的关键指标（吞吐、P99、超卖、缓存回源次数、限流分布、超时关单……）
 * 在本机真实跑出来，作为验收与回归的依据。工具只做两件事：<b>发请求</b>和<b>把结果算成表格</b>，
 * 所有结论都来自服务端返回的真实数据，测不到就打印"未获取到"，绝不编造。
 *
 * <p>运行方式（二选一）：
 * <pre>
 *   java Bench.java --help
 *   javac Bench.java &amp;&amp; java Bench --help
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>HTTP 用 JDK 自带的 java.net.http.HttpClient；</li>
 *   <li>并发模型用"虚拟线程"（JDK 21+），在 JDK 17 上自动回退到平台线程池（反射探测，不写死版本）；</li>
 *   <li>JSON 用一个 120 行的手写解析器，避免引入 fastjson/jackson；</li>
 *   <li>压测用"闭环模型"：固定 N 个工作线程串行发请求，绝不一次性把 2000 个请求全丢出去；</li>
 *   <li>表格按"中文算 2 个字符宽"对齐，保证中文控制台下表格不歪。</li>
 * </ul>
 */
public class Bench {

    // ==================================================================
    // 0. 全局配置（由命令行解析填充）
    // ==================================================================

    /** 服务地址，可用 --base 覆盖 */
    static String base = "http://localhost:18090";
    /** 要跑的实验名 */
    static String exp = "";
    /** prepare 的账号数量 */
    static int users = 200;
    static boolean usersSet = false;
    /** 活动 id */
    static long activity = 9001;
    /** 重置库存（不同实验默认值不同：超卖 100 / 吞吐 1000000 / 击穿 9999） */
    static int stock = 100;
    static boolean stockSet = false;
    /** 次数（一人多次 / 穿透查询次数） */
    static int times = 20;
    static boolean timesSet = false;
    /** throughput 的模式：async / sync */
    static String mode = "async";
    /** 闭环压测的并发工作线程数 */
    static int concurrency = 50;
    /** 闭环压测的总请求数 */
    static int requests = 2000;
    /** prepare 的起始手机号（递增） */
    static long startPhone = 13900010000L;
    /** prepare 每次登录之间的间隔，避开"登录按 IP 限流 20 次/秒" */
    static int loginIntervalMs = 60;
    /** 真实链路实验使用的演示买家账号 */
    static String phone = "13911110001";
    static String password = "123456";
    /** 强制 UTF-8 输出（重定向到文件 / 非中文控制台时用） */
    static boolean utf8 = false;
    /** cache-penetrate 不等空值标记过期 */
    static boolean noWait = false;
    /** 等空值标记过期的秒数 */
    static int waitTtlSeconds = 31;
    /** 单请求超时 */
    static int requestTimeoutMs = 15000;

    /** bench 目录（Bench.java 所在目录），tokens.txt 也放这里 */
    static Path benchDir;
    static Path tokensFile;

    /** 一次 burst 的整体墙钟耗时（微秒），由 burst() 填充 */
    static long lastBurstMicros = 0;
    /** 线程模型描述，启动时探测 */
    static String threadModel = "平台线程池（JDK 17 回退）";

    static HttpClient httpClient;

    // ==================================================================
    // 1. 入口 & 命令行解析
    // ==================================================================

    public static void main(String[] args) {
        parseArgs(args);

        if (utf8) {
            // 某些终端（GBK 控制台 / 重定向到文件）下中文会乱码，--utf8 强制 UTF-8 输出
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        }
        benchDir = resolveBenchDir();
        tokensFile = benchDir.resolve("tokens.txt");
        detectThreadModel();

        if (exp.isEmpty() || "help".equals(exp) || "usage".equals(exp)) {
            printUsage();
            return;
        }

        if (!probeServer()) {
            System.exit(2);
            return;
        }

        try {
            switch (exp) {
                case "prepare" -> expPrepare();
                case "oversell" -> expOversell();
                case "oneuser" -> expOneUser();
                case "throughput" -> expThroughput();
                case "cache-miss" -> expCacheMiss();
                case "cache-penetrate" -> expCachePenetrate();
                case "ratelimit" -> expRateLimit();
                case "timeout" -> expTimeout();
                case "pay" -> expPay();
                default -> {
                    System.out.println("[错误] 未知实验: " + exp);
                    printUsage();
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.out.println();
            System.out.println("[异常] 实验 " + exp + " 执行失败: " + e);
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /** 极简参数解析：支持 --key value 与 --key=value；无值的开关直接给 true */
    static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            String value = null;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                value = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            switch (key) {
                case "base" -> base = trimSlash(value);
                case "exp" -> exp = value == null ? "" : value;
                case "users" -> { users = parseInt(value, users); usersSet = true; }
                case "activity" -> activity = parseLong(value, activity);
                case "stock" -> { stock = parseInt(value, stock); stockSet = true; }
                case "times" -> { times = parseInt(value, times); timesSet = true; }
                case "mode" -> mode = value == null ? mode : value;
                case "concurrency" -> concurrency = parseInt(value, concurrency);
                case "requests" -> requests = parseInt(value, requests);
                case "phone" -> phone = value == null ? phone : value;
                case "password" -> password = value == null ? password : value;
                case "start-phone" -> startPhone = parseLong(value, startPhone);
                case "login-interval-ms" -> loginIntervalMs = parseInt(value, loginIntervalMs);
                case "timeout-ms" -> requestTimeoutMs = parseInt(value, requestTimeoutMs);
                case "wait-ttl" -> waitTtlSeconds = parseInt(value, waitTtlSeconds);
                case "utf8" -> utf8 = value == null || Boolean.parseBoolean(value);
                case "no-wait" -> noWait = value == null || Boolean.parseBoolean(value);
                case "help" -> exp = "help";
                default -> System.out.println("[提示] 未识别的参数: --" + key + "（用 --help 查看用法）");
            }
        }
    }

    static String trimSlash(String v) {
        if (v == null) return base;
        String s = v.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static int parseInt(String v, int def) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    static long parseLong(String v, long def) {
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return def; }
    }

    /** tokens.txt 的位置：优先按 Bench.java 所在目录，其次按 class 文件目录，最后按当前工作目录 */
    static Path resolveBenchDir() {
        String cmd = System.getProperty("sun.java.command", "");
        if (!cmd.isBlank()) {
            String first = cmd.trim().split("\\s+")[0];
            if (first.endsWith(".java")) {
                Path p = Paths.get(first).toAbsolutePath().getParent();
                if (p != null) return p;
            }
        }
        try {
            var src = Bench.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path p = Paths.get(src.getLocation().toURI());
                if (Files.isDirectory(p)) return p;
            }
        } catch (Exception ignored) {
            // 取不到就退回当前目录
        }
        return Paths.get("").toAbsolutePath();
    }

    static void printUsage() {
        String nl = System.lineSeparator();
        System.out.println("FlashMart 压测工具（纯 JDK，单文件）");
        System.out.println("用法: java Bench.java --exp <实验名> [参数...]");
        System.out.println();
        System.out.println("通用参数:");
        System.out.println("  --base <url>         服务地址，默认 http://localhost:18090");
        System.out.println("  --activity <id>      活动 id，默认 9001（9003 未开始 / 9004 已结束）");
        System.out.println("  --stock <n>          压测前 reset 的库存");
        System.out.println("  --concurrency <n>    并发数（击穿/限流/吞吐用）");
        System.out.println("  --timeout-ms <n>     单请求超时，默认 15000");
        System.out.println("  --utf8               输出强制 UTF-8（Windows GBK 控制台/重定向时用）");
        System.out.println("  --help               打印本帮助");
        System.out.println();
        System.out.println("实验列表:");
        System.out.println("  --exp prepare   --users 200");
        System.out.println("        批量注册+登录压测账号，token 写入 " + (tokensFile == null ? "bench/tokens.txt" : tokensFile.toString()));
        System.out.println("  --exp oversell  --activity 9001 --stock 100 --users 500");
        System.out.println("        500 个不同用户同时抢 100 件，验证不超卖（orderCount == 成功数 <= stock）");
        System.out.println("  --exp oneuser   --activity 9001 --times 20");
        System.out.println("        同一用户并发 20 次，验证一人一单 + 限流拦截");
        System.out.println("  --exp throughput --activity 9001 --mode async|sync --concurrency 50 --requests 2000");
        System.out.println("        闭环压测，输出成功率 / 平均 / P50 / P90 / P95 / P99 / 吞吐");
        System.out.println("  --exp cache-miss --activity 9001 --concurrency 100");
        System.out.println("        缓存击穿：删缓存后 100 并发查详情，看回源次数（期望远小于 100）");
        System.out.println("  --exp cache-penetrate --activity 99999 --times 200");
        System.out.println("        缓存穿透：200 次查不存在的活动，看回源次数（期望 1）");
        System.out.println("  --exp ratelimit --activity 9001");
        System.out.println("        限流：同一用户 1 秒内 10 次并发 + 每 1.2 秒 1 次匀速请求");
        System.out.println("  --exp timeout   --activity 9001");
        System.out.println("        超时关单：抢购后不支付，force-timeout 触发关单，验证库存回滚");
        System.out.println("  --exp pay       --activity 9001");
        System.out.println("        支付链路 + 重复支付幂等");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java Bench.java --exp prepare --users 60");
        System.out.println("  java Bench.java --exp oneuser --times 20");
        System.out.println();
        System.out.println("注意：/api/activity/** 与 /api/bench/** 都需要 Authorization: Bearer <token>，");
        System.out.println("      工具会自动登录演示买家 " + phone + " 作为压测调用方。");
    }

    // ==================================================================
    // 2. 输出工具（ASCII 表格 + 中文宽度对齐）
    // ==================================================================

    static void section(String title) {
        System.out.println();
        System.out.println("==================== " + title + " ====================");
    }

    static void kv(String key, Object value) {
        System.out.println("  " + padRight(key, 26) + " : " + value);
    }

    static void note(String text) {
        System.out.println("  [提示] " + text);
    }

    /** 结论行：PASS / FAIL 用 ASCII 方括号，方便 grep */
    static void verdict(boolean pass, String text) {
        System.out.println("  " + (pass ? "[PASS]" : "[FAIL]") + " " + text);
    }

    static void printTable(String[] headers, List<String[]> rows) {
        int n = headers.length;
        int[] w = new int[n];
        for (int i = 0; i < n; i++) w[i] = dispWidth(headers[i]);
        for (String[] row : rows) {
            for (int i = 0; i < n; i++) {
                String c = (row != null && i < row.length && row[i] != null) ? row[i] : "";
                w[i] = Math.max(w[i], dispWidth(c));
            }
        }
        String sep = border(w);
        System.out.println("  " + sep);
        System.out.println("  " + rowLine(headers, w));
        System.out.println("  " + sep);
        for (String[] row : rows) System.out.println("  " + rowLine(row, w));
        System.out.println("  " + sep);
    }

    static String border(int[] w) {
        StringBuilder sb = new StringBuilder("+");
        for (int x : w) sb.append("-".repeat(x + 2)).append('+');
        return sb.toString();
    }

    static String rowLine(String[] cells, int[] w) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < w.length; i++) {
            String c = (cells != null && i < cells.length && cells[i] != null) ? cells[i] : "";
            sb.append(' ').append(padRight(c, w[i])).append(" |");
        }
        return sb.toString();
    }

    /** 显示宽度：CJK 字符算 2 列 */
    static int dispWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += isWide(cp) ? 2 : 1;
        }
        return w;
    }

    static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    static String padRight(String s, int width) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        int w = dispWidth(s);
        while (w++ < width) sb.append(' ');
        return sb.toString();
    }

    static String repeat(char c, int n) {
        return String.valueOf(c).repeat(Math.max(0, n));
    }

    /** 把 Map 打成 {k=v, k=v} 便于单行展示 */
    static String fmtMap(Object o) {
        if (!(o instanceof Map<?, ?> m) || m.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================================================================
    // 3. 迷你 JSON 解析器（只解析服务端返回的 {code,message,data}，够用就好）
    // ==================================================================

    static final class JsonParser {
        private final String s;
        private int i;

        private JsonParser(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            JsonParser p = new JsonParser(text);
            p.skipWs();
            return p.parseValue();
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object parseValue() {
            skipWs();
            if (i >= s.length()) throw new IllegalStateException("JSON 意外结束");
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return parseNumber();
        }

        private void expect(String word) {
            if (!s.startsWith(word, i)) throw new IllegalStateException("非法字面量 @" + i);
            i += word.length();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                if (s.charAt(i) != ':') throw new IllegalStateException("缺少冒号 @" + i);
                i++;
                m.put(k, parseValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalStateException("对象分隔符非法 @" + i);
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalStateException("数组分隔符非法 @" + i);
            }
        }

        private String parseString() {
            if (s.charAt(i) != '"') throw new IllegalStateException("期望字符串 @" + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalStateException("字符串未闭合");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            String num = s.substring(start, i);
            if (num.isEmpty()) throw new IllegalStateException("非法数字 @" + start);
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.emptyList();
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static long lng(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (Exception ignored) { /* 落到 -1 */ }
        }
        return -1;
    }

    /** 按路径取值：get(root, "data", "token") */
    static Object get(Object root, String... path) {
        Object cur = root;
        for (String k : path) {
            if (cur instanceof Map<?, ?> m) cur = m.get(k);
            else return null;
        }
        return cur;
    }

    // ==================================================================
    // 4. HTTP 层
    // ==================================================================

    static HttpClient http() {
        if (httpClient == null) {
            // 显式用 HTTP/1.1：避免明文 h2c 升级握手给压测结果引入额外抖动
            httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
        return httpClient;
    }

    /** 一次 HTTP 调用的原始结果 */
    static final class HttpResult {
        final int status;
        final String body;
        final long micros;
        final String error;

        HttpResult(int status, String body, long micros, String error) {
            this.status = status;
            this.body = body;
            this.micros = micros;
            this.error = error;
        }
    }

    static HttpResult call(String method, String path, String token, String jsonBody) {
        long t0 = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofMillis(requestTimeoutMs));
            if (jsonBody != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
            HttpResponse<byte[]> resp = http().send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            long us = (System.nanoTime() - t0) / 1000;
            return new HttpResult(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8), us, null);
        } catch (Exception e) {
            long us = (System.nanoTime() - t0) / 1000;
            String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new HttpResult(-1, "", us, msg);
        }
    }

    /** 解析后的 {code,message,data} */
    static final class Api {
        final int httpStatus;
        final int code;
        final String message;
        final Object data;
        final String error;
        final long micros;

        Api(int httpStatus, int code, String message, Object data, String error, long micros) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.message = message == null ? "" : message;
            this.data = data;
            this.error = error;
            this.micros = micros;
        }

        boolean ok() {
            return error == null && httpStatus == 200 && code == 0;
        }

        String describe() {
            if (error != null) return "请求失败(" + error + ")";
            return "HTTP " + httpStatus + " code=" + code + (message.isEmpty() ? "" : " message=\"" + message + "\"");
        }

        static Api transportError(Throwable t) {
            return new Api(-1, -1, "", null, String.valueOf(t), 0);
        }
    }

    static Api api(HttpResult r) {
        if (r.error != null) return new Api(-1, -1, "", null, r.error, r.micros);
        int code = -1;
        String message = "";
        Object data = null;
        try {
            Map<String, Object> m = asMap(JsonParser.parse(r.body));
            code = (int) lng(m.get("code"));
            message = str(m.get("message"));
            data = m.get("data");
        } catch (Exception e) {
            message = "响应解析失败: " + r.body;
        }
        return new Api(r.status, code, message, data, null, r.micros);
    }

    static Api get_(String path, String token) {
        return api(call("GET", path, token, null));
    }

    static Api post(String path, String token) {
        return api(call("POST", path, token, null));
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 只带字符串字段的 JSON body */
    static String jsonBody(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    /** 服务可用性探测：连不上直接给出人话提示 */
    static boolean probeServer() {
        HttpResult r = call("GET", "/api/activity/list", null, null);
        if (r.error != null) {
            System.out.println();
            System.out.println("[错误] 无法连接后端 " + base + " -> " + r.error);
            System.out.println("       请确认后端已在 " + base + " 运行；压测接口只在 bench profile 下注册：");
            System.out.println("       java -jar server/target/flashmart-server.jar --spring.profiles.active=bench");
            return false;
        }
        System.out.println("[环境] 后端 " + base + " 可达（探测请求返回 HTTP " + r.status + "，401 属正常：接口需要 token）");
        System.out.println("[环境] 线程模型: " + threadModel + "；tokens 文件: " + tokensFile);
        return true;
    }

    // ==================================================================
    // 5. 账号 / token
    // ==================================================================

    /** 登录，失败返回 null 并打印原因；遇到 429（登录按 IP 限流）自动等待重试 */
    static String login(String p, String pw) {
        for (int attempt = 0; attempt < 4; attempt++) {
            Api a = api(call("POST", "/api/auth/login", null, jsonBody("phone", p, "password", pw)));
            if (a.ok()) return str(get(a.data, "token"));
            if (a.httpStatus == 429 || a.code == 429) {
                sleepMs(1200);
                continue;
            }
            System.out.println("  [错误] 登录失败 " + p + " -> " + a.describe());
            return null;
        }
        System.out.println("  [错误] 登录失败 " + p + " -> 连续触发登录限流");
        return null;
    }

    /** 压测调用方 token：优先用演示买家账号，失败则回退 tokens.txt 第一个 */
    static String benchToken() {
        String t = login(phone, password);
        if (t != null) return t;
        List<String> toks = readTokens();
        if (!toks.isEmpty()) {
            note("演示账号登录失败，回退使用 tokens.txt 第 1 个 token");
            return toks.get(0);
        }
        System.out.println("  [错误] 拿不到任何可用 token，请先跑 --exp prepare --users 10");
        return null;
    }

    static List<String> readTokens() {
        if (tokensFile == null || !Files.exists(tokensFile)) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(tokensFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        } catch (Exception e) {
            System.out.println("  [错误] 读取 " + tokensFile + " 失败: " + e);
            return List.of();
        }
    }

    static boolean requireTokens(int need) {
        List<String> toks = readTokens();
        if (toks.size() < need) {
            System.out.println("  [错误] " + tokensFile + " 里只有 " + toks.size() + " 个 token，需要 " + need + " 个");
            System.out.println("         先执行: java Bench.java --exp prepare --users " + need);
            return false;
        }
        return true;
    }

    // ==================================================================
    // 6. 并发基础设施
    // ==================================================================

    static ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r, "bench-worker");
            t.setDaemon(true);
            return t;
        };
    }

    /** 启动时探测一次线程模型（JDK 21+ 有虚拟线程，JDK 17 没有） */
    static void detectThreadModel() {
        try {
            Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            threadModel = "虚拟线程 (Executors.newVirtualThreadPerTaskExecutor，JDK 21+)";
        } catch (Throwable t) {
            threadModel = "平台线程池 (newCachedThreadPool，JDK 17 回退)";
        }
    }

    /** 优先虚拟线程（JDK 21+），JDK 17 上反射探测失败则回退平台线程池 */
    static ExecutorService newExecutor() {
        try {
            Method m = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            Object o = m.invoke(null);
            return (ExecutorService) o;
        } catch (Throwable t) {
            return Executors.newCachedThreadPool(daemonFactory());
        }
    }

    /**
     * "同时发起"一批请求：所有任务先在栅栏上等齐，再一起放行。
     * 返回每个请求的结果，顺序与传入任务一致。
     */
    static Api[] burst(List<Supplier<Api>> tasks) {
        int n = tasks.size();
        Api[] out = new Api[n];
        if (n == 0) return out;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ExecutorService pool = newExecutor();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final Supplier<Api> task = tasks.get(i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    out[idx] = task.get();
                } catch (Throwable t) {
                    out[idx] = Api.transportError(t);
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sleepMs(30); // 给最后一批线程一点时间真正就位，尽量让请求"同时"打出去
        long t0 = System.nanoTime();
        start.countDown();
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lastBurstMicros = (System.nanoTime() - t0) / 1000;
        pool.shutdown();
        return out;
    }

    // ==================================================================
    // 7. 结果分类 & 常用接口封装
    // ==================================================================

    enum Kind { OK, SOLD_OUT, OVER_LIMIT, RATE_LIMITED, UNAUTHORIZED, ERROR, OTHER }

    static String kindName(Kind k) {
        return switch (k) {
            case OK -> "成功进入队列";
            case SOLD_OUT -> "已被抢光";
            case OVER_LIMIT -> "触发限购";
            case RATE_LIMITED -> "限流(429)";
            case UNAUTHORIZED -> "未授权(401)";
            case ERROR -> "连接/请求失败";
            case OTHER -> "其他";
        };
    }

    static Kind kindOf(Api a) {
        if (a.error != null) return Kind.ERROR;
        if (a.httpStatus == 401) return Kind.UNAUTHORIZED;
        if (a.httpStatus == 429 || a.code == 429) return Kind.RATE_LIMITED;
        if (a.code == 0) return Kind.OK;
        if (a.message.contains("抢光") || a.message.contains("售罄")) return Kind.SOLD_OUT;
        if (a.message.contains("限购")) return Kind.OVER_LIMIT;
        return Kind.OTHER;
    }

    static Api sendSeckill(String token) {
        return post("/api/seckill/" + activity, token);
    }

    static Api resetActivity(String token, long activityId, long stockValue, boolean clearOrders) {
        Api a = post("/api/bench/reset/" + activityId + "?stock=" + stockValue + "&clearOrders=" + clearOrders, token);
        if (!a.ok()) {
            System.out.println("  [错误] reset 失败: " + a.describe());
        } else {
            kv("已重置活动 " + activityId, "stock=" + stockValue + ", clearOrders=" + clearOrders
                    + ", 物理删除订单数=" + str(get(a.data, "deletedOrders")));
        }
        return a;
    }

    static Map<String, Object> benchStats(String token, long activityId) {
        Api a = get_("/api/bench/stats/" + activityId, token);
        if (!a.ok()) {
            System.out.println("  [错误] 读取 stats 失败: " + a.describe());
            return null;
        }
        return asMap(a.data);
    }

    static long cacheLoadCount(String token, String key) {
        Api a = get_("/api/bench/cache-load-count?key=" + enc(key), token);
        if (!a.ok()) return -1;
        return lng(get(a.data, "loadCount"));
    }

    static void resetCacheLoadCount(String token, String key) {
        Api a = post("/api/bench/cache-load-count/reset?key=" + enc(key), token);
        if (!a.ok()) System.out.println("  [错误] 重置回源计数失败: " + a.describe());
    }

    static void printStats(Map<String, Object> st, String title) {
        if (st == null) {
            System.out.println("  " + title + ": 未获取到");
            return;
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"totalStock（总库存）", str(st.get("totalStock")), ""});
        rows.add(new String[]{"dbAvailableStock（DB 剩余）", str(st.get("dbAvailableStock")), "不能为负"});
        rows.add(new String[]{"redisAvailableStock（Redis 剩余）", str(st.get("redisAvailableStock")), "扣减的真身"});
        rows.add(new String[]{"orderCount（落库订单数）", str(st.get("orderCount")), "不能超过总库存"});
        rows.add(new String[]{"ordersByStatus", fmtMap(st.get("ordersByStatus")), ""});
        rows.add(new String[]{"cacheLoadCount", str(st.get("cacheLoadCount")), "详情缓存回源次数"});
        printTable(new String[]{title + " 指标", "值", "说明"}, rows);
    }

    // ==================================================================
    // 实验 1：prepare —— 批量注册 / 登录压测账号
    // 验证：压测账号池可用；顺便暴露"登录按 IP 限流"的存在
    // ==================================================================
    static void expPrepare() {
        section("实验 1：批量注册/登录压测账号（prepare）");
        kv("目标账号数", users);
        kv("起始手机号", startPhone + "（递增 " + users + " 个）");
        kv("登录间隔", loginIntervalMs + " ms（登录接口按 IP 限流 20 次/秒，太猛会被自己限流）");
        kv("token 输出", tokensFile);
        System.out.println();

        List<String> tokens = new ArrayList<>();
        int registered = 0, existed = 0, loginOk = 0, failed = 0, rateLimited = 0;
        long t0 = System.nanoTime();

        for (int i = 0; i < users; i++) {
            String p = String.valueOf(startPhone + i);
            Api reg = api(call("POST", "/api/auth/register", null,
                    jsonBody("phone", p, "password", password, "nickname", "bench-" + p, "role", "BUYER")));
            String outcome;
            if (reg.ok()) {
                registered++;
                outcome = "注册成功";
            } else if (reg.message.contains("已注册")) {
                existed++;
                outcome = "已注册→改登录";
            } else if (reg.httpStatus == 429 || reg.code == 429) {
                rateLimited++;
                outcome = "注册被限流(429)";
            } else {
                failed++;
                outcome = "注册失败: " + reg.describe();
            }

            String token = login(p, password);
            if (token != null) {
                tokens.add(token);
                loginOk++;
            } else {
                failed++;
                System.out.println("  [失败] " + p + " " + outcome + " → 登录未拿到 token");
            }
            if ((i + 1) % 20 == 0 || i + 1 == users) {
                System.out.println("  进度 " + (i + 1) + "/" + users + "（已拿到 token: " + loginOk + "）");
            }
            sleepMs(loginIntervalMs);
        }

        // 写文件：一行一个 token
        try {
            Files.write(tokensFile, tokens, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("  [错误] 写 " + tokensFile + " 失败: " + e);
        }

        double seconds = (System.nanoTime() - t0) / 1e9;
        System.out.println();
        printTable(new String[]{"统计项", "数量"}, List.of(
                new String[]{"注册成功", String.valueOf(registered)},
                new String[]{"手机号已存在(改为登录)", String.valueOf(existed)},
                new String[]{"注册被限流(429)", String.valueOf(rateLimited)},
                new String[]{"拿到 token", String.valueOf(loginOk)},
                new String[]{"失败", String.valueOf(failed)}
        ));
        kv("耗时", String.format(Locale.ROOT, "%.2f s", seconds));
        kv("tokens.txt 路径", tokensFile.toString());
        verdict(loginOk == users, "可用压测账号 " + loginOk + "/" + users + "（后续实验直接用这些 token 抢购）");
        if (existed > 0) note("有 " + existed + " 个手机号本来就存在，工具按\"已注册就登录\"处理，不会重复注册失败。");
    }

    // ==================================================================
    // 实验 2：oversell —— 500 个不同用户同时抢 100 件，验证不超卖
    // ==================================================================
    static void expOversell() {
        section("实验 2：超卖验证（oversell）");
        if (!usersSet) users = 500;
        if (!stockSet) stock = 100;

        List<String> tokens = readTokens();
        if (tokens.size() < users) {
            System.out.println("  [错误] " + tokensFile + " 里只有 " + tokens.size() + " 个 token，需要 " + users + " 个");
            System.out.println("         先执行: java Bench.java --exp prepare --users " + users);
            return;
        }
        String token = benchToken();
        if (token == null) return;

        kv("活动", activity);
        kv("重置库存", stock);
        kv("并发用户数", users + "（每个用户只发 1 次，互不重复）");
        System.out.println();
        resetActivity(token, activity, stock, true);

        List<Supplier<Api>> tasks = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            String tk = tokens.get(i);
            tasks.add(() -> sendSeckill(tk));
        }
        Api[] results = burst(tasks);

        int ok = 0, soldOut = 0, overLimit = 0, rateLimited = 0, unauthorized = 0, error = 0, other = 0;
        Map<String, Integer> otherMessages = new LinkedHashMap<>();
        for (Api a : results) {
            switch (kindOf(a)) {
                case OK -> ok++;
                case SOLD_OUT -> soldOut++;
                case OVER_LIMIT -> overLimit++;
                case RATE_LIMITED -> rateLimited++;
                case UNAUTHORIZED -> unauthorized++;
                case ERROR -> error++;
                case OTHER -> {
                    other++;
                    otherMessages.merge(a.describe(), 1, Integer::sum);
                }
            }
        }

        System.out.println();
        kv("全部请求发出耗时", String.format(Locale.ROOT, "%.3f s", lastBurstMicros / 1_000_000.0));
        System.out.println();
        printTable(new String[]{"结果分类", "次数", "说明"}, List.of(
                new String[]{"成功进入队列(code=0)", String.valueOf(ok), "拿到 orderNo，进入 MQ 排队"},
                new String[]{"已被抢光", String.valueOf(soldOut), "Redis Lua 扣减返回库存不足"},
                new String[]{"触发限购", String.valueOf(overLimit), "每个用户只发 1 次，正常应为 0"},
                new String[]{"限流(429)", String.valueOf(rateLimited), "每个用户只发 1 次，正常应为 0"},
                new String[]{"未授权(401)", String.valueOf(unauthorized), "token 失效"},
                new String[]{"连接/请求失败", String.valueOf(error), "网络或服务异常"},
                new String[]{"其他", String.valueOf(other), otherMessages.isEmpty() ? "" : otherMessages.toString()}
        ));

        // 等 MQ 异步落库：orderCount 收敛到成功数
        System.out.println();
        note("抢购接口只投递 MQ，订单由消费者异步落库，这里轮询等待 orderCount 收敛（最多 20 秒）");
        long t0 = System.nanoTime();
        int polls = 0;
        Map<String, Object> st = null;
        while ((System.nanoTime() - t0) / 1_000_000 < 20_000) {
            st = benchStats(token, activity);
            polls++;
            if (st != null && lng(st.get("orderCount")) == ok) break;
            sleepMs(200);
        }
        long waitMs = (System.nanoTime() - t0) / 1_000_000;
        boolean converged = st != null && lng(st.get("orderCount")) == ok;
        kv("落库等待", "轮询 " + polls + " 次，用时 " + waitMs + " ms，" + (converged ? "已收敛" : "未收敛（打印最后一次真实值）"));
        System.out.println();
        printStats(st, "活动 " + activity + " 最终");

        if (st == null) {
            System.out.println("  [FAIL] 未获取到 stats，无法判断是否超卖");
            return;
        }
        long orderCount = lng(st.get("orderCount"));
        long dbStock = lng(st.get("dbAvailableStock"));
        long redisStock = lng(st.get("redisAvailableStock"));
        long total = lng(st.get("totalStock"));
        System.out.println();
        verdict(orderCount == ok, "orderCount(" + orderCount + ") == 成功数(" + ok + ")：Redis 扣多少、数据库就落多少单");
        verdict(orderCount <= total, "orderCount(" + orderCount + ") <= totalStock(" + total + ")：没有超卖");
        verdict(dbStock >= 0 && redisStock >= 0, "DB/Redis 剩余库存均非负（db=" + dbStock + ", redis=" + redisStock + "）");
        verdict(overLimit == 0 && rateLimited == 0 && error == 0, "无异常分支（限购=" + overLimit + ", 429=" + rateLimited + ", 失败=" + error + "）");
    }

    // ==================================================================
    // 实验 3：oneuser —— 同一用户并发 20 次
    // ==================================================================
    static void expOneUser() {
        section("实验 3：一人一单 + 限流（oneuser）");
        if (!timesSet) times = 20;
        String token = benchToken();
        if (token == null) return;

        kv("活动", activity);
        kv("同一用户", phone + "（演示买家，真实 /api/seckill 链路）");
        kv("并发次数", times);
        kv("重置库存", stock);
        System.out.println();
        resetActivity(token, activity, stock, true);

        List<Supplier<Api>> tasks = new ArrayList<>();
        for (int i = 0; i < times; i++) tasks.add(() -> sendSeckill(token));
        Api[] results = burst(tasks);

        int ok = 0, overLimit = 0, rateLimited = 0, soldOut = 0, other = 0;
        Map<String, Integer> messages = new LinkedHashMap<>();
        for (Api a : results) {
            switch (kindOf(a)) {
                case OK -> ok++;
                case OVER_LIMIT -> overLimit++;
                case RATE_LIMITED -> rateLimited++;
                case SOLD_OUT -> soldOut++;
                default -> other++;
            }
            messages.merge(a.describe(), 1, Integer::sum);
        }

        System.out.println();
        printTable(new String[]{"结果分类", "次数", "说明"}, List.of(
                new String[]{"成功进入队列", String.valueOf(ok), "期望 1"},
                new String[]{"触发限购(每人限购)", String.valueOf(overLimit), "期望 = 通过限流的次数 - 1"},
                new String[]{"限流(429)", String.valueOf(rateLimited), "服务端限流为每人 1 秒 5 次，并发 " + times + " 次必然有一部分被拦"},
                new String[]{"已被抢光", String.valueOf(soldOut), "库存应充足，正常为 0"},
                new String[]{"其他", String.valueOf(other), ""}
        ));
        System.out.println();
        printTable(new String[]{"服务端返回（原文）", "次数"}, new ArrayList<>(messages.entrySet().stream()
                .map(e -> new String[]{e.getKey(), String.valueOf(e.getValue())}).toList()));

        System.out.println();
        verdict(ok == 1, "同一用户并发 " + times + " 次，只成功 1 次（ok=" + ok + "）");
        verdict(rateLimited + overLimit == times - ok, "其余 " + (rateLimited + overLimit) + " 次全部被拦截（限购 " + overLimit + " + 限流 " + rateLimited + "）");
        if (rateLimited > 0) {
            note("注意：服务端对抢购接口的限流是「每人 1 秒 5 次」，所以并发 " + times
                    + " 次时有 " + rateLimited + " 次在进入库存/限购校验之前就被 429 拦掉了；");
            note("      真正走到 Lua 限购校验的是 " + (ok + overLimit) + " 次，其中 1 次成功、" + overLimit + " 次被\"每人限购 1 件\"挡下。");
        }

        // 补充验证：按 1.2 秒间隔发 5 次（绕开 1 秒限流窗口），观察"一人一单"本身
        System.out.println();
        note("补充验证：按 1.2 秒间隔再发 5 次（避开 1 秒限流窗口），看\"一人一单\"本身是否生效");
        sleepMs(1200); // 先让上面那波并发彻底滑出 1 秒窗口，否则第 1 次匀速请求仍会被限流
        int ok2 = 0, overLimit2 = 0, rateLimited2 = 0, other2 = 0;
        Map<String, Integer> msg2 = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            Api a = sendSeckill(token);
            switch (kindOf(a)) {
                case OK -> ok2++;
                case OVER_LIMIT -> overLimit2++;
                case RATE_LIMITED -> rateLimited2++;
                default -> other2++;
            }
            msg2.merge(a.message.isEmpty() ? a.describe() : a.message, 1, Integer::sum);
            sleepMs(1200);
        }
        printTable(new String[]{"匀速 5 次的结果", "次数"}, List.of(
                new String[]{"成功", String.valueOf(ok2)},
                new String[]{"限购拦截", String.valueOf(overLimit2)},
                new String[]{"限流(429)", String.valueOf(rateLimited2)},
                new String[]{"其他", String.valueOf(other2)}
        ));
        printTable(new String[]{"服务端 message（原文）", "次数"}, new ArrayList<>(msg2.entrySet().stream()
                .map(e -> new String[]{e.getKey(), String.valueOf(e.getValue())}).toList()));
        verdict(ok2 == 0 && rateLimited2 == 0, "匀速请求全部通过限流、且全部被一人一单规则拦下（成功 " + ok2 + " 次）");
    }

    // ==================================================================
    // 实验 4：throughput —— 闭环压测（async vs sync）
    // ==================================================================
    static void expThroughput() {
        section("实验 4：闭环压测（throughput）");
        if (!"async".equals(mode) && !"sync".equals(mode)) {
            System.out.println("  [错误] --mode 只能是 async 或 sync，当前: " + mode);
            return;
        }
        if (!stockSet) stock = 1000000;
        if (concurrency <= 0 || requests <= 0) {
            System.out.println("  [错误] --concurrency 与 --requests 必须为正数");
            return;
        }
        String token = benchToken();
        if (token == null) return;

        String path = "/api/bench/seckill-" + mode + "/" + activity;
        kv("模式", mode + ("async".equals(mode) ? "（Lua 扣减 + MQ 异步下单）" : "（同步扣库存 + 同步写订单）"));
        kv("压测地址", "POST " + path);
        kv("并发工作线程", concurrency + "（闭环：每线程串行发 " + (requests / Math.max(1, concurrency)) + " 次左右）");
        kv("总请求数", requests);
        kv("重置库存", stock + "（两种模式条件一致，clearOrders=true）");
        System.out.println();
        resetActivity(token, activity, stock, true);

        final long synthBase = 800_000_000L;
        int warmup = Math.min(100, Math.max(20, concurrency));

        // 预热：把连接、JIT、缓存都捂热，结果才稳定（不计入统计）
        System.out.println();
        note("预热 " + warmup + " 次请求（不计入统计）");
        for (int i = 0; i < warmup; i++) {
            call("POST", path + "?syntheticUserId=" + (synthBase + 900_000_000L + i), token, null);
        }

        AtomicInteger next = new AtomicInteger(0);
        AtomicLong okCount = new AtomicLong();
        AtomicLong soldOutCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();
        AtomicLong otherCount = new AtomicLong();
        Map<String, Integer> otherMessages = Collections.synchronizedMap(new LinkedHashMap<>());
        long[][] latencyBuckets = new long[concurrency][];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        ExecutorService pool = newExecutor();
        long wallStart;

        System.out.println();
        for (int w = 0; w < concurrency; w++) {
            final int wid = w;
            pool.submit(() -> {
                List<Long> local = new ArrayList<>();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                int idx;
                while ((idx = next.getAndIncrement()) < requests) {
                    HttpResult r = call("POST", path + "?syntheticUserId=" + (synthBase + idx), token, null);
                    local.add(r.micros);
                    Api a = api(r);
                    switch (kindOf(a)) {
                        case OK -> okCount.incrementAndGet();
                        case SOLD_OUT -> soldOutCount.incrementAndGet();
                        case ERROR -> errorCount.incrementAndGet();
                        default -> {
                            otherCount.incrementAndGet();
                            otherMessages.merge(a.describe(), 1, Integer::sum);
                        }
                    }
                }
                long[] arr = new long[local.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = local.get(i);
                latencyBuckets[wid] = arr;
                done.countDown();
            });
        }
        wallStart = System.nanoTime();
        start.countDown();
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        double wallSeconds = (System.nanoTime() - wallStart) / 1e9;
        pool.shutdown();

        int total = 0;
        for (long[] b : latencyBuckets) total += b == null ? 0 : b.length;
        long[] all = new long[total];
        int p = 0;
        for (long[] b : latencyBuckets) {
            if (b == null) continue;
            System.arraycopy(b, 0, all, p, b.length);
            p += b.length;
        }
        Arrays.sort(all);

        long okFinal = okCount.get();
        System.out.println();
        printTable(new String[]{"指标", "值", "说明"}, List.of(
                new String[]{"总请求数", String.valueOf(total), "闭环发出的请求数"},
                new String[]{"成功(code=0)", String.valueOf(okFinal), ""},
                new String[]{"已被抢光", String.valueOf(soldOutCount.get()), "库存给足，正常应为 0"},
                new String[]{"连接/请求失败", String.valueOf(errorCount.get()), ""},
                new String[]{"其他", String.valueOf(otherCount.get()), otherMessages.isEmpty() ? "" : otherMessages.toString()},
                new String[]{"成功率", String.format(Locale.ROOT, "%.2f%%", 100.0 * okFinal / Math.max(1, total)), ""},
                new String[]{"墙钟耗时", String.format(Locale.ROOT, "%.3f s", wallSeconds), ""},
                new String[]{"吞吐(全部请求)", String.format(Locale.ROOT, "%.0f req/s", total / Math.max(0.0001, wallSeconds)), ""},
                new String[]{"吞吐(仅成功)", String.format(Locale.ROOT, "%.0f req/s", okFinal / Math.max(0.0001, wallSeconds)), ""}
        ));

        if (all.length > 0) {
            double sum = 0;
            for (long x : all) sum += x;
            System.out.println();
            printTable(new String[]{"延迟指标", "微秒", "毫秒"}, List.of(
                    new String[]{"平均", String.format(Locale.ROOT, "%.0f", sum / all.length), ms(sum / all.length)},
                    new String[]{"P50", String.valueOf(percentile(all, 50)), ms(percentile(all, 50))},
                    new String[]{"P90", String.valueOf(percentile(all, 90)), ms(percentile(all, 90))},
                    new String[]{"P95", String.valueOf(percentile(all, 95)), ms(percentile(all, 95))},
                    new String[]{"P99", String.valueOf(percentile(all, 99)), ms(percentile(all, 99))},
                    new String[]{"最小", String.valueOf(all[0]), ms(all[0])},
                    new String[]{"最大", String.valueOf(all[all.length - 1]), ms(all[all.length - 1])}
            ));
        } else {
            System.out.println("  延迟统计: 未获取到（没有任何请求完成）");
        }

        System.out.println();
        note("接口返回即「抢到名额」，订单由 MQ 消费者异步落库，这里等 orderCount 收敛后再看是否超卖");
        long t0 = System.nanoTime();
        Map<String, Object> st = null;
        while ((System.nanoTime() - t0) / 1_000_000 < 30_000) {
            st = benchStats(token, activity);
            if (st != null && lng(st.get("orderCount")) >= okFinal) break;
            sleepMs(300);
        }
        printStats(st, "压测后活动 " + activity);
        if (st != null) {
            long orderCount = lng(st.get("orderCount"));
            verdict(orderCount <= lng(st.get("totalStock")), "orderCount(" + orderCount + ") 未超过 totalStock(" + lng(st.get("totalStock")) + ")");
            verdict(lng(st.get("dbAvailableStock")) >= 0 && lng(st.get("redisAvailableStock")) >= 0, "库存未出现负数");
        }
    }

    static String ms(double micros) {
        return String.format(Locale.ROOT, "%.2f ms", micros / 1000.0);
    }

    static long percentile(long[] sorted, int pct) {
        if (sorted.length == 0) return -1;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    // ==================================================================
    // 实验 5：cache-miss —— 缓存击穿
    // ==================================================================
    static void expCacheMiss() {
        section("实验 5：缓存击穿（cache-miss）");
        if (!stockSet) stock = 9999;
        String token = benchToken();
        if (token == null) return;
        String key = "activity:detail:" + activity;

        kv("活动", activity);
        kv("并发数", concurrency);
        kv("缓存 key", key);
        System.out.println();

        // ① 先读一次，让缓存生效
        Api warm = get_("/api/activity/" + activity, token);
        kv("预热读详情", warm.ok() ? "HTTP 200 code=0（缓存已写入）" : warm.describe());
        if (!warm.ok()) return;

        // ② reset：服务端会同时删掉详情缓存并把该 key 的回源计数清零
        //    （工具拿不到 Redis 连接，所以借 reset 接口完成"删缓存 + 清零计数"）
        resetActivity(token, activity, stock, false);
        long afterReset = cacheLoadCount(token, key);
        kv("reset 后回源计数", afterReset < 0 ? "未获取到" : String.valueOf(afterReset));

        // ③ 并发打详情
        List<Supplier<Api>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) tasks.add(() -> get_("/api/activity/" + activity, token));
        Api[] results = burst(tasks);

        int ok = 0, other = 0;
        Map<String, Integer> others = new LinkedHashMap<>();
        for (Api a : results) {
            if (a.ok()) ok++;
            else {
                other++;
                others.merge(a.describe(), 1, Integer::sum);
            }
        }
        System.out.println();
        kv("并发请求耗时", String.format(Locale.ROOT, "%.3f s", lastBurstMicros / 1_000_000.0));
        printTable(new String[]{"并发请求结果", "次数"}, List.of(
                new String[]{"HTTP 200 code=0（拿到详情）", String.valueOf(ok)},
                new String[]{"其他", String.valueOf(other) + (others.isEmpty() ? "" : " " + others)}
        ));

        // ④ 读回源次数
        long loads = cacheLoadCount(token, key);
        System.out.println();
        printTable(new String[]{"指标", "值", "说明"}, List.of(
                new String[]{"并发请求数", String.valueOf(concurrency), "缓存被删后的瞬时并发"},
                new String[]{"缓存回源次数", loads < 0 ? "未获取到" : String.valueOf(loads), "期望远小于 " + concurrency},
                new String[]{"数据库压力下降", loads > 0 ? String.format(Locale.ROOT, "%.1f 倍", (double) concurrency / loads) : "未获取到", ""}
        ));
        if (loads >= 0) {
            verdict(loads < concurrency, "回源 " + loads + " 次 << 并发 " + concurrency + " 次：SETNX 互斥把击穿流量挡住了");
        }
    }

    // ==================================================================
    // 实验 6：cache-penetrate —— 缓存穿透
    // ==================================================================
    static void expCachePenetrate() {
        section("实验 6：缓存穿透（cache-penetrate）");
        if (!timesSet) times = 200;
        String token = benchToken();
        if (token == null) return;
        String key = "activity:detail:" + activity;
        kv("查询的活动 id", activity + "（不存在的活动）");
        kv("缓存 key", key);
        kv("查询次数", times);
        System.out.println();

        resetCacheLoadCount(token, key);

        // 空值标记（__NULL__）有 30 秒 TTL。如果上一次实验刚留下标记，这次的回源计数会是 0。
        // 为了真实复现"第一次查询回源 1 次"，先探测一次；若发现标记还在，就等它过期再来。
        long loads = cacheLoadCount(token, key);
        int attempts = 0;
        while (attempts < 2) {
            attempts++;
            Api probe = get_("/api/activity/" + activity, token);
            loads = cacheLoadCount(token, key);
            kv("探测第 " + attempts + " 次", "返回 " + probe.describe() + "；回源计数=" + loads);
            if (loads > 0) break;
            if (noWait) {
                note("检测到 Redis 里已存在空值标记（__NULL__，TTL 30 秒），说明这条不存在的活动已被缓存住；"
                        + "--no-wait 已开启，不再等待其过期");
                break;
            }
            note("检测到 Redis 里已存在空值标记（__NULL__，TTL 30 秒），等待 " + waitTtlSeconds + " 秒后重来，"
                    + "以便观察\"第一次查询会回源 1 次\"");
            sleepMs(waitTtlSeconds * 1000L);
            resetCacheLoadCount(token, key);
        }

        // 并发（栅栏同时发起）查询不存在的活动
        List<Supplier<Api>> tasks = new ArrayList<>();
        for (int i = 0; i < times; i++) tasks.add(() -> get_("/api/activity/" + activity, token));
        Api[] results = burst(tasks);

        int notFound = 0, okUnexpected = 0, other = 0;
        Map<String, Integer> messages = new LinkedHashMap<>();
        for (Api a : results) {
            if (a.httpStatus == 200 && a.code != 0) {
                notFound++;
                messages.merge(a.message.isEmpty() ? a.describe() : a.message, 1, Integer::sum);
            } else if (a.ok()) {
                okUnexpected++;
            } else {
                other++;
                messages.merge(a.describe(), 1, Integer::sum);
            }
        }
        long finalLoads = cacheLoadCount(token, key);

        System.out.println();
        kv("并发查询耗时", String.format(Locale.ROOT, "%.3f s", lastBurstMicros / 1_000_000.0));
        printTable(new String[]{"结果", "次数", "说明"}, List.of(
                new String[]{"HTTP 200 + code!=0（活动不存在）", String.valueOf(notFound), "业务上就是查不到"},
                new String[]{"意外成功", String.valueOf(okUnexpected), "活动 " + activity + " 本不该存在"},
                new String[]{"其他", String.valueOf(other), other == 0 ? "" : messages.toString()}
        ));
        printTable(new String[]{"服务端 message（原文）", "次数"}, new ArrayList<>(messages.entrySet().stream()
                .map(e -> new String[]{e.getKey(), String.valueOf(e.getValue())}).toList()));
        System.out.println();
        printTable(new String[]{"指标", "值", "说明"}, List.of(
                new String[]{"查询次数", String.valueOf(times), ""},
                new String[]{"累计回源次数", finalLoads < 0 ? "未获取到" : String.valueOf(finalLoads), "期望 1：只回源一次就写空值标记"},
                new String[]{"空值缓存 TTL", "30 秒（服务端配置）", "TTL 内重复查询直接命中 __NULL__"}
        ));
        if (finalLoads >= 0) {
            verdict(finalLoads <= 1, "累计回源 " + finalLoads + " 次（期望 ≤1）：不存在的 id 没有反复打到数据库");
            if (finalLoads == 0) {
                note("本次回源 0 次是因为查询前 Redis 里已经有 __NULL__ 空值标记（TTL 30 秒）——同样是穿透防护生效的表现。");
            }
        }
    }

    // ==================================================================
    // 实验 7：ratelimit —— 滑动窗口限流
    // ==================================================================
    static void expRateLimit() {
        section("实验 7：限流（ratelimit）");
        String token = benchToken();
        if (token == null) return;
        kv("活动", activity);
        kv("用户", phone);
        kv("服务端规则", "抢购接口每人 1 秒最多 5 次（滑动窗口），超出返回 HTTP 429");
        System.out.println();
        resetActivity(token, activity, stock, true);

        // ① 1 秒内并发 10 次
        System.out.println();
        note("第 1 阶段：同一用户 1 秒内并发 10 次抢购");
        List<Supplier<Api>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) tasks.add(() -> sendSeckill(token));
        Api[] results = burst(tasks);

        int http200 = 0, http429 = 0, otherStatus = 0;
        int ok = 0, overLimit = 0, soldOut = 0;
        Map<String, Integer> messages = new LinkedHashMap<>();
        for (Api a : results) {
            if (a.httpStatus == 200) http200++;
            else if (a.httpStatus == 429) http429++;
            else otherStatus++;
            switch (kindOf(a)) {
                case OK -> ok++;
                case OVER_LIMIT -> overLimit++;
                case SOLD_OUT -> soldOut++;
                default -> { /* 429 / 其他已在上面统计 */ }
            }
            messages.merge("HTTP " + a.httpStatus + " " + (a.message.isEmpty() ? "(无 message)" : a.message), 1, Integer::sum);
        }
        kv("并发耗时", String.format(Locale.ROOT, "%.3f s", lastBurstMicros / 1_000_000.0));
        printTable(new String[]{"HTTP 状态", "次数"}, List.of(
                new String[]{"200", String.valueOf(http200)},
                new String[]{"429", String.valueOf(http429)},
                new String[]{"其他", String.valueOf(otherStatus)}
        ));
        printTable(new String[]{"业务结果", "次数", "说明"}, List.of(
                new String[]{"成功进入队列", String.valueOf(ok), "库存充足时窗口内只放行 1 次成功"},
                new String[]{"触发限购", String.valueOf(overLimit), "通过限流但已被一人一单拦下"},
                new String[]{"已被抢光", String.valueOf(soldOut), "正常为 0"}
        ));
        printTable(new String[]{"服务端返回（原文）", "次数"}, new ArrayList<>(messages.entrySet().stream()
                .map(e -> new String[]{e.getKey(), String.valueOf(e.getValue())}).toList()));
        verdict(http429 > 0, "突发流量被限流拦截：" + http429 + " 次 429 / 10 次并发");
        verdict(http200 == 5, "窗口内放行 " + http200 + " 次（服务端配置 maxRequests=5）");

        // ② 每 1.2 秒 1 次，共 5 次
        System.out.println();
        note("第 2 阶段：按每 1.2 秒 1 次的节奏请求 5 次（错开 1 秒窗口）");
        sleepMs(1200); // 让第 1 阶段的突发流量滑出滑动窗口，第 1 次匀速请求才不会被上一波带累
        int passed = 0, limited429 = 0;
        List<String[]> rows = new ArrayList<>();
        Map<String, Integer> messages2 = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            long t0 = System.nanoTime();
            Api a = sendSeckill(token);
            long cost = (System.nanoTime() - t0) / 1_000_000;
            boolean rateLimited = a.httpStatus == 429 || a.code == 429;
            if (rateLimited) limited429++;
            else passed++;
            rows.add(new String[]{String.valueOf(i), "HTTP " + a.httpStatus, "code=" + a.code, a.message,
                    (rateLimited ? "被限流" : "通过限流"), cost + " ms"});
            messages2.merge(a.message.isEmpty() ? a.describe() : a.message, 1, Integer::sum);
            if (i < 5) sleepMs(1200);
        }
        printTable(new String[]{"第几次", "HTTP", "code", "message", "限流判定", "耗时"}, rows);
        System.out.println();
        verdict(passed == 5, "匀速请求 " + passed + "/5 通过限流（429=" + limited429 + "）：滑动窗口只拦突发，不拦匀速");
        note("通过限流后业务返回什么由库存/限购决定：" + messages2);
    }

    // ==================================================================
    // 实验 8：timeout —— 超时关单 + 库存回滚
    // ==================================================================
    static void expTimeout() {
        section("实验 8：超时关单与库存回滚（timeout）");
        String token = benchToken();
        if (token == null) return;
        kv("活动", activity);
        kv("买家", phone + "（真实 /api/seckill 链路）");
        kv("重置库存", stock);
        System.out.println();
        resetActivity(token, activity, stock, true);

        Map<String, Object> before = benchStats(token, activity);

        Api sk = sendSeckill(token);
        if (!sk.ok()) {
            System.out.println("  [错误] 抢购失败: " + sk.describe());
            return;
        }
        String orderNo = str(get(sk.data, "orderNo"));
        kv("抢购返回", "orderNo=" + orderNo + ", state=" + str(get(sk.data, "state")));

        // 轮询等订单落库（MQ 消费者异步写单）
        String state = pollState(token, orderNo, 15000, s -> !"QUEUING".equals(s));
        kv("订单落库后状态", state);
        Map<String, Object> afterSeckill = benchStats(token, activity);

        System.out.println();
        note("不支付，直接调 force-timeout 投递关单消息（延迟 1 秒）");
        Api ft = post("/api/bench/force-timeout/" + orderNo, token);
        kv("force-timeout 返回", ft.ok() ? str(get(ft.data, "message")) : ft.describe());
        sleepMs(3000);

        String finalState = pollState(token, orderNo, 20000, s -> "TIMEOUT".equals(s) || "PAID".equals(s) || "CANCELLED".equals(s));
        Map<String, Object> afterTimeout = benchStats(token, activity);

        System.out.println();
        printTable(new String[]{"订单号", "关单前状态", "关单后状态"}, List.<String[]>of(
                new String[]{orderNo, state, finalState}
        ));
        List<String[]> rows = new ArrayList<>();
        rows.add(stockRow("totalStock", before, afterSeckill, afterTimeout));
        rows.add(stockRow("dbAvailableStock", before, afterSeckill, afterTimeout));
        rows.add(stockRow("redisAvailableStock", before, afterSeckill, afterTimeout));
        rows.add(new String[]{"orderCount", str(before == null ? "未获取到" : before.get("orderCount")),
                str(afterSeckill == null ? "未获取到" : afterSeckill.get("orderCount")),
                str(afterTimeout == null ? "未获取到" : afterTimeout.get("orderCount"))});
        rows.add(new String[]{"ordersByStatus", fmtMap(before == null ? null : before.get("ordersByStatus")),
                fmtMap(afterSeckill == null ? null : afterSeckill.get("ordersByStatus")),
                fmtMap(afterTimeout == null ? null : afterTimeout.get("ordersByStatus"))});
        printTable(new String[]{"指标", "reset 后", "抢购落库后", "关单后"}, rows);

        System.out.println();
        verdict("TIMEOUT".equals(finalState), "订单最终状态为 " + finalState + "（期望 TIMEOUT）");
        if (afterSeckill != null && afterTimeout != null) {
            long redisDelta = lng(afterTimeout.get("redisAvailableStock")) - lng(afterSeckill.get("redisAvailableStock"));
            long dbDelta = lng(afterTimeout.get("dbAvailableStock")) - lng(afterSeckill.get("dbAvailableStock"));
            verdict(redisDelta == 1 && dbDelta == 1, "关单后库存回滚：Redis +" + redisDelta + "、DB +" + dbDelta + "（各期望 +1）");
        } else {
            System.out.println("  [FAIL] 未获取到 stats，无法判断库存是否回滚");
        }

        // 补充：关单只回滚库存、不回滚"已购记录"，所以这个人再抢会被限购拦下
        System.out.println();
        note("补充验证：关单后同一用户再抢一次（服务端设计上只回滚库存、不回滚已购记录）");
        Api again = sendSeckill(token);
        kv("再次抢购结果", again.describe());
        verdict(kindOf(again) == Kind.OVER_LIMIT, "再次抢购被\"" + again.message + "\"拦下：库存退回了池子，但该用户的名额已经用掉");
    }

    static String[] stockRow(String key, Map<String, Object> a, Map<String, Object> b, Map<String, Object> c) {
        return new String[]{key, str(a == null ? "未获取到" : a.get(key)), str(b == null ? "未获取到" : b.get(key)),
                str(c == null ? "未获取到" : c.get(key))};
    }

    /** 轮询抢购结果，直到满足条件或超时；返回最后一次拿到的 state */
    static String pollState(String token, String orderNo, long timeoutMs, java.util.function.Predicate<String> done) {
        long t0 = System.nanoTime();
        String state = "未获取到";
        int polls = 0;
        while ((System.nanoTime() - t0) / 1_000_000 < timeoutMs) {
            Api a = get_("/api/seckill/result/" + orderNo, token);
            polls++;
            if (a.ok()) {
                state = str(get(a.data, "state"));
                if (done.test(state)) break;
            } else if (a.code != 0) {
                state = "查询失败: " + a.describe();
                break;
            }
            sleepMs(200);
        }
        kv("轮询", polls + " 次，用时 " + ((System.nanoTime() - t0) / 1_000_000) + " ms，state=" + state);
        return state;
    }

    // ==================================================================
    // 实验 9：pay —— 支付链路 + 幂等
    // ==================================================================
    static void expPay() {
        section("实验 9：支付链路与幂等（pay）");
        String token = benchToken();
        if (token == null) return;
        kv("活动", activity);
        kv("买家", phone);
        kv("重置库存", stock);
        System.out.println();
        resetActivity(token, activity, stock, true);

        Api sk = sendSeckill(token);
        if (!sk.ok()) {
            System.out.println("  [错误] 抢购失败: " + sk.describe());
            return;
        }
        String orderNo = str(get(sk.data, "orderNo"));
        kv("抢购返回", "orderNo=" + orderNo + ", state=" + str(get(sk.data, "state")));

        String state = pollState(token, orderNo, 15000, s -> "PENDING_PAY".equals(s) || "PAID".equals(s));
        kv("订单落库后状态", state);
        if (!"PENDING_PAY".equals(state)) {
            note("订单状态不是 PENDING_PAY，后面支付流程可能不符合预期，继续如实记录");
        }
        Map<String, Object> afterSeckill = benchStats(token, activity);

        Api pay1 = post("/api/order/" + orderNo + "/pay", token);
        Api pay2 = post("/api/order/" + orderNo + "/pay", token);
        String finalState = pollState(token, orderNo, 5000, s -> "PAID".equals(s));
        Map<String, Object> afterPay = benchStats(token, activity);

        System.out.println();
        printTable(new String[]{"步骤", "HTTP", "code", "message", "data.status"}, List.of(
                new String[]{"第 1 次支付", String.valueOf(pay1.httpStatus), String.valueOf(pay1.code), pay1.message, str(get(pay1.data, "status"))},
                new String[]{"第 2 次支付", String.valueOf(pay2.httpStatus), String.valueOf(pay2.code), pay2.message, str(get(pay2.data, "status"))}
        ));
        printTable(new String[]{"订单最终状态", finalState, "（期望 PAID）"}, List.of());
        printTable(new String[]{"指标", "抢购后", "支付后"}, List.of(
                new String[]{"orderCount", str(afterSeckill == null ? "未获取到" : afterSeckill.get("orderCount")),
                        str(afterPay == null ? "未获取到" : afterPay.get("orderCount"))},
                new String[]{"dbAvailableStock", str(afterSeckill == null ? "未获取到" : afterSeckill.get("dbAvailableStock")),
                        str(afterPay == null ? "未获取到" : afterPay.get("dbAvailableStock"))},
                new String[]{"redisAvailableStock", str(afterSeckill == null ? "未获取到" : afterSeckill.get("redisAvailableStock")),
                        str(afterPay == null ? "未获取到" : afterPay.get("redisAvailableStock"))},
                new String[]{"ordersByStatus", fmtMap(afterSeckill == null ? null : afterSeckill.get("ordersByStatus")),
                        fmtMap(afterPay == null ? null : afterPay.get("ordersByStatus"))}
        ));

        System.out.println();
        verdict(pay1.ok() && "PAID".equals(str(get(pay1.data, "status"))), "第一次支付成功：" + pay1.message);
        verdict(pay2.code == 0 && "PAID".equals(str(get(pay2.data, "status"))), "第二次支付幂等返回：" + pay2.message);
        verdict("PAID".equals(finalState), "订单最终状态 " + finalState + "（支付后不会被超时关单回滚库存）");
    }
}
