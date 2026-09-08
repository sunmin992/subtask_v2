package org.hanbat.ses.persistence.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hanbat.ses.core.pes.PesNode;
import org.hanbat.ses.template.model.OutputSpec;
import org.hanbat.ses.template.model.SimulatorConfig;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "scenario")
public class ScenarioEntity {

    @Id
    @Column(name = "scenario_id")
    private UUID scenarioId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "template_id", nullable = false)
    private String templateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private PesNode pes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> params;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sim_config", columnDefinition = "jsonb", nullable = false)
    private SimulatorConfig simConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_spec", columnDefinition = "jsonb", nullable = false)
    private OutputSpec output;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ScenarioEntity() {
    }

    public ScenarioEntity(UUID scenarioId) {
        this.scenarioId = scenarioId;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public PesNode getPes() {
        return pes;
    }

    public void setPes(PesNode pes) {
        this.pes = pes;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public SimulatorConfig getSimConfig() {
        return simConfig;
    }

    public void setSimConfig(SimulatorConfig simConfig) {
        this.simConfig = simConfig;
    }

    public OutputSpec getOutput() {
        return output;
    }

    public void setOutput(OutputSpec output) {
        this.output = output;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
