package p1.controller;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LoggedOperation;
import p1.infrastructure.mdc.ChatSessionMetrics;
import p1.model.dto.ChatRequestDTO;
import p1.service.ChatService;

import java.util.List;
import java.util.Map;

import static p1.utils.ReplyUtil.segment;
import static p1.utils.SessionUtil.normalizeSessionId;

@RestController
@CrossOrigin
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionMetrics chatSessionMetrics;

    @LoggedOperation(domain = LogDomain.HTTP, operation = "chat.send")

    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody ChatRequestDTO request) {
        String sessionId = normalizeSessionId(request.getSessionId());
        request.setSessionId(sessionId);
        int currentRound = chatSessionMetrics.incrementAndGetRound(sessionId);

        MDC.put("sessionId", sessionId);
        MDC.put("chatRound", String.valueOf(currentRound));
        try {
            String rawReply = chatService.sendMsgToRpAgent(request);
            rawReply = rawReply == null ? "" : rawReply;
            List<String> replyList = segment(rawReply, request.isShortMode());
            return ResponseEntity.ok(replyList);
        } catch (Exception e) {
            return ResponseEntity
                    .status(statusFor(e))
                    .body(Map.of("message", userFacingError(e)));
        } finally {
            MDC.remove("chatRound");
            MDC.remove("sessionId");
        }
    }

    private HttpStatus statusFor(Exception e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("Authentication")) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (name.contains("Http") || name.contains("RateLimit") || name.contains("Timeout")) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String userFacingError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.toString();
        }
        if (message.contains("Authentication Fails") || message.contains("authentication")) {
            return "AI API key authentication failed. Check the API key, base URL, and model name in Settings.";
        }
        return message;
    }

}
