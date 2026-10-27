package com.oj.judge;

import com.oj.judge.adapter.JudgeStatusEnum;
import com.oj.judge.dto.JudgeRequests;
import com.oj.judge.entity.JudgeSubmission;
import com.oj.judge.mapper.JudgeSubmissionMapper;
import com.oj.judge.service.JudgeService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * RabbitMQ 异步判题<b>端到端自检</b>：真正走「生产者 → 交换机 → 队列 → 消费者 → 落库」。
 *
 * <p>{@link JudgePipelineCheck} 是手动调用 {@code judgeSubmission} 模拟消费；
 * 本类则验证<b>真实的 MQ 链路</b>——包括拓扑声明、JSON 序列化、
 * {@code @RabbitListener} 装配、手动 ack 是否真的把消息消费掉。
 * 这些是 {@code mvn compile} 与手动调用都发现不了的。</p>
 *
 * <h2>为什么用 {@code WebApplicationType.NONE}</h2>
 * <p>同 {@link JudgePipelineCheck}：不监听 8083 端口，不与 Lead 统一启动的服务冲突。
 * 但这里<b>开启</b> {@code listener.simple.auto-startup}，因为要验证消费者。</p>
 *
 * <h2>前置条件</h2>
 * <p>RabbitMQ 的 {@code oj.judge.submit.queue} 必须为空（脚本会先 purge），
 * 否则会顺带消费到历史消息导致断言混乱。</p>
 *
 * <p>用法：{@code java -cp "target/classes;$(cat cp-host.txt)" com.oj.judge.JudgeMqCheck}</p>
 * <p>退出码：0 全部通过；1 有失败。</p>
 *
 * @author oj-cloud
 */
public final class JudgeMqCheck {

    /** 冒烟题目 */
    private static final long PROBLEM_ID = 1006L;

    /** 测试用户 */
    private static final long TEST_USER_ID = 1L;

    /** 等待判题完成的最长时间（毫秒） */
    private static final long WAIT_TIMEOUT_MS = 60_000L;

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 200L;

    private static int passed;
    private static int failed;

    private JudgeMqCheck() {
    }

