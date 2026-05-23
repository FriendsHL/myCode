package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.List;

/**
 * EVAL-DATASET-LAYER V1 (V110): a named, owner-scoped collection of
 * {@link EvalScenarioEntity} grouped under versioned {@link EvalDatasetVersionEntity}
 * snapshots. Mirrors Langfuse's {@code Dataset} concept.
 *
 * <p>{@link #agentId} is optional — {@code null} means the dataset is
 * cross-agent (a generic baseline anchor that any agent can be evaluated
 * against).
 *
 * <p>KNOWN TECH DEBT (r3 reviewer finding 处置):
 * {@code agentId} is {@code VARCHAR(36)} to stay consistent with the
 * existing {@code EvalScenarioEntity.agentId} convention. The matching
 * {@code AgentEntity.id} is {@code BIGINT}; the project's other code paths
 * cope by casting on the JPQL side. V1 does not unify the ID type — a future
 * "agent ID type unification" needs to coordinate across all consumers
 * (EvalScenarioEntity, TraceScenarioImportService, EvalOrchestrator, etc.).
 */
@Entity
@Table(name = "t_eval_dataset")
@EntityListeners(AuditingEntityListener.class)
public class EvalDatasetEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "agent_id", length = 36)
    private String agentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> tags;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public EvalDatasetEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
