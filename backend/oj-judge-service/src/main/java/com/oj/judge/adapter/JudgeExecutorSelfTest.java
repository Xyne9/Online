package com.oj.judge.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link JudgeExecutor} 的端到端自检（<b>会真实调用 go-judge</b>）。
 *
 * <p>这不是单元测试，而是「判题链路验收脚本」：它绕过 Spring，手工 new 出
 * {@link GoJudgeClient} 与 {@link JudgeExecutor}，对题目 1006（A+B，3 个测试点，
 * 30+30+40=100 分）跑三种语言的正确解，断言全部 AC 且得分 100。</p>
 *
 * <p><b>为什么要这个类：</b>判题服务最难的不是 CRUD，而是「协议到底对不对」。
 * 只靠 {@code mvn compile} 通过毫无意义——协议写错照样编译通过，运行时才炸。
 * 这个 main 让「编译 → 跑测试点 → 磁盘比对 → 计分」全链路在<b>不启动 Spring 服务</b>
 * 的前提下可复现验证（任务要求「不要启动 Spring Boot 服务」）。</p>
 *
 * <h2>用法</h2>
 * <pre>
 * # 1) 先编译
 * docker run --rm -v "D:\毕设\agent oj\backend:/w" -v "$env:USERPROFILE\.m2:/root/.m2" \
 *   -w /w maven:3.9-eclipse-temurin-17 mvn -B -q -pl oj-judge-service -am compile
 *
 * # 2) 用宿主机 JDK 17 跑（classpath 见 oj-judge-service/cp-host.txt）
 * java -cp "target/classes;$(cat cp-host.txt)" com.oj.judge.adapter.JudgeExecutorSelfTest
 * </pre>
 *
 * <p>退出码：0 全部通过；1 有失败。</p>
 *
 * @author oj-cloud
 */
public final class JudgeExecutorSelfTest {

    /** 冒烟题目：1006 = A + B，3 个测试点（30 + 30 + 40 = 100） */
    private static final long PROBLEM_ID = 1006L;

    /** 题目时限（毫秒，未乘倍率） */
    private static final int TIME_LIMIT_MS = 2000;

    /** 题目内存（MB，未乘倍率） */
    private static final int MEMORY_LIMIT_MB = 512;

    /** 测试点满分 */
    private static final int FULL_SCORE = 100;

    private static int passed;
    private static int failed;

    private JudgeExecutorSelfTest() {
    }

