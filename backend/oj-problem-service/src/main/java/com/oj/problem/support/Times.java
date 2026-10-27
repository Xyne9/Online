package com.oj.problem.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 时间换算：DB 的 {@code datetime}（无时区墙钟）→ 契约要求的 epoch 毫秒。
 *
 * <p><b>为什么固定用 Asia/Shanghai 而不是 {@code ZoneId.systemDefault()}：</b>
 * 数据库连接串已声明 {@code serverTimezone=Asia/Shanghai}，库里存的就是北京时间的墙钟值。
 * 若跟随 JVM 默认时区，一旦部署到 UTC 容器（docker 默认就是 UTC），
 * 同一行 {@code update_time} 会算出差 8 小时的时间戳，前端"最近更新"直接错乱。
 * 显式写死时区，行为与运行环境无关。</p>
 *
 * <p>契约依据：04 §2.1(4)「所有时刻字段以 At 结尾，统一传输 epoch 毫秒 long」。</p>
 *
 * @author oj-cloud
 */
public final class Times {

    /** 数据库 datetime 列所用的时区（与 datasource 的 serverTimezone 保持一致） */
    public static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private Times() {
    }

    /**
     * DB datetime → epoch 毫秒。
     *
     * @param dateTime 数据库墙钟时间，可为 null
     * @return epoch 毫秒；入参为 null 时返回 null（前端用 === null 判空态）
     */
    public static Long toEpochMs(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(DB_ZONE).toInstant().toEpochMilli();
    }

    /** 当前时刻（DB 时区的墙钟），用于「本周新增」这类按库内时间比较的场景 */
    public static LocalDateTime nowInDbZone() {
        return LocalDateTime.now(DB_ZONE);
    }
}
