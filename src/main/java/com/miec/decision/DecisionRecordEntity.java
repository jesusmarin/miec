package com.miec.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad JPA que representa una decisión registrada (MIEC-F03 ENT-11).
 *
 * <p>Esta entidad conserva la recomendación o determinación emitida por el motor de decisión,
 * incluyendo las evidencias utilizadas, las reglas aplicadas y el estado de autorización.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El identificador del ciclo es obligatorio.</li>
 *   <li>El tipo de decisión es obligatorio.</li>
 *   <li>El estado de la decisión es obligatorio.</li>
 * </ul>
 *
 * @see <a href="MIEC-F03 §7.11">MIEC-F03 - Decisión</a>
 */
@Entity
@Table(
        name = "decision_record",
        indexes = {
                @Index(name = "idx_decision_cycle", columnList = "cycle_id")
        }
)
public class DecisionRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "evaluation_id")
    private UUID evaluationId;

    @Column(name = "decision_type", nullable = false, length = 50)
    private String decisionType;

    @Column(name = "recommendation", length = 100)
    private String recommendation;

    @Column(name = "alternatives_considered", columnDefinition = "JSON")
    private String alternativesConsidered;

    @Column(name = "selection_rationale", columnDefinition = "TEXT")
    private String selectionRationale;

    @Column(name = "risk_constraints", columnDefinition = "JSON")
    private String riskConstraints;

    @Column(name = "authorization_status", length = 50)
    private String authorizationStatus;

    @Column(name = "proposed_transition", columnDefinition = "JSON")
    private String proposedTransition;

    @Column(name = "actual_transition_id")
    private UUID actualTransitionId;

    @Column(name = "decision_status", nullable = false, length = 50)
    private String decisionStatus;

    @Column(name = "decision_rule_version_id", length = 100)
    private String decisionRuleVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Constructor protegido para JPA.
     */
    protected DecisionRecordEntity() {
    }

    /**
     * Constructor público para crear una nueva decisión.
     *
     * @param cycleId       identificador del ciclo asociado
     * @param decisionType  tipo de decisión (ej: "CONTINUE", "ADJUST", "SUSPEND")
     * @param decisionStatus estado de la decisión (ej: "PROPOSED", "APPROVED", "EXECUTED")
     * @throws NullPointerException si cycleId, decisionType o decisionStatus son nulos
     */
    public DecisionRecordEntity(UUID cycleId, String decisionType, String decisionStatus) {
        this.cycleId = Objects.requireNonNull(cycleId, "El identificador del ciclo es obligatorio");
        this.decisionType = Objects.requireNonNull(decisionType, "El tipo de decisión es obligatorio");
        this.decisionStatus = Objects.requireNonNull(decisionStatus, "El estado de la decisión es obligatorio");
        this.createdAt = Instant.now();
    }

    // Getters

    public UUID getDecisionId() {
        return decisionId;
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public UUID getEvaluationId() {
        return evaluationId;
    }

    public String getDecisionType() {
        return decisionType;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public String getAlternativesConsidered() {
        return alternativesConsidered;
    }

    public String getSelectionRationale() {
        return selectionRationale;
    }

    public String getRiskConstraints() {
        return riskConstraints;
    }

    public String getAuthorizationStatus() {
        return authorizationStatus;
    }

    public String getProposedTransition() {
        return proposedTransition;
    }

    public UUID getActualTransitionId() {
        return actualTransitionId;
    }

    public String getDecisionStatus() {
        return decisionStatus;
    }

    public String getDecisionRuleVersionId() {
        return decisionRuleVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // Setters para campos opcionales

    public DecisionRecordEntity evaluationId(UUID evaluationId) {
        this.evaluationId = evaluationId;
        return this;
    }

    public DecisionRecordEntity recommendation(String recommendation) {
        this.recommendation = recommendation;
        return this;
    }

    public DecisionRecordEntity alternativesConsidered(String alternativesConsidered) {
        this.alternativesConsidered = alternativesConsidered;
        return this;
    }

    public DecisionRecordEntity selectionRationale(String selectionRationale) {
        this.selectionRationale = selectionRationale;
        return this;
    }

    public DecisionRecordEntity riskConstraints(String riskConstraints) {
        this.riskConstraints = riskConstraints;
        return this;
    }

    public DecisionRecordEntity authorizationStatus(String authorizationStatus) {
        this.authorizationStatus = authorizationStatus;
        return this;
    }

    public DecisionRecordEntity proposedTransition(String proposedTransition) {
        this.proposedTransition = proposedTransition;
        return this;
    }

    public DecisionRecordEntity actualTransitionId(UUID actualTransitionId) {
        this.actualTransitionId = actualTransitionId;
        return this;
    }

    public DecisionRecordEntity decisionRuleVersionId(String decisionRuleVersionId) {
        this.decisionRuleVersionId = decisionRuleVersionId;
        return this;
    }
}