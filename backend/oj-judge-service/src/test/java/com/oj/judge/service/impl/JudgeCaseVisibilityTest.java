package com.oj.judge.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐测试点「期望/实际输出」下发口径的回归测试。
 *
 * <p>背景：判题有两个消费方，脱敏要求相反，共用一个组装方法时必须靠参数区分。</p>
 * <ul>
 *   <li>{@code #21 GET /status/{id}} —— 工作台判分链。学生本人正在调这道题，
 *       点开测试点必须看到真实的输入 / 期望 / 实际，否则《6 步手册》第 3 步的
 *       验收标志（「点第 3 格能看到真实的输入输出对比」）无法达成。</li>
 *   <li>{@code #23 GET /submissions/{id}} —— 历史回看。隐藏点按契约只给输入预览，
 *       期望/实际输出必须为 null，防止把隐藏用例答案摊开。</li>
 * </ul>
 *
 * <p>这条规则曾以「两处各写一份」的形式存在（状态接口干脆一个预览字段都不给），
 * 结果两个页面各错一半。现在收敛到一个函数，这里锁住它的四种组合。</p>
 */
class JudgeCaseVisibilityTest {

    @Test
    @DisplayName("判题状态（不遮）：公开点与隐藏点都下发输出")
    void statusEndpointShowsAllOutputs() {
        assertTrue(JudgeServiceImpl.showCaseOutputs(false, true),
                "公开测试点当然要下发");
        assertTrue(JudgeServiceImpl.showCaseOutputs(false, false),
                "工作台是本人刚提交的判题结果，隐藏点也必须有输出，否则点开只有「（隐藏）」");
    }

    @Test
    @DisplayName("提交详情（遮）：公开点下发，隐藏点屏蔽")
    void detailEndpointMasksHiddenOutputs() {
        assertTrue(JudgeServiceImpl.showCaseOutputs(true, true),
                "公开样例在详情里也应可见");
        assertFalse(JudgeServiceImpl.showCaseOutputs(true, false),
                "隐藏用例的期望/实际输出不得出现在历史详情里（防泄题）");
    }
}
