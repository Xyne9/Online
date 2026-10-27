package com.oj.common.web.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：只开分页拦截器，**刻意不开乐观锁 / 逻辑删除拦截器**。
 *
 * <p><b>为什么不开逻辑删除拦截器：</b>本项目各表的软删列语义并不统一
 * （{@code prob_question.deleted_at} 是软删，{@code judge_submission} 根本没有该列），
 * 全局拦截器会往所有 SQL 上无条件追加 {@code deleted_at IS NULL}，
 * 一旦某张表没有该列就直接 SQL 报错。软删条件一律在 Mapper 的 SQL 里显式写。</p>
 *
 * <p><b>为什么不开乐观锁拦截器：</b>契约要求乐观锁冲突返回 409（而不是静默覆盖），
 * 用 {@code WHERE version = #{version}} + 检查影响行数更直接，能拿到「改了 0 行」
 * 这个事实并抛 {@code BusinessException.conflict}。</p>
 *
 * @author oj-cloud
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class MybatisPlusConfig {

    /**
     * 分页插件。
     *
     * <p>{@code maxLimit} 与 {@link com.oj.common.core.result.PageResult#normalizePageSize}
     * 保持同一个上限 100——两处不一致会出现「PageResult 说 100 条、SQL 却 LIMIT 500」
     * 的诡异结果。</p>
     *
     * @return 拦截器链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        // 页码超出总页数时返回空列表而不是回到第一页：前端翻到末页后不该莫名跳回首页
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
