package com.oj.common.api.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 通道新增/更新的请求体（契约 02 §7.2.3 / §7.2.4 的入参，两处共用）。
 *
 * <p><b>为什么共用一份</b>：契约里 #75 写的是「id + 7.2.3 可选字段」，字段集合完全一致，
 * 拆成两个类只会让「PUT 少校验一个字段」这类漂移有机会发生。更新时用 {@code null} 表示
 * 「不改这一项」，因此所有字段都必须是包装类型，不能用基本类型。</p>
 *
 * <p><b>单位</b>：{@code timeoutMs} 毫秒；DB 列是 {@code timeout_seconds}，
 * 由服务层做 ×1000 / ÷1000 换算（agent.md §4.4 时间单位铁律）。</p>
 *
 * <p><b>密钥红线</b>（agent.md §2.6）：{@code apiKey} 允许三种形态——
 * 明文（仅调试用）、{@code ${ENV_VAR}}、{@code env:ENV_VAR}。写入前不改写，
 * 由 {@code SecretResolver} 在**使用侧**解析；本字段永不回显、永不进日志。</p>
 *
 * @author oj-cloud
 */
@Data
public class ChannelCommandDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 稳定通道标识，{@code ^[a-z][a-z0-9-]{2,31}$}（新增时必填，更新时不可改） */
    private String channelCode;

    /** 展示名 */
    private String channelName;

    /** 厂商：Ollama / DeepSeek / Qwen / Zhipu / OpenAI-Compatible */
    private String vendor;

    /** 协议类型：OLLAMA / OPENAI_COMPATIBLE（不传时由 vendor 推断） */
    private String providerType;

    /** 具体模型名，如 qwen2.5-coder:7b */
    private String modelName;

    /**
     * 角色绑定，取值 ROUTER / TUTOR / GUARDRAIL（大小写不敏感）。
     *
     * <p><b>创建（#74）必须非空</b>；<b>更新（#75）不传即表示「不改角色绑定」</b>。</p>
     *
     * <p><b>为什么默认值是 {@code null} 而不是 {@code new ArrayList<>()}</b>（第 6 步实测缺陷）：
     * 契约 §7.2.4 明确 #75 是<b>部分更新</b>——只传 {@code channelName} 就该成功。
     * 但默认空 List 让「没传」和「传了空数组」在服务端不可区分：
     * {@code AdminChannelService#update} 的判断是 {@code if (dto.getRoleBindings() != null)}，
     * 空 List 满足这个条件 → 走 {@code normalizeRoles} → 抛「roleBindings 不能为空」→ 400。
     * 结果就是<b>任何不含 roleBindings 的局部更新全部被拒</b>，
     * 页面「只改一个通道名」这种最常见操作直接失败。</p>
     *
     * <p>改成 {@code null} 默认后语义就分得开了：
     * 不传 = null = 不动；显式传 {@code []} = 空数组 = 由 {@code normalizeRoles} 拒绝（正确，创建/更新都不允许零角色）。</p>
     */
    private List<String> roleBindings;

    /** API 端点 */
    private String baseUrl;

    /** 密钥（明文 / ${VAR} / env:VAR）；更新时传空表示不改 */
    private String apiKey;

    /** 超时毫秒；不传按 8000 */
    private Long timeoutMs;

    /** 单次回复 Token 上限 */
    private Integer maxTokens;

    /** 采样温度 0.00~1.00 */
    private Double temperature;

    /** 最大并发 */
    private Integer maxConcurrency;

    /** 是否启用 */
    private Boolean isEnabled;
}
