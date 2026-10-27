package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 流只保存连接；可回放事实始终来自 exam_monitor_event。 */
@Service
public class MonitorStreamService {
    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    private static class Client {
        final Long examId,userId;
        final SseEmitter emitter;
        final Supplier<Map<String,Object>> summary;
        long summaryAt=System.currentTimeMillis(),pingAt=summaryAt;
        Client(Long examId,Long userId,SseEmitter emitter,Supplier<Map<String,Object>> summary) {
            this.examId=examId; this.userId=userId; this.emitter=emitter; this.summary=summary;
        }
    }
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    public static class Scheduling { }

    public synchronized SseEmitter connect(Long examId,Long userId,Supplier<Map<String,Object>> summary) {
        if(clients.values().stream().filter(c->c.userId.equals(userId)).count()>=2) throw BusinessException.tooManyRequests("同一用户最多同时连接两条监考流");
        SseEmitter emitter=new SseEmitter(0L); String key=UUID.randomUUID().toString();
        clients.put(key,new Client(examId,userId,emitter,summary));
        Runnable cleanup=()->clients.remove(key);
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(e->cleanup.run());
        return emitter;
    }
    public void send(SseEmitter emitter,String event,String id,Object data) {
        try { synchronized(emitter) { var frame=SseEmitter.event().name(event).data(data); if(id!=null) frame.id(id); emitter.send(frame); } }
        catch(IOException | IllegalStateException ex) { emitter.completeWithError(ex); clients.entrySet().removeIf(e->e.getValue().emitter==emitter); }
    }
    public void publish(Long examId,String event,String id,Object data) {
        clients.values().stream().filter(c->c.examId.equals(examId)).forEach(c->send(c.emitter,event,id,data));
    }
    @Scheduled(fixedDelay=1000)
    public void tick() {
        long now=System.currentTimeMillis();
        for(Client c:clients.values()) {
            try {
                if(now-c.summaryAt>=10000) { send(c.emitter,"summary",null,c.summary.get()); c.summaryAt=now; }
                if(now-c.pingAt>=15000) { synchronized(c.emitter) { c.emitter.send(SseEmitter.event().comment("ping")); } c.pingAt=now; }
            } catch(Exception ex) { c.emitter.completeWithError(ex); clients.entrySet().removeIf(e->e.getValue()==c); }
        }
    }
}
