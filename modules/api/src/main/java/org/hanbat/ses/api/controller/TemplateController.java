package org.hanbat.ses.api.controller;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.api.dto.IssueView;
import org.hanbat.ses.api.dto.TemplateSummary;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.plan.SubtaskDagPlanner;
import org.hanbat.ses.template.registry.SesDefinition;
import org.hanbat.ses.template.registry.SesDefinitionNotFoundException;
import org.hanbat.ses.template.registry.SesRegistry;
import org.hanbat.ses.template.registry.TemplateNotFoundException;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.hanbat.ses.template.validate.TemplateConsistencyChecker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서브태스크 템플릿 조회/등록 (FR-701).
 *
 * <p>도메인 SES 와 모델 베이스는 {@code AssetController} 가 맡는다.
 *
 * <p>등록 시 정합성 검사를 강제한다. 잘못된 템플릿을 받아 두면 문제가 대화 도중에
 * "질문이 안 나온다" 같은 증상으로만 드러나고, 그때는 원인을 짚기 어렵다.
 */
@RestController
@RequestMapping("/api/v1")
public class TemplateController {

    private final TemplateRegistry templates;
    private final SesRegistry sesDefinitions;
    private final TemplateConsistencyChecker checker = new TemplateConsistencyChecker();

    public TemplateController(TemplateRegistry templates, SesRegistry sesDefinitions) {
        this.templates = templates;
        this.sesDefinitions = sesDefinitions;
    }

    @GetMapping("/templates")
    public List<TemplateSummary> list() {
        return templates.findAllActive().stream().map(TemplateSummary::of).toList();
    }

    @GetMapping("/templates/{id}")
    public SubtaskTemplate get(@PathVariable String id) {
        return templates.findActive(id).orElseThrow(() -> new TemplateNotFoundException(id));
    }

    /**
     * 서브태스크 실행 계획 (FR-103).
     *
     * <p>선행 의존을 닫아 위상 정렬한 순서를 돌려준다. 선행이 없으면 목표 하나만 나온다.
     */
    @GetMapping("/templates/{id}/plan")
    public SubtaskDagPlanner.Plan plan(@PathVariable String id) {
        templates.findActive(id).orElseThrow(() -> new TemplateNotFoundException(id));
        return new SubtaskDagPlanner(templates).plan(id);
    }

    @PostMapping("/templates")
    public ResponseEntity<Map<String, Object>> register(@RequestBody SubtaskTemplate template) {
        SesDefinition ses = sesDefinitions.find(template.binding().sesDefinitionId())
                .orElseThrow(() -> new SesDefinitionNotFoundException(
                        template.binding().sesDefinitionId()));

        List<ValidationIssue> issues = checker.check(template, ses.tree());
        List<IssueView> views = issues.stream().map(IssueView::of).toList();
        if (issues.stream().anyMatch(ValidationIssue::isError)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "템플릿이 도메인 SES 와 맞지 않습니다.",
                    "issues", views));
        }
        templates.register(template);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", template.id(), "version", template.version(), "warnings", views));
    }

}
