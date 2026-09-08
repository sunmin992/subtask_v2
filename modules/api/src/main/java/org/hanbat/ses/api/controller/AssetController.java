package org.hanbat.ses.api.controller;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.api.dto.IssueView;
import org.hanbat.ses.core.axiom.SesAxiomValidator;
import org.hanbat.ses.core.validate.SesStructureChecker;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.scenario.factory.ModelFactoryRegistry;
import org.hanbat.ses.template.registry.ModelBaseEntry;
import org.hanbat.ses.template.registry.ModelBaseNotFoundException;
import org.hanbat.ses.template.registry.ModelBaseRegistry;
import org.hanbat.ses.template.registry.SesDefinition;
import org.hanbat.ses.template.registry.SesDefinitionNotFoundException;
import org.hanbat.ses.template.registry.SesRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 도메인 자산 등록·조회 — 도메인 SES(FR-702)와 모델 베이스(FR-703).
 *
 * <p>등록 경로가 없으면 "새 도메인 추가 시 코드 수정이 없어야 한다"(NFR-04)를 지킬 수 없다.
 * 아키텍처가 데이터 기반이더라도 실제로 넣을 문이 없으면 도메인은 코드로 들어오게 된다.
 *
 * <p>등록은 검사를 강제한다. SES 는 공리 위반을, 모델 베이스는 실행 가능한 구현체의
 * 유무를 확인한다. 명세만 등록되고 구현이 없는 모델은 시나리오를 실행할 때
 * 정체불명의 오류로 나타나므로, 그 사실을 등록 시점에 경고로 돌려준다.
 */
@RestController
@RequestMapping("/api/v1")
public class AssetController {

    private final SesRegistry sesDefinitions;
    private final ModelBaseRegistry modelBases;
    private final ModelFactoryRegistry factories;
    private final SesAxiomValidator axioms = new SesAxiomValidator();
    private final SesStructureChecker structure = new SesStructureChecker();

    public AssetController(SesRegistry sesDefinitions, ModelBaseRegistry modelBases,
                           ModelFactoryRegistry factories) {
        this.sesDefinitions = sesDefinitions;
        this.modelBases = modelBases;
        this.factories = factories;
    }

    // ------------------------------------------------------------ 도메인 SES

    @GetMapping("/ses-definitions")
    public List<Map<String, Object>> listSes() {
        return sesDefinitions.findAll().stream()
                .map(d -> Map.<String, Object>of(
                        "id", d.id(), "domain", d.domain(), "version", d.version()))
                .toList();
    }

    @GetMapping("/ses-definitions/{id}")
    public SesDefinition getSes(@PathVariable String id) {
        return sesDefinitions.find(id)
                .orElseThrow(() -> new SesDefinitionNotFoundException(id));
    }

    /**
     * 도메인 SES 등록. 공리 위반이 있으면 거부한다.
     *
     * <p>경고는 등록을 막지 않고 응답에 실어 보낸다 — 선택지가 하나뿐인 specialization 처럼
     * 의심스럽지만 의도일 수 있는 것들이다.
     */
    @PostMapping("/ses-definitions")
    public ResponseEntity<Map<String, Object>> registerSes(@RequestBody SesDefinition definition) {
        List<ValidationIssue> issues = new java.util.ArrayList<>(axioms.validate(definition.tree()));
        // 배선 검사도 여기서 돌린다. 아직 가지치기되지 않은 트리라 미결정 축을 가리키는
        // 배선은 건너뛰지만, 고아 포트와 단위 불일치는 지금도 확정적으로 틀린 것이다.
        issues.addAll(structure.check(definition.tree()));
        List<IssueView> views = issues.stream().map(IssueView::of).toList();

        if (issues.stream().anyMatch(ValidationIssue::isError)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "공리 또는 배선 검사에 실패해 등록하지 않았습니다.",
                    "issues", views));
        }
        sesDefinitions.register(definition);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", definition.id(),
                "domain", definition.domain(),
                "version", definition.version(),
                "warnings", views));
    }

    // ------------------------------------------------------------ 모델 베이스

    /**
     * 모델 베이스 목록.
     *
     * <p>등록된 명세와 실제 구현체의 유무를 함께 보여 준다. 둘이 어긋난 항목이
     * 도메인 저작 중 가장 자주 밟는 함정이다 — SES 리프가 가리키는 모델이
     * 카탈로그에는 있는데 팩토리가 없으면 실행 직전에야 드러난다.
     */
    @GetMapping("/models")
    public List<Map<String, Object>> listModels() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (ModelBaseEntry e : modelBases.findAll()) {
            out.add(Map.of(
                    "modelId", e.modelId(),
                    "kind", e.kind(),
                    "displayName", String.valueOf(e.displayName()),
                    "ports", e.ports(),
                    "params", e.params(),
                    "requiredParams", e.requiredParams(),
                    "implemented", factories.supports(e.modelId())));
        }
        // 카탈로그에 없는데 구현체만 있는 경우도 알려 준다.
        factories.knownModels().stream()
                .filter(id -> modelBases.find(id).isEmpty())
                .forEach(id -> out.add(Map.of(
                        "modelId", id, "kind", "ATOMIC", "displayName", id,
                        "ports", Map.of(), "params", Map.of(),
                        "requiredParams", List.of(),
                        "implemented", true,
                        "note", "구현체는 있으나 모델 베이스에 명세가 등록되지 않았습니다.")));
        return out;
    }

    @GetMapping("/models/{modelId}")
    public ModelBaseEntry getModel(@PathVariable String modelId) {
        return modelBases.find(modelId)
                .orElseThrow(() -> new ModelBaseNotFoundException(modelId));
    }

    /** 모델 베이스 등록. 구현체가 없으면 경고와 함께 등록한다. */
    @PostMapping("/models")
    public ResponseEntity<Map<String, Object>> registerModel(@RequestBody ModelBaseEntry entry) {
        if (entry.modelId() == null || entry.modelId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "modelId 가 필요합니다."));
        }
        modelBases.register(entry);

        List<String> warnings = new java.util.ArrayList<>();
        if (!factories.supports(entry.modelId())) {
            warnings.add("이 modelId 에 대응하는 AtomicModelFactory 가 없습니다. "
                    + "SES 리프가 이 모델을 가리키면 시나리오 실행 시 실패합니다. "
                    + "사용 가능한 구현체: " + factories.knownModels());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "modelId", entry.modelId(),
                "implemented", factories.supports(entry.modelId()),
                "requiredParams", entry.requiredParams(),
                "warnings", warnings));
    }
}
