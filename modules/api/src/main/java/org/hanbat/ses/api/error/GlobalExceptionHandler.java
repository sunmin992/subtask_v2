package org.hanbat.ses.api.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hanbat.ses.api.service.SessionNotReadyException;
import org.hanbat.ses.core.pes.IncompleteSesException;
import org.hanbat.ses.core.prune.PruningException;
import org.hanbat.ses.dialogue.control.NoMatchingTemplateException;
import org.hanbat.ses.dialogue.session.SessionNotFoundException;
import org.hanbat.ses.llm.gateway.LlmParseException;
import org.hanbat.ses.llm.gateway.LlmUnavailableException;
import org.hanbat.ses.scenario.factory.UnknownModelException;
import org.hanbat.ses.scenario.model.RunNotFoundException;
import org.hanbat.ses.scenario.model.ScenarioNotFoundException;
import org.hanbat.ses.template.registry.SesDefinitionNotFoundException;
import org.hanbat.ses.template.registry.TemplateNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예외를 HTTP 로 옮긴다.
 *
 * <p>사용자 입력 문제(400/409)와 서버 문제(500)를 확실히 갈라 놓는 것이 목적이다.
 * "선택지에 없는 값" 같은 것이 500 으로 나가면 클라이언트는 재시도해야 할지
 * 사용자에게 되물어야 할지 알 수 없다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({SessionNotFoundException.class, ScenarioNotFoundException.class,
            RunNotFoundException.class, TemplateNotFoundException.class,
            SesDefinitionNotFoundException.class})
    public ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler({PruningException.class, IncompleteSesException.class,
            NoMatchingTemplateException.class, UnknownModelException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getMessage());
    }

    @ExceptionHandler(SessionNotReadyException.class)
    public ResponseEntity<Map<String, Object>> conflict(SessionNotReadyException e) {
        return body(HttpStatus.CONFLICT, "SESSION_NOT_READY", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("요청 형식이 올바르지 않습니다.");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    /**
     * LLM 장애는 502 로 낸다.
     *
     * <p>여기까지 예외가 올라왔다는 것은 폴백이 없는 경로라는 뜻이다.
     * 서버 버그(500)와 구분해 두어야 어느 쪽을 고쳐야 할지 로그만 보고 판단할 수 있다.
     */
    @ExceptionHandler({LlmUnavailableException.class, LlmParseException.class})
    public ResponseEntity<Map<String, Object>> llm(RuntimeException e) {
        log.warn("LLM 경로에서 폴백 없이 실패했습니다", e);
        return body(HttpStatus.BAD_GATEWAY, "LLM_ERROR", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "서버에서 예기치 못한 오류가 발생했습니다.");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("status", status.value());
        payload.put("code", code);
        payload.put("message", message);
        return ResponseEntity.status(status).body(payload);
    }
}
