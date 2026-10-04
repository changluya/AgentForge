package com.changlu.agentforge.studio.web;

import com.changlu.agentforge.agent.ReActAgent;
import com.changlu.agentforge.agent.domain.AgentRequest;
import com.changlu.agentforge.studio.stream.StudioStreamingMiddleware;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin
public class ChatController {

    private final ReActAgent agent;
    private final StudioStreamingMiddleware streamingMiddleware;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatController(ReActAgent agent, StudioStreamingMiddleware streamingMiddleware) {
        this.agent = agent;
        this.streamingMiddleware = streamingMiddleware;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatStreamRequest body) {
        if (body.getMessage() == null || body.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        final String sessionId =
                body.getSessionId() == null || body.getSessionId().trim().isEmpty()
                        ? UUID.randomUUID().toString()
                        : body.getSessionId().trim();
        final SseEmitter emitter = new SseEmitter(0L);
        streamingMiddleware.bind(sessionId, emitter);
        emitter.onCompletion(() -> streamingMiddleware.unbind(sessionId));
        emitter.onTimeout(() -> streamingMiddleware.unbind(sessionId));

        executor.execute(
                () -> {
                    try {
                        // 事件全部由 StudioStreamingMiddleware 在 Agent 生命周期回调中发送
                        agent.runStream(
                                        AgentRequest.builder()
                                                .memoryId(sessionId)
                                                .question(body.getMessage())
                                                .build())
                                .ignoreErrors()
                                .start();
                    } catch (RuntimeException e) {
                        streamingMiddleware.onError(sessionId, e);
                    }
                });
        return emitter;
    }

    @PostMapping("/stop")
    public void stop(@RequestParam String sessionId) {
        agent.cancel(sessionId);
    }
}
