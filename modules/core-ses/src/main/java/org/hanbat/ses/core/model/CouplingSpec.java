package org.hanbat.ses.core.model;

/** 포트 연결. 엔티티 id 기준으로 표기하며 PES 빌드 시 실제 인스턴스로 해석된다. */
public record CouplingSpec(
        CouplingKind kind,
        String fromEntity, String fromPort,
        String toEntity, String toPort
) {
    public static CouplingSpec ic(String fromEntity, String fromPort, String toEntity, String toPort) {
        return new CouplingSpec(CouplingKind.IC, fromEntity, fromPort, toEntity, toPort);
    }
}
