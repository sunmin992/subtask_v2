package org.hanbat.ses.persistence.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 서브태스크 템플릿. (id, version) 복합키로 버전을 함께 보관한다. */
@Entity
@Table(name = "subtask_template")
@IdClass(SubtaskTemplateEntity.Key.class)
public class SubtaskTemplateEntity {

    /**
     * 복합키. 템플릿을 고쳐도 이전 버전으로 만든 세션이 계속 살아 있어야 한다.
     *
     * <p>record 로 쓰지 않는다. Hibernate 는 IdClass 인스턴스를 기본 생성자로 만든 뒤
     * 필드에 값을 넣는데, record 의 필드는 final 이라 그 경로가 막힌다.
     */
    public static class Key implements Serializable {

        private String id;
        private String version;

        public Key() {
        }

        public Key(String id, String version) {
            this.id = id;
            this.version = version;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(id, k.id)
                    && Objects.equals(version, k.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, version);
        }

        @Override
        public String toString() {
            return id + "@" + version;
        }
    }

    @Id
    private String id;

    @Id
    private String version;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private SubtaskTemplate spec;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SubtaskTemplateEntity() {
    }

    public SubtaskTemplateEntity(SubtaskTemplate template) {
        this.id = template.id();
        this.version = template.version();
        this.name = template.name();
        this.spec = template;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public SubtaskTemplate getSpec() {
        return spec;
    }

    public void setSpec(SubtaskTemplate spec) {
        this.spec = spec;
        this.name = spec.name();
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
