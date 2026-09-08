package org.hanbat.ses.core.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import org.hanbat.ses.core.axiom.SesAxiomValidator;
import org.hanbat.ses.core.json.SesJson;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.pes.PesBuildResult;
import org.hanbat.ses.core.pes.PesBuilder;
import org.hanbat.ses.core.prune.PruningEngine;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.core.resolve.SlotResolver;
import org.hanbat.ses.core.validate.SesStructureChecker;
import org.hanbat.ses.core.validate.ValidationIssue;

/**
 * Phase 1 완료 확인용 CLI — LLM 도 Spring 도 없이 SES 코어만으로 PES 를 만들어 본다.
 *
 * <pre>
 *   java -cp core-ses.jar org.hanbat.ses.core.cli.SesPruningCli ses.json answers.json
 * </pre>
 *
 * answers.json 은 {@code {"슬롯이름": 값}} 형태의 맵이며, 순서대로 적용하면서
 * 매 턴 남은 슬롯을 출력한다.
 */
public final class SesPruningCli {

    private SesPruningCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("사용법: SesPruningCli <ses.json> [answers.json]");
            System.exit(2);
        }
        SesNode ses = SesJson.readTree(Path.of(args[0]));

        List<ValidationIssue> axioms = new SesAxiomValidator().validate(ses);
        print("공리 검사", axioms);

        Map<String, Object> answers = args.length > 1
                ? SesJson.mapper().readValue(Files.readString(Path.of(args[1])),
                new TypeReference<LinkedHashMap<String, Object>>() {
                })
                : Map.of();

        SlotResolver resolver = new SlotResolver();
        PruningEngine engine = new PruningEngine();

        int turn = 0;
        List<OpenSlot> open = resolver.scan(ses);
        while (true) {
            turn++;
            System.out.println("\n== 턴 " + turn + " — 열린 슬롯 " + open.size() + "개 ==");
            open.forEach(s -> System.out.println("  - " + s.slotName()
                    + " [" + s.kind() + "] @ " + s.entityPath()
                    + (s.options().isEmpty() ? "" : " 선택지=" + s.options().stream()
                    .map(o -> o.label()).toList())));
            if (open.isEmpty()) {
                break;
            }
            boolean applied = false;
            for (OpenSlot s : open) {
                Object ans = answers.get(s.slotName());
                if (ans == null) {
                    continue;
                }
                ses = engine.apply(ses, s.anchor(), ans);
                System.out.println("  -> 적용: " + s.slotName() + " = " + ans);
                applied = true;
            }
            if (!applied) {
                System.out.println("  (답변 파일에 해당 슬롯의 값이 없어 중단합니다)");
                break;
            }
            open = resolver.scan(ses);
        }

        print("구조 무결성", new SesStructureChecker().check(ses));

        PesBuildResult result = new PesBuilder().tryBuild(ses);
        if (result.ok()) {
            System.out.println("\n== PES 확정 (" + result.pes().nodeCount() + " 노드) ==");
            System.out.println(SesJson.write(result.pes().root()));
        } else {
            print("PES 빌드", result.issues());
            System.exit(1);
        }
    }

    private static void print(String label, List<ValidationIssue> issues) {
        if (issues.isEmpty()) {
            System.out.println("[" + label + "] 통과");
            return;
        }
        System.out.println("[" + label + "] " + issues.size() + "건");
        issues.forEach(i -> System.out.println("  " + i.severity() + " " + i.code()
                + " @" + i.slot() + " : " + i.message()));
    }
}
