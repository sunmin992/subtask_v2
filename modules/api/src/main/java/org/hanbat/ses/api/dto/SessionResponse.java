package org.hanbat.ses.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * 세션 응답.
 *
 * @param derivedFrom 왜 이 질문이 새로 나왔는지. 파생 슬롯 구조에서 이 한 줄이 없으면
 *                    사용자는 질문이 끝없이 늘어난다고 느낀다.
 * @param provenance  채워진 값이 각각 어디에서 왔는지. 사용자가 말한 값과 서버가 채운 값을
 *                    화면이 다르게 보여줄 수 있어야 자동 채움을 믿고 쓸 수 있다.
 */
public record SessionResponse(
        UUID sessionId,
        String phase,
        String outcome,
        int turn,
        List<QuestionView> questions,
        ProgressView progress,
        String derivedFrom,
        List<IssueView> issues,
        SesSnapshotNode sesSnapshot,
        UUID scenarioId,
        boolean canUndo,
        List<CandidateView> candidates,
        List<ProvenanceView> provenance
) {

    /** 라우팅이 애매할 때 사용자에게 제시하는 후보 (FR-104). */
    public record CandidateView(String templateId, String name, String intent,
                                double score, String why) {
    }
}