    /**
     * 入口。
     *
     * @param args 可覆盖沙箱地址与测试数据根目录：
     *             {@code args[0] = endpoint}，{@code args[1] = testcaseRoot}
     */
    public static void main(String[] args) {
        String endpoint = args.length > 0 ? args[0] : "http://127.0.0.1:5050";
        String testcaseRoot = args.length > 1 ? args[1] : "D:/毕设/agent oj/data/testcases";

        System.out.println("=== JudgeExecutor 端到端自检 ===");
        System.out.println("go-judge   : " + endpoint);
        System.out.println("testcase   : " + testcaseRoot);
        System.out.println();

        ObjectMapper objectMapper = new ObjectMapper();
        GoJudgeClient client = new GoJudgeClient(endpoint, 3000, 180_000);
        JudgeExecutor executor = new JudgeExecutor(client, objectMapper, testcaseRoot,
                "/data/testcases", true, 10 * 1024 * 1024, 8 * 1024 * 1024, 4);

        // 与 DB 里 prob_test_case 的 1006 行一致：1/2 是 sample 30 分，3 是 stress 40 分
        List<JudgeExecutor.CaseSpec> cases = new ArrayList<>();
        cases.add(new JudgeExecutor.CaseSpec(33L, 1, "sample", 30));
        cases.add(new JudgeExecutor.CaseSpec(34L, 2, "sample", 30));
        cases.add(new JudgeExecutor.CaseSpec(35L, 3, "stress", 40));

        // ── 用例 1：三语言正确解，应全部 AC / 100 分 ──
        checkAccepted(executor, cases, "cpp", CPP_AC, "C++ 正确解");
        checkAccepted(executor, cases, "java", JAVA_AC, "Java 正确解");
        checkAccepted(executor, cases, "python", PYTHON_AC, "Python 正确解");

        // ── 用例 2：错误解，应判 WA 且得分 0 ──
        JudgeExecutor.ExecutionResult wa = executor.execute(PROBLEM_ID, "cpp", CPP_WA,
                cases, TIME_LIMIT_MS, MEMORY_LIMIT_MB);
        expect("C++ 错误解判 WA", wa.getStatus() == JudgeStatusEnum.WA,
                "实际 " + wa.getStatus());
        expect("C++ 错误解得分 0", wa.getOiScore() == 0, "实际 " + wa.getOiScore());
        expect("C++ 错误解通过点数 0", wa.getPassCaseCount() == 0, "实际 " + wa.getPassCaseCount());
        expect("C++ 错误解记录首败点", wa.getFirstFailedSeqNo() != null && wa.getFirstFailedSeqNo() == 1,
                "实际 " + wa.getFirstFailedSeqNo());

        // ── 用例 3：编译错误，应判 CE 且 errorMsg 非空 ──
        JudgeExecutor.ExecutionResult ce = executor.execute(PROBLEM_ID, "cpp", CPP_CE,
                cases, TIME_LIMIT_MS, MEMORY_LIMIT_MB);
        expect("C++ 语法错判 CE", ce.getStatus() == JudgeStatusEnum.CE, "实际 " + ce.getStatus());
        expect("CE 带编译错误信息", ce.getErrorMsg() != null && !ce.getErrorMsg().isBlank(),
                "errorMsg 为空");
        expect("CE 的 isCompileError=true", ce.isCompileError(), "实际 false");
        if (ce.getErrorMsg() != null) {
            System.out.println("        CE 原文首行：" + ce.getErrorMsg().split("\n")[0]);
        }

        // ── 用例 4：死循环，应判 TLE（验证 cpuLimit 真的生效）──
        JudgeExecutor.ExecutionResult tle = executor.execute(PROBLEM_ID, "cpp", CPP_TLE,
                cases, TIME_LIMIT_MS, MEMORY_LIMIT_MB);
        expect("C++ 死循环判 TLE", tle.getStatus() == JudgeStatusEnum.TLE, "实际 " + tle.getStatus());

        // ── 用例 5：除零崩溃，应判 RE ──
        JudgeExecutor.ExecutionResult re = executor.execute(PROBLEM_ID, "cpp", CPP_RE,
                cases, TIME_LIMIT_MS, MEMORY_LIMIT_MB);
        expect("C++ 除零判 RE", re.getStatus() == JudgeStatusEnum.RE, "实际 " + re.getStatus());

        // ── 用例 6：部分通过 → PAC（只让第 1、2 点过，第 3 点输出错）──
        JudgeExecutor.ExecutionResult pac = executor.execute(PROBLEM_ID, "cpp", CPP_PARTIAL,
                cases, TIME_LIMIT_MS, MEMORY_LIMIT_MB);
        expect("部分通过判 PAC", pac.getStatus() == JudgeStatusEnum.PAC, "实际 " + pac.getStatus());
        expect("部分通过得分 60", pac.getOiScore() == 60, "实际 " + pac.getOiScore());
        expect("部分通过通过点数 2", pac.getPassCaseCount() == 2, "实际 " + pac.getPassCaseCount());

        // ── 用例 7：语言倍率（java 的 effectiveTimeMs 是 4000）──
        expect("java 倍率 ×2", JudgeAdapter.effectiveTimeMs(TIME_LIMIT_MS, "java") == 4000,
                "实际 " + JudgeAdapter.effectiveTimeMs(TIME_LIMIT_MS, "java"));
        expect("cpp 倍率 ×1", JudgeAdapter.effectiveTimeMs(TIME_LIMIT_MS, "cpp") == 2000,
                "实际 " + JudgeAdapter.effectiveTimeMs(TIME_LIMIT_MS, "cpp"));

        // ── 用例 8：数据缺失必须是 502，不能伪造结果 ──
        boolean threw = false;
        try {
            executor.execute(999_999L, "cpp", CPP_AC, cases, TIME_LIMIT_MS, MEMORY_LIMIT_MB);
        } catch (com.oj.common.core.exception.BusinessException e) {
            threw = e.getCode() == 502;
        }
        expect("测试数据缺失抛 502（不伪造 AC）", threw, "未抛 502");

        System.out.println();
        System.out.println("========================================");
        System.out.println("结果：" + passed + " 通过 / " + failed + " 失败");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 断言「正确解应 AC 且满分」 */
    private static void checkAccepted(JudgeExecutor executor, List<JudgeExecutor.CaseSpec> cases,
                                      String language, String code, String title) {
        long start = System.currentTimeMillis();
        JudgeExecutor.ExecutionResult result = executor.execute(PROBLEM_ID, language, code,
                cases, TIME_LIMIT_MS, MEMORY_LIMIT_MB);
        long cost = System.currentTimeMillis() - start;
        expect(title + " 判 AC", result.getStatus() == JudgeStatusEnum.AC, "实际 " + result.getStatus());
        expect(title + " 得分 100", result.getOiScore() == FULL_SCORE, "实际 " + result.getOiScore());
        expect(title + " 通过 3 点", result.getPassCaseCount() == 3, "实际 " + result.getPassCaseCount());
        expect(title + " 无首败点", result.getFirstFailedSeqNo() == null,
                "实际 " + result.getFirstFailedSeqNo());
        expect(title + " 耗时有值", result.getTimeUsedMs() >= 0, "负值");
        expect(title + " 内存有值", result.getMemoryUsedKb() > 0, "实际 " + result.getMemoryUsedKb());
        System.out.printf("        [%s] %dms（编译 %dms / 运行 %dms） 峰值 %dms / %dKB%n",
                language, cost, result.getCompileCostMs(), result.getRunCostMs(),
                result.getTimeUsedMs(), result.getMemoryUsedKb());
        if (result.getCases() != null && !result.getCases().isEmpty()) {
            JudgeExecutor.CaseOutcome first = result.getCases().get(0);
            System.out.printf("        case1: status=%s time=%dms mem=%dKB got=%s%n",
                    first.getStatus().getLabel(), first.getTimeMs(), first.getMemKb(),
                    oneLine(first.getActualOutput()));
        }
    }

    /** 断言辅助 */
    private static void expect(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "  —— " + detail);
        }
    }

    /** 把多行输出压成一行（日志好看） */
    private static String oneLine(String text) {
        if (text == null) {
            return "null";
        }
        String single = text.replace("\r\n", "\\n").replace("\n", "\\n");
        return single.length() <= 60 ? single : single.substring(0, 60) + "...";
    }

    // ==================================================================
    // 测试源码（对应题目 1006：读两个整数，输出和）
    // ==================================================================

    /** C++ 正确解 */
    private static final String CPP_AC = """
            #include <iostream>
            int main() {
                long long a, b;
                if (!(std::cin >> a >> b)) return 0;
                std::cout << a + b << "\\n";
                return 0;
            }
            """;

    /** Java 正确解 */
    private static final String JAVA_AC = """
            import java.util.Scanner;

            public class Main {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    System.out.println(sc.nextLong() + sc.nextLong());
                }
            }
            """;

    /** Python 正确解 */
    private static final String PYTHON_AC = """
            a, b = map(int, input().split())
            print(a + b)
            """;

    /** C++ 错误解（输出乘积） */
    private static final String CPP_WA = """
            #include <iostream>
            int main() {
                long long a, b;
                std::cin >> a >> b;
                std::cout << a * b << "\\n";
                return 0;
            }
            """;

    /** C++ 编译错误 */
    private static final String CPP_CE = """
            #include <iostream>
            int main() {
                this is not valid c++
            }
            """;

    /** C++ 死循环 */
    private static final String CPP_TLE = """
            #include <iostream>
            int main() {
                long long a, b;
                std::cin >> a >> b;
                volatile long long spin = 0;
                while (true) { spin++; }
            }
            """;

    /** C++ 除零崩溃 */
    private static final String CPP_RE = """
            #include <iostream>
            int main() {
                long long a, b;
                std::cin >> a >> b;
                std::cout << a / (b - b) << "\\n";
                return 0;
            }
            """;

    /** C++ 部分通过：第 3 个测试点（1000000 1000000 → 2000000）故意输出错 */
    private static final String CPP_PARTIAL = """
            #include <iostream>
            int main() {
                long long a, b;
                std::cin >> a >> b;
                if (a == 1000000 && b == 1000000) {
                    std::cout << 0 << "\\n";
                } else {
                    std::cout << a + b << "\\n";
                }
                return 0;
            }
            """;

    /** 宿主机测试数据根目录（供外部引用） */
    public static Path defaultTestcaseRoot() {
        return Paths.get("D:/毕设/agent oj/data/testcases");
    }
}
