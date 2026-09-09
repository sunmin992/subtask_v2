package org.hanbat.ses.api.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.hanbat.ses.api.dto.AnswerRequest;
import org.hanbat.ses.api.dto.CreateSessionRequest;
import org.hanbat.ses.api.dto.ScenarioResponse;
import org.hanbat.ses.api.dto.SesSnapshotNode;
import org.hanbat.ses.api.dto.SessionResponse;
import org.hanbat.ses.api.service.SessionFacade;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionFacade facade;

    public SessionController(SessionFacade facade) {
        this.facade = facade;
    }

    /**
     * 요청문으로 세션을 열고 라우팅한 뒤 첫 질문을 낸다.
     *
     * <p>라우팅 근거가 부족하면 세션을 만들지 않고 200 과 함께 후보를 돌려준다(FR-104).
     * 201 은 세션이 실제로 생겼을 때만 쓴다 — 생기지 않은 자원을 Created 라고 하면
     * 클라이언트가 sessionId 를 기대하고 null 을 받는다.
     */
    @PostMapping
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest body) {
        SessionResponse response = facade.create(body.request(), body.templateId(), body.usage());
        HttpStatus status = response.sessionId() == null
                ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable UUID id) {
        return facade.get(id);
    }

    /** 답변 제출 -> 다음 질문 또는 완료. */
    @PostMapping("/{id}/answers")
    public SessionResponse answer(@PathVariable UUID id, @Valid @RequestBody AnswerRequest body) {
        return facade.answer(id, body.answers());
    }

    @PostMapping("/{id}/undo")
    public SessionResponse undo(@PathVariable UUID id) {
        return facade.undo(id);
    }

    /** 현재 pruning 상태 트리 — 시각화용. */
    @GetMapping("/{id}/ses")
    public SesSnapshotNode ses(@PathVariable UUID id) {
        return facade.snapshot(id);
    }

    /** 완료 조건을 충족한 세션에서 시나리오를 확정한다. */
    @PostMapping("/{id}/scenario")
    public ResponseEntity<ScenarioResponse> scenario(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(facade.createScenario(id));
    }
}
