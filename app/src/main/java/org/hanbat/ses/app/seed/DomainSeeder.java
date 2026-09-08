package org.hanbat.ses.app.seed;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.core.axiom.SesAxiomValidator;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.registry.ModelBaseEntry;
import org.hanbat.ses.template.registry.ModelBaseRegistry;
import org.hanbat.ses.template.registry.SesDefinition;
import org.hanbat.ses.template.registry.SesRegistry;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.hanbat.ses.template.validate.TemplateConsistencyChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 도메인 자산을 <b>데이터에서</b> 읽어 등록한다.
 *
 * <p>이전에는 리조트 도메인이 Java 코드에 있었다. 그러면 "새 도메인 추가 시 코드 수정이
 * 없어야 한다"(NFR-04)와 "도메인 지식은 코드가 아닌 데이터에 존재해야 한다"(NFR-11)를
 * 둘 다 지킬 수 없다 — 아키텍처가 그것을 지원하더라도 실제 경로가 코드였기 때문이다.
 *
 * <p>지금은 클래스패스의 {@code seed/} 아래 파일을 이름 규칙으로 찾아 등록한다.
 * <pre>
 *   seed/&#42;.ses.json        SesDefinition  {id, domain, version, tree}
 *   seed/&#42;.models.json     ModelBaseEntry 배열
 *   seed/&#42;.template.json   SubtaskTemplate
 * </pre>
 * 등록 순서가 중요하다 — 템플릿의 정합성 검사는 대상 SES 가 이미 있어야 돌 수 있다.
 *
 * <p>등록 전에 공리 검사와 템플릿-SES 정합성 검사를 돌리고, 오류가 있으면 기동을 멈춘다.
 * 시드라고 검사를 건너뛰면 정작 그 검사가 잡아야 할 오류를 기본 데이터가 들고 온다.
 */
@Component
@ConditionalOnProperty(name = "app.seed-demo-domain", havingValue = "true", matchIfMissing = true)
public class DomainSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DomainSeeder.class);

    private final SesRegistry sesRegistry;
    private final ModelBaseRegistry modelBaseRegistry;
    private final TemplateRegistry templateRegistry;
    private final ObjectMapper mapper;

    private final SesAxiomValidator axioms = new SesAxiomValidator();
    private final TemplateConsistencyChecker consistency = new TemplateConsistencyChecker();

    public DomainSeeder(SesRegistry sesRegistry, ModelBaseRegistry modelBaseRegistry,
                        TemplateRegistry templateRegistry, ObjectMapper mapper) {
        this.sesRegistry = sesRegistry;
        this.modelBaseRegistry = modelBaseRegistry;
        this.templateRegistry = templateRegistry;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        // SES -> 모델 베이스 -> 템플릿 순서로 등록한다. 템플릿 검사가 SES 를 필요로 한다.
        seedSesDefinitions();
        seedModelBases();
        seedTemplates();
    }

    private void seedSesDefinitions() throws IOException {
        for (Resource r : find("classpath*:seed/*.ses.json")) {
            SesDefinition definition = read(r, SesDefinition.class);
            List<ValidationIssue> issues = axioms.validate(definition.tree());
            failOnErrors("도메인 SES 공리(" + r.getFilename() + ")", issues);
            warn(r.getFilename(), issues);

            if (sesRegistry.find(definition.id()).isEmpty()) {
                sesRegistry.register(definition);
                log.info("도메인 SES 를 등록했습니다: {} ({})", definition.id(), r.getFilename());
            }
        }
    }

    private void seedModelBases() throws IOException {
        for (Resource r : find("classpath*:seed/*.models.json")) {
            ModelBaseEntry[] entries = read(r, ModelBaseEntry[].class);
            for (ModelBaseEntry entry : entries) {
                if (modelBaseRegistry.find(entry.modelId()).isEmpty()) {
                    modelBaseRegistry.register(entry);
                }
            }
            log.info("모델 베이스 {}건을 등록했습니다 ({})", entries.length, r.getFilename());
        }
    }

    private void seedTemplates() throws IOException {
        for (Resource r : find("classpath*:seed/*.template.json")) {
            SubtaskTemplate template = read(r, SubtaskTemplate.class);
            String sesId = template.binding().sesDefinitionId();
            SesDefinition ses = sesRegistry.find(sesId).orElseThrow(() ->
                    new IllegalStateException(r.getFilename()
                            + " 이 가리키는 도메인 SES 가 없습니다: " + sesId));

            List<ValidationIssue> issues = consistency.check(template, ses.tree());
            failOnErrors("템플릿-SES 정합성(" + r.getFilename() + ")", issues);
            warn(r.getFilename(), issues);

            if (templateRegistry.find(template.id(), template.version()).isEmpty()) {
                templateRegistry.register(template);
                log.info("서브태스크 템플릿을 등록했습니다: {} ({})",
                        template.key(), r.getFilename());
            }
        }
    }

    private Resource[] find(String pattern) throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver().getResources(pattern);
        // 파일 이름 순으로 고정한다 — 등록 순서가 달라지면 로그가 매번 다르게 보인다.
        List<Resource> sorted = new ArrayList<>(List.of(found));
        sorted.sort((a, b) -> String.valueOf(a.getFilename())
                .compareTo(String.valueOf(b.getFilename())));
        return sorted.toArray(new Resource[0]);
    }

    private <T> T read(Resource resource, Class<T> type) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, type);
        }
    }

    private void failOnErrors(String label, List<ValidationIssue> issues) {
        List<ValidationIssue> errors = issues.stream().filter(ValidationIssue::isError).toList();
        if (!errors.isEmpty()) {
            errors.forEach(i -> log.error("[{}] {} @{} : {}",
                    label, i.code(), i.slot(), i.message()));
            throw new IllegalStateException(
                    label + " 검사에서 오류 " + errors.size() + "건이 발견되어 기동을 중단합니다.");
        }
    }

    private void warn(String source, List<ValidationIssue> issues) {
        issues.stream().filter(i -> !i.isError()).forEach(i ->
                log.warn("[{}] {} @{} : {}", source, i.code(), i.slot(), i.message()));
    }
}
