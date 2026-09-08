package org.hanbat.ses.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hanbat.ses.core.model.SesNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 도메인 SES 정의. 여러 템플릿이 같은 정의를 공유한다. */
@Entity
@Table(name = "ses_definition")
public class SesDefinitionEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String domain;

    @Column(nullable = false)
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private SesNode tree;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SesDefinitionEntity() {
    }

    public SesDefinitionEntity(String id, String domain, String version, SesNode tree) {
        this.id = id;
        this.domain = domain;
        this.version = version;
        this.tree = tree;
    }

    public String getId() {
        return id;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public SesNode getTree() {
        return tree;
    }

    public void setTree(SesNode tree) {
        this.tree = tree;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