    /**
     * 入口。
     *
     * @param args 忽略
     */
    public static void main(String[] args) {
        System.out.println("=== JudgeMqCheck：RabbitMQ 异步判题端到端自检 ===");

        ConfigurableApplicationContext context = new SpringApplicationBuilder(JudgeApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.cloud.nacos.discovery.enabled=false",
                        "spring.cloud.service-registry.auto-registration.enabled=false",
                        // 先不开监听器：队列里可能有历史脏消息，必须 purge 之后再启动，
                        // 否则消费者会先抢到旧消息，断言全乱。
                        "spring.rabbitmq.listener.simple.auto-startup=false",
                        "logging.level.root=WARN",
                        "logging.level.com.oj=INFO")
                .run();

        try {
            runChecks(context);
        } finally {
            context.close();
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("结果：" + passed + " 通过 / " + failed + " 失败");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 全部检查项 */
    private static void runChecks(ConfigurableApplicationContext context) {
        JudgeService judgeService = context.getBean(JudgeService.class);
        JudgeSubmissionMapper submissionMapper = context.getBean(JudgeSubmissionMapper.class);
        RabbitTemplate rabbitTemplate = context.getBean(RabbitTemplate.class);

        // ── 1. 拓扑声明（RabbitConfig 的 3 个 Bean 是否真的建出来了）──
        System.out.println("\n--- 1. RabbitMQ 拓扑 ---");
        expect("交换机 Bean 存在", context.containsBean("judgeExchange"), "缺失");
        expect("队列 Bean 存在", context.containsBean("judgeQueue"), "缺失");
        expect("绑定 Bean 存在", context.containsBean("judgeBinding"), "缺失");
        org.springframework.amqp.core.Queue queue =
                context.getBean("judgeQueue", org.springframework.amqp.core.Queue.class);
        expect("队列名 = oj.judge.submit.queue",
                "oj.judge.submit.queue".equals(queue.getName()), queue.getName());
        expect("队列是持久化的", queue.isDurable(), "非持久化");
        // 真的去 broker 上做一次 passive declare：队列不存在会抛异常，
        // 这一步能验证「声明确实生效了」而不是只在 Spring 容器里有个 Bean。
        boolean declaredOnBroker = Boolean.TRUE.equals(rabbitTemplate.execute(channel -> {
            channel.queueDeclarePassive("oj.judge.submit.queue");
            return true;
        }));
        expect("队列已在 broker 上声明", declaredOnBroker, "passive declare 失败");
        System.out.println("        队列：" + queue.getName() + " durable=" + queue.isDurable());

        // ── 2. 清空历史消息（保证后续断言只看到本次提交）──
        System.out.println("\n--- 2. 清空队列历史消息 ---");
        Integer purged = rabbitTemplate.execute(channel -> {
            // passiveDeclare 拿到真实消息数后 purge；队列不存在会抛异常（拓扑没声明成功）
            channel.queueDeclarePassive("oj.judge.submit.queue");
            var result = channel.queuePurge("oj.judge.submit.queue");
            return result == null ? 0 : result.getMessageCount();
        });
        System.out.println("        已清除历史消息：" + purged + " 条");
        expect("队列已清空", purged != null, "purge 返回 null");

        // 清空之后才启动监听器（auto-startup=false 的补偿动作）
        org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry registry =
                context.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
        registry.start();
        expect("监听器已启动", !registry.getListenerContainers().isEmpty(), "无监听容器");
        System.out.println("        监听容器数：" + registry.getListenerContainers().size());

        // ── 3. 提交 → 等消费者处理完 ──
        System.out.println("\n--- 3. 提交并等待 MQ 消费者完成判题 ---");
        // 用真实存在的用户（judge_submission.user_id 有外键 fk_js_user → sys_user.id）。
        // 展示号由 Redis 序列保证唯一，所以复用 user 1 不会撞唯一键。
        Map<String, Object> submitResult = judgeService.submit(buildSubmitRequest(), TEST_USER_ID);
        Long submissionId = submitResult.get("submissionId") instanceof Number n ? n.longValue() : null;
        expect("submit 返回 submissionId", submissionId != null, "实际 " + submitResult);
        if (submissionId == null) {
            return;
        }
        System.out.println("        提交：" + submitResult);

        JudgeSubmission finished = waitForTerminal(submissionMapper, submissionId, WAIT_TIMEOUT_MS);
        expect("MQ 消费者在 " + WAIT_TIMEOUT_MS + "ms 内完成判题", finished != null,
                "超时，状态仍是 " + statusOf(submissionMapper, submissionId));
        if (finished == null) {
            cleanup(submissionMapper, submissionId);
            return;
        }

        expect("MQ 判题结果 = AC(3)", finished.getStatus() == 3, "实际 " + finished.getStatus());
        expect("MQ 判题得分 = 100", finished.getOiScore() == 100, "实际 " + finished.getOiScore());
        expect("MQ 判题通过 3 点", finished.getPassCaseCount() == 3, "实际 " + finished.getPassCaseCount());
        expect("MQ 判题 case_details 非空",
                finished.getCaseDetails() != null && !finished.getCaseDetails().isBlank(), "null");
        expect("MQ 判题 finished_at 已填", finished.getFinishedAt() != null, "null");
        System.out.printf("        MQ 判题结果：status=%d score=%d pass=%d time=%dms mem=%dKB%n",
                finished.getStatus(), finished.getOiScore(), finished.getPassCaseCount(),
                finished.getTimeUsed(), finished.getMemoryUsed());

        // ── 4. 消息已被 ack（队列不应残留未确认消息）──
        System.out.println("\n--- 4. 消息确认 ---");
        // 给 ack 一点时间落盘
        sleep(500);
        Long remaining = rabbitTemplate.execute(channel -> {
            var result = channel.queueDeclarePassive("oj.judge.submit.queue");
            return result == null ? -1L : (long) result.getMessageCount();
        });
        expect("队列已无待处理消息", remaining != null && remaining == 0, "剩余 " + remaining);
        System.out.println("        队列剩余消息数：" + remaining);

        // ── 5. 幂等性：重复消费同一提交不应改写结果 ──
        System.out.println("\n--- 5. 重复消费幂等性 ---");
        int before = finished.getStatus();
        judgeService.judgeSubmission(submissionId);
        JudgeSubmission after = submissionMapper.selectById(submissionId);
        expect("重复消费不改写终态", after != null && after.getStatus() == before,
                "before=" + before + " after=" + (after == null ? "null" : after.getStatus()));

        // ── 6. 清理 ──
        System.out.println("\n--- 6. 清理验证数据 ---");
        cleanup(submissionMapper, submissionId);
        expect("清理完成", submissionMapper.selectById(submissionId) == null, "记录仍存在");
    }

    /** 轮询等待提交进入终态 */
    private static JudgeSubmission waitForTerminal(JudgeSubmissionMapper mapper, Long submissionId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JudgeSubmission submission = mapper.selectById(submissionId);
            if (submission != null && JudgeStatusEnum.isTerminalStatus(submission.getStatus())) {
                return submission;
            }
            sleep(POLL_INTERVAL_MS);
        }
        return null;
    }

    /** 查当前状态（超时诊断用） */
    private static String statusOf(JudgeSubmissionMapper mapper, Long submissionId) {
        JudgeSubmission submission = mapper.selectById(submissionId);
        return submission == null ? "记录不存在" : String.valueOf(submission.getStatus());
    }

    /** 删除验证产生的提交记录 */
    private static void cleanup(JudgeSubmissionMapper mapper, Long submissionId) {
        try {
            mapper.deleteById(submissionId);
        } catch (Exception e) {
            System.out.println("        清理失败（可忽略）：" + e.getMessage());
        }
    }

    /** 构造 AC 的 C++ 提交请求 */
    private static JudgeRequests.SubmitRequest buildSubmitRequest() {
        JudgeRequests.SubmitRequest request = new JudgeRequests.SubmitRequest();
        request.setProblemId(PROBLEM_ID);
        request.setAssignmentId(0L);
        request.setLanguage("cpp");
        request.setCode("""
                #include <iostream>
                int main() {
                    long long a, b;
                    if (!(std::cin >> a >> b)) return 0;
                    std::cout << a + b << "\\n";
                    return 0;
                }
                """);
        return request;
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

    /** 睡眠（吞中断） */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
