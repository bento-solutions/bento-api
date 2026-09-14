package com.bento.crm.identity.service;

import com.bento.crm.identity.model.GroupMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class GroupStreamService {

    private final Map<UUID, List<SseEmitter>> emittersByGroup = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID groupId) {
        // 30 minute timeout
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emittersByGroup.computeIfAbsent(groupId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> list = emittersByGroup.get(groupId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    emittersByGroup.remove(groupId);
                }
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data("connected"));
        } catch (IOException e) {
            cleanup.run();
        }

        return emitter;
    }

    public void broadcast(UUID groupId, GroupMessage message) {
        List<SseEmitter> list = emittersByGroup.get(groupId);
        if (list == null || list.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("message").data(message));
            } catch (Exception e) {
                log.debug("SSE emitter send failed, cleaning up", e);
                list.remove(emitter);
            }
        }
    }
}
