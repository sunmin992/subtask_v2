package org.hanbat.ses.persistence.entity;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 모델 베이스 — SES 리프가 어떤 원자 모델로 해석되는지.
 *
 * <p>impl_class 대신 modelId 문자열로 팩토리를 찾는다. FQCN 을 DB 에 두면
 * 리팩터링 한 번에 조용히 깨지고, 깨진 사실은 실행 시점에야 드러난다.
 */
@Entity
@Table(name = "model_base")
public class ModelBaseEntity {

    @Id
    @Column(name = "model_id")
    private String modelId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "display_name")
    private String displayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> ports;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_vars", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> stateVars;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> params;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ModelBaseEntity() {
    }

    public ModelBaseEntity(String modelId, String kind, String displayName,
                           Map<String, Object> ports, Map<String, Object> stateVars,
                           Map<String, Object> params) {
        this.modelId = modelId;
        this.kind = kind;
        this.displayName = displayName;
        this.ports = ports;
        this.stateVars = stateVars;
        this.params = params;
    }

    public String getModelId() {
        return modelId;
    }

    public String getKind() {
        return kind;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Map<String, Object> getPorts() {
        return ports;
    }

    public Map<String, Object> getStateVars() {
        return stateVars;
    }

    public Map<String, Object> getParams() {
        return params;
    }
}
