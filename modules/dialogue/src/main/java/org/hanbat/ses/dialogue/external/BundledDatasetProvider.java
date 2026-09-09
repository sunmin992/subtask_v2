package org.hanbat.ses.dialogue.external;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 3단 — 서버에 함께 실린 통계 데이터셋.
 *
 * <p>도메인 SES 와 같은 원칙을 따른다. 값은 자바 코드가 아니라 {@code external-data/} 아래
 * JSON 이고, 새 도메인의 참고값을 넣는 데 코드 수정이 필요 없다. 이 단이 있어야
 * 3단 폴백이 <b>반드시 답을 내는 구조</b>가 된다 — 네트워크가 없어도, 캐시가 비어 있어도
 * 도메인이 아는 대표값은 남는다.
 *
 * <pre>
 * {
 *   "datasetId": "evcharge-public-2026H1",
 *   "domain": "evcharge",
 *   "observedAt": "2026-06-30T00:00:00Z",
 *   "note": "공개 충전 통계 요약",
 *   "values": {
 *     "급속기.평균충전시간": { "value": 34.0, "unit": "분" }
 *   }
 * }
 * </pre>
 *
 * <p>열쇠는 템플릿 슬롯 이름을 먼저 보고, 없으면 SES 변수 이름을 본다. 슬롯 이름은
 * 템플릿마다 다를 수 있지만 변수 이름은 도메인이 정하므로, 두 단계를 두면 같은 데이터셋을
 * 여러 템플릿이 나눠 쓸 수 있다.
 */
@Component
public class BundledDatasetProvider implements ExternalDataProvider {

    private static final Logger log = LoggerFactory.getLogger(BundledDatasetProvider.class);

    private static final String PATTERN = "classpath*:external-data/*.dataset.json";

    /** 도메인 -> 데이터셋 목록. 한 도메인에 여러 파일을 둘 수 있다. */
    private final Map<String, List<Dataset>> byDomain;

    public BundledDatasetProvider(ObjectMapper mapper) {
        this.byDomain = load(mapper);
    }

    @Override
    public String sourceId() {
        return "bundled-dataset";
    }

    @Override
    public SourceTier tier() {
        return SourceTier.BUNDLED;
    }

    @Override
    public boolean available() {
        return !byDomain.isEmpty();
    }

    @Override
    public Optional<ExternalValue> lookup(ExternalQuery query) {
        for (Dataset dataset : byDomain.getOrDefault(query.domain(), List.of())) {
            Entry entry = dataset.find(query.slotName(), query.varName());
            if (entry == null || entry.value() == null) {
                continue;
            }
            return Optional.of(new ExternalValue(entry.value(), entry.unit(),
                    dataset.datasetId(), SourceTier.BUNDLED, dataset.observedAt(),
                    dataset.note()));
        }
        return Optional.empty();
    }

    private static Map<String, List<Dataset>> load(ObjectMapper mapper) {
        Map<String, List<Dataset>> out = new LinkedHashMap<>();
        Resource[] found;
        try {
            found = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        } catch (IOException e) {
            log.warn("내장 데이터셋을 훑는 데 실패했습니다. 3단 폴백 없이 진행합니다: {}", e.getMessage());
            return Map.of();
        }
        // 파일 이름 순으로 고정한다 — 같은 열쇠가 두 파일에 있으면 앞 파일이 이긴다.
        List<Resource> sorted = new ArrayList<>(List.of(found));
        sorted.sort((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));

        for (Resource r : sorted) {
            try (InputStream in = r.getInputStream()) {
                Dataset dataset = mapper.readValue(in, Dataset.class);
                if (dataset.domain() == null || dataset.values() == null) {
                    log.warn("도메인 또는 값이 없는 데이터셋을 건너뜁니다: {}", r.getFilename());
                    continue;
                }
                out.computeIfAbsent(dataset.domain(), k -> new ArrayList<>()).add(dataset);
                log.info("내장 데이터셋을 읽었습니다: {} (도메인 {}, 항목 {}건)",
                        dataset.datasetId(), dataset.domain(), dataset.values().size());
            } catch (IOException e) {
                // 데이터셋 하나가 깨졌다고 서버를 세우지 않는다 — 이건 폴백의 마지막 단이지
                // 시스템의 전제가 아니다. 도메인 SES 와 다루는 방식이 다른 이유가 여기 있다.
                log.warn("데이터셋을 읽지 못해 건너뜁니다: {} ({})", r.getFilename(), e.getMessage());
            }
        }
        return Map.copyOf(out);
    }

    /** 데이터셋 파일 하나. */
    private record Dataset(String datasetId, String domain, Instant observedAt, String note,
                           Map<String, Entry> values) {

        Entry find(String slotName, String varName) {
            Entry bySlot = slotName == null ? null : values.get(slotName);
            if (bySlot != null) {
                return bySlot;
            }
            return varName == null ? null : values.get(varName);
        }
    }

    private record Entry(Object value, String unit) {
    }
}
