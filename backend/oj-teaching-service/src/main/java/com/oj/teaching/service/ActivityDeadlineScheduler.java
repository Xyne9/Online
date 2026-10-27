package com.oj.teaching.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 即使学生关闭浏览器，考试截止仍由服务端封卷；失败留在开放状态并重试收卷。 */
@Component
public class ActivityDeadlineScheduler {
    private static final Logger log=LoggerFactory.getLogger(ActivityDeadlineScheduler.class);
    private final ActivitySupport support;
    private final ExamService exams;
    public ActivityDeadlineScheduler(ActivitySupport support,ExamService exams) { this.support=support; this.exams=exams; }
    @Scheduled(fixedDelay=1000,initialDelay=5000)
    public void expire() {
        support.mapper.activate(ActivitySupport.now());
        for(Long id:support.mapper.expired(ActivitySupport.now())) {
            try { exams.expire(id); }
            catch(Exception ex) { log.warn("自动收卷尚未完成，稍后重试：activityId={} reason={}",id,ex.getMessage()); }
        }
    }
}

