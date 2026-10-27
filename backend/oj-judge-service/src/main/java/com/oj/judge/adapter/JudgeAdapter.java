package com.oj.judge.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * go-judge 协议适配层 —— <b>全项目唯一做「MB ↔ 字节 ↔ KB」换算与沙箱协议封装的类</b>。
 *
 * <h2>一、为什么必须唯一</h2>
 * <p>基线决策 §5.5 与契约 §4.7 都把这条写成了硬约定：题目内存限制 {@code memoryLimitMb}（MB）、
 * 判题实测内存 {@code memoryUsedKb}（KB）、go-judge 的 {@code memoryLimit}（字节）。
 * 三个单位在 5 个服务、前端、DB 之间来回流动，只要允许第二处换算，
 * 就一定会出现「51200 KB 被当成 51200 字节 → 所有题 MLE」这类事故。
 * 因此本类的换算方法 {@code *} 全部 {@code public static}，其他类直接调用，禁止自己乘除。</p>
 *
 * <h2>二、go-judge v1.13 实测协议（与官方文档不一致，见 scripts/_probe_gojudge*.py）</h2>
 * <table border="1">
 *   <caption>七条实测事实</caption>
 *   <tr><th>#</th><th>事实</th><th>本类如何应对</th></tr>
 *   <tr><td>1</td><td>一次 {@code /run} 里多个 {@code cmd} <b>各自独立环境，文件系统不共享</b></td>
 *       <td>编译与运行拆成<b>两次</b> {@code /run}（{@link #compile} / {@link #runCases}）</td></tr>
 *   <tr><td>2</td><td>{@code workdir=/w}；{@code /} 只读，{@code /tmp} 可写；{@code /w} 是
 *       <b>128 MB tmpfs</b></td><td>源码与产物一律写 {@code /w}，并控制体积</td></tr>
 *   <tr><td>3</td><td>{@code files} 是<b>位置语义</b>：第 0 项 = stdin，第 1 项 = stdout，第 2 项 = stderr；
 *       {@code name} 只决定响应里的键名</td><td>{@link #buildStdioFiles} 固定顺序，绝不写反</td></tr>
 *   <tr><td>4</td><td>响应<b>单对象</b>（1 cmd）或<b>数组</b>（N cmd）</td>
 *       <td>{@link #parseRunResults} 两种都吃</td></tr>
 *   <tr><td>5</td><td>{@code copyOut} 可用，返回 {@code {文件名: 内容}}</td>
 *       <td>取编译产物用 {@code copyOut:["art.b64"]}</td></tr>
 *   <tr><td>6</td><td>{@code copyIn} 是 <b>map</b> {@code {"dst":{"content":"..."}}}；
 *       <b>by fileId 不可用（404）</b></td><td>编译产物走 base64 文本往返，不走 fileId</td></tr>
 *   <tr><td>7</td><td>{@code status} 是字符串，取值有
 *       {@code Accepted} / {@code Nonzero Exit Status} / {@code Time Limit Exceeded} /
 *       {@code Memory Limit Exceeded} / {@code Output Limit Exceeded} / {@code Signalled} /
 *       {@code File Error} / {@code Internal Error}</td>
 *       <td>{@link #mapRunStatus} 用 {@code contains} 判定，不用 {@code equals}</td></tr>
 * </table>
 *
 * <h2>三、两阶段调用链</h2>
 * <pre>
 * 阶段 1 · 编译（1 个 cmd，{@link #compile}）
 *   copyIn  : {"/w/Main.cpp": {"content": 源码}}
 *   args    : /bin/sh -c "mkdir -p /w/bin &amp;&amp; cd /w &amp;&amp; g++ ... -o /w/bin/m /w/Main.cpp
 *                          &amp;&amp; tar czf /w/art.tgz -C /w/bin . &amp;&amp; base64 -w0 /w/art.tgz > /w/art.b64"
 *   copyOut : ["art.b64"]
 *   → exitStatus != 0 或 status != "Accepted" ⇒ CE，errorMsg = stderr
 *   → 否则把 art.b64 的文本带进阶段 2
 *
 * 阶段 2 · 跑测试点（N 个 cmd 并行，{@link #runCases}）
 *   每个 cmd:
 *     copyIn : {"/w/art.b64": {"content": 阶段1 的 base64 文本}}
 *     files  : [{"src": "/data/testcases/{pid}/{seq}.in"},   ← stdin（直挂挂载文件，零传输）
 *               {"name":"stdout","max":...}, {"name":"stderr","max":...}]
 *     args   : /bin/sh -c "cd /w &amp;&amp; base64 -d art.b64 > art.tgz &amp;&amp; tar xzf art.tgz &amp;&amp; ./m"
 *     cpuLimit/clockLimit/memoryLimit/stackLimit/procLimit  ← <b>必须写在 cmd 内部</b>
 * </pre>
 *
 * <p><b>为什么 stdin 用 {@code {"src": ...}} 而不是 {@code {"content": ...}}：</b>
 * 实测 {@code files[0].src} 可以直接指向 go-judge 容器内可见的挂载文件，
 * 205 MB 的输入耗时 0.0s；而 {@code copyIn} 走 {@code /w} 会撞 128 MB tmpfs 上限
 * （{@code write /w/big.in: no space left on device}）。本机 4837 个输入里有 4 个 &gt;100 MB、
 * 156 个 &gt;10 MB，所以这条不是优化而是<b>正确性</b>。</p>
 *
 * <h2>四、语言模板</h2>
 * <pre>
 * cpp    : g++ -O2 -std=c++17 -o /w/bin/m /w/Main.cpp          → ./m
 * java   : javac -encoding UTF-8 -d /w/bin /w/Main.java         → java -cp /w Main
 * python : python3 -m py_compile /w/main.py                     → python3 /w/main.py
 * </pre>
 *
 * @author oj-cloud
 */
public final class JudgeAdapter {

    /** go-judge 响应里 {@code status} 的精确取值（实测，v1.13.0） */
    public static final String GJ_ACCEPTED = "Accepted";
    public static final String GJ_NONZERO_EXIT = "Nonzero Exit Status";
    public static final String GJ_TLE = "Time Limit Exceeded";
    public static final String GJ_MLE = "Memory Limit Exceeded";
    public static final String GJ_OLE = "Output Limit Exceeded";
    public static final String GJ_SIGNALLED = "Signalled";
    public static final String GJ_FILE_ERROR = "File Error";
    public static final String GJ_INTERNAL_ERROR = "Internal Error";

    /** 字节与 KB 的换算基数（二进制，1 KB = 1024 B） */
    private static final int BYTES_PER_KB = 1024;

    /** 字节与 MB 的换算基数（二进制，1 MB = 1024 KB = 1048576 B） */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** 沙箱固定环境变量：不给 PATH 时 g++/javac 都找不到 */
    private static final List<String> SANDBOX_ENV = List.of(
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8");

    /** 沙箱内源码/产物目录（{@code /w} 是 128 MB tmpfs，{@code /w/bin} 放编译产物） */
    private static final String SANDBOX_WORK = "/w";
    private static final String SANDBOX_BIN = "/w/bin";

    /** 编译产物打包后的文件名（base64 文本） */
    private static final String ARTIFACT_B64 = "art.b64";

    /** 编译阶段给足的固定额度：编译比运行重得多，且与题目时限无关 */
    private static final long COMPILE_CPU_LIMIT_NS = 20_000_000_000L;    // 20s
    private static final long COMPILE_CLOCK_LIMIT_NS = 120_000_000_000L;  // 120s
    private static final long COMPILE_MEMORY_LIMIT_BYTES = 1024L * 1024L * 1024L; // 1 GB

    /** 栈上限：与探针脚本一致（256 MB） */
    private static final long STACK_LIMIT_BYTES = 268_435_456L;

    /** 进程数上限：JVM 需要更多线程 */
    private static final int PROC_LIMIT = 64;

    private JudgeAdapter() {
    }

    // ==================================================================
    // 一、单位换算（全项目唯一入口，禁止在别处乘除 1024）
    // ==================================================================

    /**
     * MB → 字节。{@code 512 MB → 536870912 B}。
     *
     * @param mb 兆字节（题目 {@code memoryLimitMb}）
     * @return 字节（go-judge {@code memoryLimit}）
     */
    public static long mbToBytes(long mb) {
        return mb * BYTES_PER_MB;
    }

    /**
     * KB → 字节。{@code 51200 KB → 52428800 B}。
     *
     * <p>沙箱运维页的 {@code mem_limit_kb} 是 KB，转 go-judge 时走这里。</p>
     *
     * @param kb 千字节
     * @return 字节
     */
    public static long kbToBytes(long kb) {
        return kb * BYTES_PER_KB;
    }

    /**
     * 字节 → KB（四舍五入）。go-judge 的 {@code memory}（字节）→
     * {@code judge_submission.memory_used} / {@code memoryUsedKb}。
     *
     * <p><b>必须四舍五入而不是截断：</b>实测一个 C++ 程序占用 1_103_104 B，
     * 截断得 1077 KB、四舍五入得 1078 KB；契约要求「51200 KB ↔ 268435456 B」
     * 这对值双向可逆，只有四舍五入才能保证 {@code bytesToKb(mbToBytes(512)) == 524288}
     * 且 {@code bytesToKb(268435456) == 262144} 这类整值不被磨掉 1 KB。</p>
     *
     * @param bytes 字节
     * @return KB（≥0）
     */
    public static int bytesToKb(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        return (int) Math.round(bytes / (double) BYTES_PER_KB);
    }

    /**
     * 字节 → MB（四舍五入，仅用于展示与沙箱运维页）。
     *
     * @param bytes 字节
     * @return MB
     */
    public static int bytesToMb(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        return (int) Math.round(bytes / (double) BYTES_PER_MB);
    }

    /**
     * 契约点名的换算用例：{@code 51200 KB ↔ 268435456 B}。
     *
     * <p>{@code 268435456 B = 256 MB = 262144 KB}；{@code 51200 KB = 50 MB = 52428800 B}。
     * 两个方向都必须可逆（四舍五入）。这里显式写成断言方法，供 {@code main} 与单测调用。</p>
     *
     * @throws IllegalStateException 换算不成立
     */
    public static void assertUnitConversionContract() {
        // 方向一：51200 KB → 52428800 B → 51200 KB（四舍五入可逆）
        long bytesOf50Mb = kbToBytes(51200L);
        if (bytesOf50Mb != 52_428_800L) {
            throw new IllegalStateException("kbToBytes(51200) 应为 52428800，实际 " + bytesOf50Mb);
        }
        if (bytesToKb(bytesOf50Mb) != 51_200) {
            throw new IllegalStateException("bytesToKb(52428800) 应为 51200，实际 " + bytesToKb(bytesOf50Mb));
        }

        // 方向二：268435456 B → 262144 KB（= 256 MB），且 256 MB → 268435456 B
        long bytesOf256Mb = mbToBytes(256L);
        if (bytesOf256Mb != 268_435_456L) {
            throw new IllegalStateException("mbToBytes(256) 应为 268435456，实际 " + bytesOf256Mb);
        }
        if (bytesToKb(bytesOf256Mb) != 262_144) {
            throw new IllegalStateException("bytesToKb(268435456) 应为 262144，实际 " + bytesToKb(bytesOf256Mb));
        }
        if (bytesToMb(bytesOf256Mb) != 256) {
            throw new IllegalStateException("bytesToMb(268435456) 应为 256，实际 " + bytesToMb(bytesOf256Mb));
        }

        // 方向三：契约里「沙箱 mem=51200 KB 显示 50.0 MB」的那一对
        if (bytesToMb(bytesOf50Mb) != 50) {
            throw new IllegalStateException("bytesToMb(52428800) 应为 50，实际 " + bytesToMb(bytesOf50Mb));
        }

        // 方向四：四舍五入边界（1023 B → 1 KB，1025 B → 1 KB，1536 B → 2 KB）
        if (bytesToKb(1023) != 1 || bytesToKb(1536) != 2) {
            throw new IllegalStateException("bytesToKb 四舍五入不成立：1023→" + bytesToKb(1023)
                    + "，1536→" + bytesToKb(1536));
        }

        // 方向五：0 / 负数兜底
        if (bytesToKb(0) != 0 || bytesToKb(-1) != 0) {
            throw new IllegalStateException("bytesToKb 对 0/负数应返回 0");
        }
    }

    // ==================================================================
    // 二、限额计算（语言倍率只在这里乘）
    // ==================================================================

    /**
     * 语言是否享受 ×2 时空补偿（{@code java} / {@code python}）。
     *
     * @param language cpp / java / python
     * @return true = ×2
     */
    public static boolean isDoubledLanguage(String language) {
        String lang = normalizeLanguage(language);
        return "java".equals(lang) || "python".equals(lang);
    }

    /**
     * 有效时限（毫秒）：{@code timeLimit × (java|python ? 2 : 1)}。
     *
     * @param timeLimitMs 题目原始时限（接口层展示的一律是它，不带倍率）
     * @param language    语言
     * @return 实际下发给沙箱的毫秒数
     */
    public static int effectiveTimeMs(int timeLimitMs, String language) {
        int base = Math.max(timeLimitMs, 1);
        return isDoubledLanguage(language) ? base * 2 : base;
    }

    /**
     * 有效内存（MB）：{@code memoryLimit × (java|python ? 2 : 1)}。
     *
     * @param memoryLimitMb 题目原始内存（MB）
     * @param language      语言
     * @return 实际下发给沙箱的 MB 数
     */
    public static int effectiveMemoryMb(int memoryLimitMb, String language) {
        int base = Math.max(memoryLimitMb, 1);
        return isDoubledLanguage(language) ? base * 2 : base;
    }

    /**
     * 时限 → {@code cpuLimit}（纳秒）。
     *
     * @param effectiveTimeMs 已乘过语言倍率的毫秒
     * @return 纳秒
     */
    public static long toCpuLimitNanos(int effectiveTimeMs) {
        return effectiveTimeMs * 1_000_000L;
    }

    /**
     * 时限 → {@code clockLimit}（纳秒）= {@code cpuLimit × 2}。
     *
     * <p>CPU 时间是「进程真正占 CPU」的时间，墙钟是「从启动到结束」的时间。
     * 墙钟给 2 倍是给 JVM 启动、IO 等待、调度抖动留余量；
     * 只卡 CPU 会让 {@code sleep} 型死循环漏判，只卡墙钟会误杀慢机器上的正解。</p>
     *
     * @param cpuLimitNanos cpuLimit（纳秒）
     * @return clockLimit（纳秒）
     */
    public static long toClockLimitNanos(long cpuLimitNanos) {
        return cpuLimitNanos * 2L;
    }

    /**
     * 规范化语言标识（大小写不敏感，兼容 {@code C++} / {@code cpp} / {@code CPP}）。
     *
     * @param language 原始语言
     * @return 小写规范值；无法识别时原样返回小写
     */
    public static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "cpp";
        }
        String lang = language.trim().toLowerCase(Locale.ROOT);
        return switch (lang) {
            case "c++", "cpp", "cpp17", "c++17" -> "cpp";
            case "py", "python", "python3", "py3" -> "python";
            case "java", "java17", "java21" -> "java";
            default -> lang;
        };
    }

    /**
     * 语言展示标签（基线决策 §5.3，禁止在业务代码里散写字面量）。
     *
     * @param language cpp / java / python
     * @return {@code C++ 17} / {@code Java 17} / {@code Python 3}
     */
    public static String languageLabel(String language) {
        return switch (normalizeLanguage(language)) {
            case "java" -> "Java 17";
            case "python" -> "Python 3";
            default -> "C++ 17";
        };
    }

    // ==================================================================
    // 三、输出比对
    // ==================================================================

    /**
     * 规范化输出：按行 {@code stripTrailing()}（去行尾空格与 {@code \r}），
     * 并忽略末尾多余空行（契约与 agent.md §5.3 的逐字要求）。
     *
     * <p><b>为什么不能简单 {@code trim()}：</b>Windows 生成的 {@code .out} 带 {@code \r\n}，
     * 而 Linux 沙箱输出是 {@code \n}，不归一化会把<b>所有题</b>判成 WA；
     * 反过来若把中间空行也吃掉，又会把「输出多打一个空行」的错解判成 AC。</p>
     *
     * @param text 原始文本（可为 null）
     * @return 规范化后的文本
     */
    public static String normalizeOutput(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = unified.split("\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String line : rawLines) {
            lines.add(stripTrailing(line));
        }
        // 只裁掉末尾的空行，保留中间的空行（中间空行是有语义的）
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isEmpty()) {
            end--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** 去掉行尾空白（空格 / 制表符），不动行首缩进 */
    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\u000B' || c == '\f') {
                end--;
            } else {
                break;
            }
        }
        return line.substring(0, end);
    }

    /**
     * 输出是否匹配。
     *
     * @param expected 期望输出（{@code .out} 文件内容）
     * @param actual   实际输出（沙箱 stdout）
     * @return true = 通过
     */
    public static boolean outputMatches(String expected, String actual) {
        return normalizeOutput(expected).equals(normalizeOutput(actual));
    }

    // ==================================================================
    // 四、go-judge 状态 → 判题状态码
    // ==================================================================

    /**
     * 把 go-judge 的 {@code status} 字符串映射成 {@link JudgeStatusEnum}。
     *
     * <p><b>用 {@code contains} 不用 {@code equals}：</b>实测取值里
     * {@code "Nonzero Exit Status"} / {@code "Time Limit Exceeded"} 都带空格，
     * 但上游小版本间可能加前缀（如 {@code "run: Time Limit Exceeded"}），
     * 精确相等会在升级时静默失效。</p>
     *
     * @param runStatus go-judge 的 status 字符串
     * @return 判题状态；未知取值按 RE 处理（保守：绝不因为看不懂就判 AC）
     */
    public static JudgeStatusEnum mapRunStatus(String runStatus) {
        String s = (runStatus == null) ? "" : runStatus.trim();
        if (s.isEmpty()) {
            return JudgeStatusEnum.RE;
        }
        if (s.contains(GJ_TLE)) {
            return JudgeStatusEnum.TLE;
        }
        if (s.contains(GJ_MLE)) {
            return JudgeStatusEnum.MLE;
        }
        if (s.contains(GJ_OLE)) {
            // 输出超限在契约里没有独立状态，归到 WA（输出不对）而不是 RE
            return JudgeStatusEnum.WA;
        }
        if (s.contains(GJ_ACCEPTED)) {
            return JudgeStatusEnum.AC;
        }
        if (s.contains(GJ_SIGNALLED) || s.contains(GJ_NONZERO_EXIT)) {
            return JudgeStatusEnum.RE;
        }
        if (s.contains(GJ_FILE_ERROR) || s.contains(GJ_INTERNAL_ERROR)) {
            // 沙箱自身故障：按 RE 落状态，但调用方必须同时把 error 原文写进 errorMsg，
            // 让运维能区分「用户代码崩了」和「沙箱坏了」
            return JudgeStatusEnum.RE;
        }
        return JudgeStatusEnum.RE;
    }

    /**
     * 单个测试点的最终判定：先看沙箱状态，再看输出。
     *
     * <p>顺序不能反：一个 TLE 的程序可能输出了正确的前半段，先比输出会误判 AC。</p>
     *
     * @param runStatus go-judge status
     * @param expected  期望输出
     * @param actual    实际输出
     * @return AC / WA / TLE / MLE / RE
     */
    public static JudgeStatusEnum judgeCase(String runStatus, String expected, String actual) {
        JudgeStatusEnum status = mapRunStatus(runStatus);
        if (status != JudgeStatusEnum.AC) {
            return status;
        }
        return outputMatches(expected, actual) ? JudgeStatusEnum.AC : JudgeStatusEnum.WA;
    }

    /**
     * 汇总整体状态：全部通过 → AC；部分通过 → PAC；否则取<b>首个失败点</b>的状态。
     *
     * @param caseStatuses 逐测试点状态（按 seqNo 升序）
     * @return 整体状态
     */
    public static JudgeStatusEnum aggregateStatus(List<JudgeStatusEnum> caseStatuses) {
        if (caseStatuses == null || caseStatuses.isEmpty()) {
            return JudgeStatusEnum.RE;
        }
        boolean allPass = true;
        for (JudgeStatusEnum status : caseStatuses) {
            if (status != JudgeStatusEnum.AC) {
                allPass = false;
                break;
            }
        }
        if (allPass) {
            return JudgeStatusEnum.AC;
        }
        for (JudgeStatusEnum status : caseStatuses) {
            if (status != JudgeStatusEnum.AC) {
                return status;
            }
        }
        return JudgeStatusEnum.RE;
    }

    // ==================================================================
    // 五、语言模板
    // ==================================================================

    /**
     * 源码文件名（写入 {@code /w} 的名字）。
     *
     * @param language cpp / java / python
     * @return {@code Main.cpp} / {@code Main.java} / {@code main.py}
     */
    public static String sourceFileName(String language) {
        return switch (normalizeLanguage(language)) {
            case "java" -> "Main.java";
            case "python" -> "main.py";
            default -> "Main.cpp";
        };
    }

    /**
     * 编译命令（在 {@code /bin/sh -c} 里执行）。
     *
     * <p>统一「编译到 {@code /w/bin} → 打包 {@code /w/bin} 内容 → base64」，
     * 这样阶段 2 只需 {@code cd /w && base64 -d && tar xzf} 就能还原出与编译时
     * <b>完全一致</b>的相对目录结构（实测：C++ 的 {@code ./m}、Java 的
     * {@code java -cp /w Main}、Python 的 {@code /w/main.py} 都依赖这一点）。</p>
     *
     * @param language cpp / java / python
     * @return shell 脚本
     */
    public static String compileScript(String language) {
        String lang = normalizeLanguage(language);
        String src = SANDBOX_WORK + "/" + sourceFileName(lang);
        String build = switch (lang) {
            case "java" -> "javac -encoding UTF-8 -d " + SANDBOX_BIN + " " + src;
            // py_compile 只做语法检查，产物落在 /w/__pycache__（不在 /w/bin），
            // 所以必须显式把源码拷进 /w/bin，否则打包出来的 tar 是空的，
            // 阶段 2 解包后 /w/main.py 不存在 → 每个测试点都 RE。
            case "python" -> "python3 -m py_compile " + src + " && cp " + src + " " + SANDBOX_BIN + "/main.py";
            default -> "g++ -O2 -std=c++17 -o " + SANDBOX_BIN + "/m " + src;
        };
        return "mkdir -p " + SANDBOX_BIN + " && cd " + SANDBOX_WORK
                + " && " + build
                // 把编译产物（不含源码，源码留在 /w 供排障）打成 tar.gz 再转 base64 文本，
                // 因为 copyIn by fileId 在本机 v1.13 上 404，只能靠 content 传二进制
                + " && tar czf " + SANDBOX_WORK + "/art.tgz -C " + SANDBOX_BIN + " ."
                + " && base64 -w0 " + SANDBOX_WORK + "/art.tgz > " + SANDBOX_WORK + "/" + ARTIFACT_B64;
    }

    /**
     * 运行命令（阶段 2 每个 cmd 的 {@code args} 第三项）。
     *
     * @param language cpp / java / python
     * @return shell 脚本
     */
    public static String runScript(String language) {
        String lang = normalizeLanguage(language);
        String exec = switch (lang) {
            // C++ 产物在 /w/bin/m，解包后 /w/m（tar 的是 /w/bin 的内容）
            case "java" -> "java -cp " + SANDBOX_WORK + " Main";
            case "python" -> "python3 " + SANDBOX_WORK + "/main.py";
            default -> "./m";
        };
        return "cd " + SANDBOX_WORK + " && base64 -d " + ARTIFACT_B64 + " > art.tgz"
                + " && tar xzf art.tgz && " + exec;
    }

    // ==================================================================
    // 六、请求构造（纯函数，便于 main 里断言 JSON 结构）
    // ==================================================================

    /**
     * 构造编译请求 JSON（单 cmd）。
     *
     * @param objectMapper Jackson 实例
     * @param language     语言
     * @param sourceCode   源码
     * @return 请求 JSON 字符串
     */
    public static String buildCompileRequest(ObjectMapper objectMapper, String language, String sourceCode) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode cmds = root.putArray("cmd");

        ObjectNode cmd = cmds.addObject();
        cmd.putArray("args").add("/bin/sh").add("-c").add(compileScript(language));
        fillEnv(cmd.putArray("env"));

        // files 位置语义：第 0 项 stdin（编译不吃输入，给空）、第 1 项 stdout、第 2 项 stderr
        ArrayNode files = cmd.putArray("files");
        files.addObject().put("content", "");
        files.addObject().put("name", "stdout").put("max", 1 << 20);
        files.addObject().put("name", "stderr").put("max", 1 << 20);

        cmd.put("cpuLimit", COMPILE_CPU_LIMIT_NS);
        cmd.put("clockLimit", COMPILE_CLOCK_LIMIT_NS);
        cmd.put("memoryLimit", COMPILE_MEMORY_LIMIT_BYTES);
        cmd.put("stackLimit", STACK_LIMIT_BYTES);
        cmd.put("procLimit", PROC_LIMIT);

        // copyIn 是 map：{"目标路径": {"content": "..."}}
        ObjectNode copyIn = cmd.putObject("copyIn");
        copyIn.putObject(SANDBOX_WORK + "/" + sourceFileName(language)).put("content", sourceCode);

        // copyOut 取编译产物（base64 文本）
        cmd.putArray("copyOut").add(ARTIFACT_B64);
        return root.toString();
    }

    /**
     * 构造阶段 2 的运行请求 JSON（N 个 cmd，每个测试点一个）。
     *
     * @param objectMapper     Jackson 实例
     * @param language         语言
     * @param artifactBase64   阶段 1 取回的编译产物 base64
     * @param cases            测试点执行清单（有序，下标即响应顺序）
     * @param cpuLimitNanos    cpuLimit（纳秒，已乘语言倍率）
     * @param clockLimitNanos  clockLimit（纳秒）
     * @param memoryLimitBytes memoryLimit（字节，已乘语言倍率）
     * @param outputLimitBytes stdout 上限（字节）
     * @param useContainerSrc  true = stdin 用容器内挂载路径（大文件必需）
     * @return 请求 JSON 字符串
     */
    public static String buildRunRequest(ObjectMapper objectMapper,
                                         String language,
                                         String artifactBase64,
                                         List<CaseInput> cases,
                                         long cpuLimitNanos,
                                         long clockLimitNanos,
                                         long memoryLimitBytes,
                                         int outputLimitBytes,
                                         boolean useContainerSrc) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode cmds = root.putArray("cmd");
        for (CaseInput testCase : cases) {
            ObjectNode cmd = cmds.addObject();
            cmd.putArray("args").add("/bin/sh").add("-c").add(runScript(language));
            fillEnv(cmd.putArray("env"));

            // files 位置语义（实测）：第 0 项 = stdin，第 1 项 = stdout，第 2 项 = stderr。
            // name 只是响应 files 的键名，不决定 fd —— 写反了 stdout 会变空。
            ArrayNode files = cmd.putArray("files");
            ObjectNode stdin = files.addObject();
            if (useContainerSrc && testCase.hasContainerPath()) {
                // 直挂 go-judge 容器内的 /data/testcases（docker-compose :ro 挂载），
                // 205MB 输入耗时 0.0s；走 copyIn 会撞 /w 的 128MB tmpfs 上限。
                stdin.put("src", testCase.containerPath());
            } else {
                stdin.put("content", testCase.contentOrEmpty());
            }
            files.addObject().put("name", "stdout").put("max", outputLimitBytes);
            files.addObject().put("name", "stderr").put("max", 1 << 20);

            // 限值必须写在 cmd 内部（写到顶层会被静默忽略 → procLimit=0 → clone EAGAIN）
            cmd.put("cpuLimit", cpuLimitNanos);
            cmd.put("clockLimit", clockLimitNanos);
            cmd.put("memoryLimit", memoryLimitBytes);
            cmd.put("stackLimit", STACK_LIMIT_BYTES);
            cmd.put("procLimit", PROC_LIMIT);

            cmd.putObject("copyIn").putObject(SANDBOX_WORK + "/" + ARTIFACT_B64)
                    .put("content", artifactBase64);
        }
        return root.toString();
    }

    /**
     * 构造「每 cmd 独立编译 + 运行」的降级请求（编译产物 base64 超过
     * {@code max-artifact-bytes} 时使用）。
     *
     * <p>降级路径不搬运产物，而是把源码塞进每个 cmd 自己编译——慢，但一定能跑通。
     * 实测 C++ 编译约 0.45s，10 个点串行编译约 4.5s，可接受。</p>
     *
     * @param objectMapper     Jackson 实例
     * @param language         语言
     * @param sourceCode       源码
     * @param cases            测试点清单
     * @param cpuLimitNanos    单个 cmd 的 cpuLimit
     * @param clockLimitNanos  单个 cmd 的 clockLimit
     * @param memoryLimitBytes 单个 cmd 的 memoryLimit
     * @param outputLimitBytes stdout 上限
     * @param useContainerSrc  是否用容器内挂载文件作 stdin
     * @return 请求 JSON 字符串
     */
    public static String buildInlineCompileRunRequest(ObjectMapper objectMapper,
                                                      String language,
                                                      String sourceCode,
                                                      List<CaseInput> cases,
                                                      long cpuLimitNanos,
                                                      long clockLimitNanos,
                                                      long memoryLimitBytes,
                                                      int outputLimitBytes,
                                                      boolean useContainerSrc) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode cmds = root.putArray("cmd");
        String combined = compileScript(language) + " && " + runScript(language);
        for (CaseInput testCase : cases) {
            ObjectNode cmd = cmds.addObject();
            cmd.putArray("args").add("/bin/sh").add("-c").add(combined);
            fillEnv(cmd.putArray("env"));

            ArrayNode files = cmd.putArray("files");
            ObjectNode stdin = files.addObject();
            if (useContainerSrc && testCase.hasContainerPath()) {
                stdin.put("src", testCase.containerPath());
            } else {
                stdin.put("content", testCase.contentOrEmpty());
            }
            files.addObject().put("name", "stdout").put("max", outputLimitBytes);
            files.addObject().put("name", "stderr").put("max", 1 << 20);

            // 降级路径要把编译时间算进去：编译 20s + 题目时限
            cmd.put("cpuLimit", cpuLimitNanos + COMPILE_CPU_LIMIT_NS);
            cmd.put("clockLimit", clockLimitNanos + COMPILE_CLOCK_LIMIT_NS);
            cmd.put("memoryLimit", Math.max(memoryLimitBytes, COMPILE_MEMORY_LIMIT_BYTES));
            cmd.put("stackLimit", STACK_LIMIT_BYTES);
            cmd.put("procLimit", PROC_LIMIT);

            cmd.putObject("copyIn")
                    .putObject(SANDBOX_WORK + "/" + sourceFileName(language))
                    .put("content", sourceCode);
        }
        return root.toString();
    }

    // ==================================================================
    // 七、响应解析
    // ==================================================================

    /**
     * 解析 {@code /run} 响应为结果列表。
     *
     * <p>v1.13 的形态：1 个 cmd → 单对象；N 个 cmd → 数组。两种都要吃。</p>
     *
     * @param objectMapper Jackson 实例
     * @param responseBody 响应体原文
     * @return 结果列表（与请求 cmd 顺序一一对应）
     */
    public static List<RunResult> parseRunResults(ObjectMapper objectMapper, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<RunResult> results = new ArrayList<>();
            if (root == null || root.isNull() || root.isMissingNode()) {
                return results;
            }
            if (root.isArray()) {
                for (JsonNode node : root) {
                    results.add(toRunResult(node));
                }
            } else {
                results.add(toRunResult(root));
            }
            return results;
        } catch (IOException e) {
            throw new UncheckedIOException("解析 go-judge 响应失败", e);
        }
    }

    /** 单条 cmd 结果 → {@link RunResult} */
    private static RunResult toRunResult(JsonNode node) {
        RunResult result = new RunResult();
        result.setStatus(textOf(node, "status"));
        result.setExitStatus(node.hasNonNull("exitStatus") ? node.get("exitStatus").asInt() : null);
        result.setTimeNanos(node.hasNonNull("time") ? node.get("time").asLong() : 0L);
        result.setMemoryBytes(node.hasNonNull("memory") ? node.get("memory").asLong() : 0L);
        result.setError(textOf(node, "error"));
        JsonNode files = node.get("files");
        if (files != null && files.isObject()) {
            if (files.hasNonNull("stdout")) {
                result.setStdout(files.get("stdout").asText());
            }
            if (files.hasNonNull("stderr")) {
                result.setStderr(files.get("stderr").asText());
            }
            // 编译阶段 copyOut 的文件也落在 files 里（键名 = copyOut 里的文件名）
            if (files.hasNonNull(ARTIFACT_B64)) {
                result.setArtifactBase64(files.get(ARTIFACT_B64).asText());
            }
        }
        return result;
    }

    /** 安全取字符串字段 */
    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /** 把固定的沙箱环境变量灌进 {@code env} 数组 */
    private static void fillEnv(ArrayNode env) {
        for (String value : SANDBOX_ENV) {
            env.add(value);
        }
    }

    // ==================================================================
    // 八、内部数据结构
    // ==================================================================

    /**
     * 一个测试点的执行输入。
     *
     * <p>两种取数方式：{@link #containerPath}（容器内挂载路径，推荐）与
     * {@link #content}（Java 读盘后内联，仅小文件可用）。</p>
     */
    public static final class CaseInput {

        /** 序号（从 1 开始） */
        private final int seqNo;

        /** go-judge 容器内路径，如 {@code /data/testcases/1006/1.in} */
        private final String containerPath;

        /** 内联内容（仅在 {@code useContainerSrc=false} 时使用） */
        private final String content;

        private CaseInput(int seqNo, String containerPath, String content) {
            this.seqNo = seqNo;
            this.containerPath = containerPath;
            this.content = content;
        }

        /**
         * 用容器内挂载路径构造（推荐）。
         *
         * @param seqNo         序号
         * @param containerPath 容器内路径
         * @return 输入描述
         */
        public static CaseInput ofContainerPath(int seqNo, String containerPath) {
            return new CaseInput(seqNo, containerPath, null);
        }

        /**
         * 用内联内容构造（小文件 / 无挂载场景）。
         *
         * @param seqNo         序号
         * @param containerPath 容器内路径（降级时仅用于日志）
         * @param content       文件内容
         * @return 输入描述
         */
        public static CaseInput ofContent(int seqNo, String containerPath, String content) {
            return new CaseInput(seqNo, containerPath, content);
        }

        public int getSeqNo() {
            return seqNo;
        }

        public String containerPath() {
            return containerPath;
        }

        /**
         * 是否有可用的容器内路径。
         *
         * <p>内联输入（self-test / run-sample 的 custom 模式）没有磁盘文件，
         * {@code containerPath} 为空 —— 此时必须回退到 {@code content}，
         * 否则 go-judge 会返回 400 {@code "source path is empty"}。</p>
         *
         * @return true = 可以用 {@code src}
         */
        public boolean hasContainerPath() {
            return containerPath != null && !containerPath.isBlank();
        }

        public String contentOrEmpty() {
            return content == null ? "" : content;
        }
    }

    /** go-judge 单条 cmd 的执行结果（原样映射，不做业务判定） */
    public static final class RunResult {

        /** go-judge 的 status 字符串，如 {@code Accepted} */
        private String status;

        /** 退出码（被信号杀死时是信号值，如 11 = SIGSEGV） */
        private Integer exitStatus;

        /** 用时（纳秒，go-judge 原值） */
        private long timeNanos;

        /** 内存（字节，go-judge 原值） */
        private long memoryBytes;

        /** 沙箱自身的错误描述（非用户代码错误） */
        private String error;

        /** 标准输出 */
        private String stdout = "";

        /** 标准错误 */
        private String stderr = "";

        /** 编译阶段 copyOut 回来的产物 base64 */
        private String artifactBase64;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Integer getExitStatus() {
            return exitStatus;
        }

        public void setExitStatus(Integer exitStatus) {
            this.exitStatus = exitStatus;
        }

        public long getTimeNanos() {
            return timeNanos;
        }

        public void setTimeNanos(long timeNanos) {
            this.timeNanos = timeNanos;
        }

        public long getMemoryBytes() {
            return memoryBytes;
        }

        public void setMemoryBytes(long memoryBytes) {
            this.memoryBytes = memoryBytes;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getStdout() {
            return stdout == null ? "" : stdout;
        }

        public void setStdout(String stdout) {
            this.stdout = stdout;
        }

        public String getStderr() {
            return stderr == null ? "" : stderr;
        }

        public void setStderr(String stderr) {
            this.stderr = stderr;
        }

        public String getArtifactBase64() {
            return artifactBase64 == null ? "" : artifactBase64;
        }

        public void setArtifactBase64(String artifactBase64) {
            this.artifactBase64 = artifactBase64;
        }

        /** 用时 → 毫秒（四舍五入，最小 0） */
        public int timeMs() {
            if (timeNanos <= 0) {
                return 0;
            }
            return (int) Math.max(0L, Math.round(timeNanos / 1_000_000.0));
        }

        /** 内存 → KB（走唯一的换算入口） */
        public int memoryKb() {
            return bytesToKb(memoryBytes);
        }

        /** 是否沙箱自身故障（而非用户代码问题） */
        public boolean isSandboxFault() {
            return status != null && (status.contains(GJ_FILE_ERROR) || status.contains(GJ_INTERNAL_ERROR));
        }
    }

    // ==================================================================
    // 九、可执行自检（不依赖 Spring，直接 java 跑）
    // ==================================================================

    /**
     * 换算与协议自检入口。
     *
     * <p>用法（容器内）：</p>
     * <pre>
     * mvn -B -q -pl oj-judge-service -am compile
     * java -cp oj-judge-service/target/classes:... com.oj.judge.adapter.JudgeAdapter
     * </pre>
     * <p>或者直接跑 {@code JudgeAdapterSelfTest}（会额外验证 JSON 结构）。</p>
     *
     * @param args 忽略
     */
    public static void main(String[] args) {
        assertUnitConversionContract();
        System.out.println("[PASS] 51200 KB  <-> " + kbToBytes(51200L) + " B");
        System.out.println("[PASS] 268435456 B -> " + bytesToKb(268_435_456L) + " KB / "
                + bytesToMb(268_435_456L) + " MB");
        System.out.println("[PASS] 256 MB    -> " + mbToBytes(256L) + " B");

        // 语言倍率
        check("cpp 2000ms ×1 = 2000", effectiveTimeMs(2000, "cpp") == 2000);
        check("java 2000ms ×2 = 4000", effectiveTimeMs(2000, "java") == 4000);
        check("python 2000ms ×2 = 4000", effectiveTimeMs(2000, "python") == 4000);
        check("java 512MB ×2 = 1024MB", effectiveMemoryMb(512, "java") == 1024);
        check("cpuLimit(2000ms) = 2e9 ns", toCpuLimitNanos(2000) == 2_000_000_000L);
        check("clockLimit = 2 × cpuLimit", toClockLimitNanos(2_000_000_000L) == 4_000_000_000L);

        // 输出比对
        check("忽略行尾空格", outputMatches("1 2  \n3\t\n", "1 2\n3\n"));
        check("忽略 CRLF", outputMatches("3\r\n", "3\n"));
        check("忽略末尾空行", outputMatches("3\n\n\n", "3\n"));
        check("不忽略中间空行", !outputMatches("1\n\n2\n", "1\n2\n"));
        check("数字不同判 WA", !outputMatches("788", "787"));

        // 状态映射（实测取值）
        check("Accepted → AC", mapRunStatus(GJ_ACCEPTED) == JudgeStatusEnum.AC);
        check("Nonzero Exit Status → RE", mapRunStatus(GJ_NONZERO_EXIT) == JudgeStatusEnum.RE);
        check("Signalled → RE", mapRunStatus(GJ_SIGNALLED) == JudgeStatusEnum.RE);
        check("Time Limit Exceeded → TLE", mapRunStatus(GJ_TLE) == JudgeStatusEnum.TLE);
        check("Memory Limit Exceeded → MLE", mapRunStatus(GJ_MLE) == JudgeStatusEnum.MLE);
        check("Output Limit Exceeded → WA", mapRunStatus(GJ_OLE) == JudgeStatusEnum.WA);
        check("未知状态 → RE（不判 AC）", mapRunStatus("Weird") == JudgeStatusEnum.RE);
        check("null 状态 → RE", mapRunStatus(null) == JudgeStatusEnum.RE);
        check("AC+输出不符 → WA", judgeCase(GJ_ACCEPTED, "3", "2") == JudgeStatusEnum.WA);
        check("AC+输出相符 → AC", judgeCase(GJ_ACCEPTED, "3", "3\n") == JudgeStatusEnum.AC);
        check("TLE 优先于输出比对", judgeCase(GJ_TLE, "3", "3") == JudgeStatusEnum.TLE);

        // 汇总
        check("全 AC → AC", aggregateStatus(List.of(JudgeStatusEnum.AC, JudgeStatusEnum.AC)) == JudgeStatusEnum.AC);
        check("首败为 WA → WA", aggregateStatus(List.of(JudgeStatusEnum.AC, JudgeStatusEnum.WA,
                JudgeStatusEnum.TLE)) == JudgeStatusEnum.WA);
        check("首败为 TLE → TLE", aggregateStatus(List.of(JudgeStatusEnum.TLE, JudgeStatusEnum.WA))
                == JudgeStatusEnum.TLE);

        // 语言
        check("C++ 标签", "C++ 17".equals(languageLabel("cpp")));
        check("Java 标签", "Java 17".equals(languageLabel("JAVA")));
        check("Python 标签", "Python 3".equals(languageLabel("py")));
        check("源码文件名 java", "Main.java".equals(sourceFileName("java")));

        System.out.println("\nJudgeAdapter 自检全部通过。");
    }

    /** 断言辅助 */
    private static void check(String name, boolean condition) {
        if (!condition) {
            throw new IllegalStateException("[FAIL] " + name);
        }
        System.out.println("[PASS] " + name);
    }

    // ==================================================================
    // 十、测试数据路径（宿主机读 .out / 容器内作 stdin）
    // ==================================================================

    /**
     * 宿主机测试点文件路径：{@code {root}/{problemId}/{seqNo}.in|.out}。
     *
     * <p>判题比对<b>必须</b>读磁盘（{@code prob_test_case.input_text} 只是 ≤4 KB 摘要，
     * 用它比对 = 100% 误判，见 day3 §7）。</p>
     *
     * @param root      宿主机测试数据根目录
     * @param problemId 题目 ID
     * @param seqNo     测试点序号
     * @param ext       {@code in} 或 {@code out}
     * @return 路径
     */
    public static Path hostCasePath(Path root, Long problemId, int seqNo, String ext) {
        return root.resolve(String.valueOf(problemId)).resolve(seqNo + "." + ext);
    }

    /**
     * go-judge 容器内测试点路径：{@code {containerRoot}/{problemId}/{seqNo}.in}。
     *
     * @param containerRoot 容器内根目录，如 {@code /data/testcases}
     * @param problemId     题目 ID
     * @param seqNo         序号
     * @return 容器内绝对路径
     */
    public static String containerCasePath(String containerRoot, Long problemId, int seqNo) {
        String root = (containerRoot == null || containerRoot.isBlank()) ? "/data/testcases" : containerRoot.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + "/" + problemId + "/" + seqNo + ".in";
    }

    /**
     * 读宿主机测试点文件（UTF-8）。文件不存在时抛 404 语义的业务异常由调用方处理。
     *
     * @param path 文件路径
     * @return 文本内容
     */
    public static String readCaseFile(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取测试数据失败：" + path, e);
        }
    }

    /**
     * 把 KB 数值格式化成 MB 展示（日志用，避免浮点误差）。
     *
     * @param kb KB
     * @return 如 {@code 50.0}
     */
    public static String kbToMbText(int kb) {
        return BigDecimal.valueOf(kb)
                .divide(BigDecimal.valueOf(1024), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** 供外部拼日志用：语言 → 沙箱内源码路径 */
    public static String sandboxSourcePath(String language) {
        return SANDBOX_WORK + "/" + sourceFileName(language);
    }

    /** 供外部拼日志用：环境变量快照 */
    public static Map<String, String> sandboxEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String item : SANDBOX_ENV) {
            int idx = item.indexOf('=');
            env.put(item.substring(0, idx), item.substring(idx + 1));
        }
        return env;
    }
}
