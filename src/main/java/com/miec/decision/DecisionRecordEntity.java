
package com.miec.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad JPA que representa una decisión registrada en MIEC.
 *
 * <p>Corresponde a MIEC-F03 ENT-11 y a la tabla
 * {@code decision_record}, definida en Flyway V001.
 *
 * <p>La migración V002 refuerza la integridad referencial:
 * cuando la decisión referencia una evaluación, ambas
 * deben pertenecer al mismo ciclo (IR-006).
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Conservar la identificación de la decisión.</li>
 *   <li>Registrar el ciclo y la evaluación relacionada.</li>
 *   <li>Almacenar recomendaciones y alternativas.</li>
 *   <li>Conservar la justificación y restricciones de riesgo.</li>
 *   <li>Registrar el estado de autorización y transición.</li>
 *   <li>Mantener la trazabilidad temporal y de reglas.</li>
 * </ul>
 *
 * <p>La entidad registra decisiones, pero no autoriza
 * operaciones ni ejecuta transiciones por sí misma.
 * Estas funciones pertenecen al dominio MIEC.
 *
 * <p>Las referencias de evaluación se representan mediante
 * UUID. Su existencia y pertenencia al ciclo son comprobadas
 * por las restricciones de la base de datos.
 */
@Entity
@Table(
        name = "decision_record",
        indexes = {
                @Index(
                        name = "idx_decision_cycle",
                        columnList = "cycle_id"
                )
        }
)
public class DecisionRecordEntity {

    /** Identificador único de la decisión. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "decision_id",
            nullable = false,
            updatable = false
    )
    private UUID decisionId;

    /** Identificador obligatorio del ciclo. */
    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    /** Evaluación asociada, opcional. */
    @Column(name = "evaluation_id")
    private UUID evaluationId;

    /** Tipo de decisión del dominio MIEC. */
    @Column(
            name = "decision_type",
            nullable = false,
            length = 50
    )
    private String decisionType;

    /** Recomendación emitida. */
    @Column(name = "recommendation", length = 100)
    private String recommendation;

    /** Alternativas consideradas, representadas en JSON. */
    @Column(
            name = "alternatives_considered",
            columnDefinition = "JSON"
    )
    private String alternativesConsidered;

    /** Justificación de la selección. */
    @Column(
            name = "selection_rationale",
            columnDefinition = "TEXT"
    )
    private String selectionRationale;

    /** Restricciones y riesgos registrados en JSON. */
    @Column(
            name = "risk_constraints",
            columnDefinition = "JSON"
    )
    private String riskConstraints;

    /** Estado informativo de autorización. */
    @Column(name = "authorization_status", length = 50)
    private String authorizationStatus;

    /** Transición propuesta, representada en JSON. */
    @Column(
            name = "proposed_transition",
            columnDefinition = "JSON"
    )
    private String proposedTransition;

    /** Referencia opcional a una transición efectuada. */
    @Column(name = "actual_transition_id")
    private UUID actualTransitionId;

    /** Estado obligatorio del registro de decisión. */
    @Column(
            name = "decision_status",
            nullable = false,
            length = 50
    )
    private String decisionStatus;

    /** Identificador de la versión de reglas utilizada. */
    @Column(name = "decision_rule_version_id", length = 100)
    private String decisionRuleVersionId;

    /** Fecha de creación del registro. */
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    /**
     * Constructor protegido requerido por JPA.
     */
    protected DecisionRecordEntity() {
    }

    /**
     * Construye un registro de decisión.
     *
     * @param cycleId identificador obligatorio del ciclo
     * @param decisionType tipo obligatorio de decisión
     * @param decisionStatus estado obligatorio de decisión
     * @throws NullPointerException si un valor es nulo
     * @throws IllegalArgumentException si los textos son
     *         vacíos o exceden sus longitudes permitidas
     */
    public DecisionRecordEntity(
            UUID cycleId,
            String decisionType,
            String decisionStatus
    ) {
        this.cycleId = Objects.requireNonNull(
                cycleId,
                "El identificador del ciclo es obligatorio"
        );
        this.decisionType = requireText(
                decisionType, "decisionType", 50
        );
        this.decisionStatus = requireText(
                decisionStatus, "decisionStatus", 50
        );
        this.createdAt = Instant.now();
    }

    /**
     * Valida y normaliza un texto obligatorio.
     *
     * @param value valor recibido
     * @param field nombre del atributo
     * @param maxLength longitud máxima permitida
     * @return texto normalizado
     */
    private static String requireText(
            String value,
            String field,
            int maxLength
    ) {
        Objects.requireNonNull(
                value, field + " es obligatorio"
        );

        String normalized = value.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " no puede estar vacío"
            );
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " supera " + maxLength + " caracteres"
            );
        }

        return normalized;
    }

    /**
     * Valida un texto opcional y elimina espacios externos.
     *
     * @param value valor recibido
     * @param field nombre del atributo
     * @param maxLength longitud máxima permitida
     * @return texto normalizado o null
     */
    private static String optionalText(
            String value,
            String field,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " supera " + maxLength + " caracteres"
            );
        }

        return normalized;
    }

    /**
     * Verifica las invariantes obligatorias antes
     * de insertar la entidad mediante JPA.
     *
     * <p>El control de pertenencia de {@code evaluation_id}
     * al mismo ciclo se realiza mediante Flyway V002.
     */
    @PrePersist
    void onCreate() {
        Objects.requireNonNull(
                cycleId,
                "El ciclo es obligatorio"
        );

        decisionType = requireText(
                decisionType, "decisionType", 50
        );

        decisionStatus = requireText(
                decisionStatus, "decisionStatus", 50
        );

        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Obtiene el identificador de decisión.
     *
     * @return UUID o null antes de persistir
     */
    public UUID getDecisionId() {
        return decisionId;
    }

    /**
     * Obtiene el ciclo propietario.
     *
     * @return identificador del ciclo
     */
    public UUID getCycleId() {
        return cycleId;
    }

    /**
     * Obtiene la evaluación asociada.
     *
     * @return identificador de evaluación o null
     */
    public UUID getEvaluationId() {
        return evaluationId;
    }

    /**
     * Obtiene el tipo de decisión.
     *
     * @return tipo registrado
     */
    public String getDecisionType() {
        return decisionType;
    }

    /**
     * Obtiene la recomendación.
     *
     * @return recomendación o null
     */
    public String getRecommendation() {
        return recommendation;
    }

    /**
     * Obtiene las alternativas en formato JSON.
     *
     * @return texto JSON o null
     */
    public String getAlternativesConsidered() {
        return alternativesConsidered;
    }

    /**
     * Obtiene la justificación.
     *
     * @return justificación o null
     */
    public String getSelectionRationale() {
        return selectionRationale;
    }

    /**
     * Obtiene las restricciones de riesgo.
     *
     * @return texto JSON o null
     */
    public String getRiskConstraints() {
        return riskConstraints;
    }

    /**
     * Obtiene el estado informativo de autorización.
     *
     * @return estado o null
     */
    public String getAuthorizationStatus() {
        return authorizationStatus;
    }

    /**
     * Obtiene la transición propuesta.
     *
     * @return texto JSON o null
     */
    public String getProposedTransition() {
        return proposedTransition;
    }

    /**
     * Obtiene la transición efectuada.
     *
     * @return identificador o null
     */
    public UUID getActualTransitionId() {
        return actualTransitionId;
    }

    /**
     * Obtiene el estado de decisión.
     *
     * @return estado obligatorio
     */
    public String getDecisionStatus() {
        return decisionStatus;
    }

    /**
     * Obtiene la versión de reglas de decisión.
     *
     * @return referencia o null
     */
    public String getDecisionRuleVersionId() {
        return decisionRuleVersionId;
    }

    /**
     * Obtiene el instante de creación.
     *
     * @return instante de creación o null antes de insertar
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Establece la evaluación asociada.
     *
     * <p>La asociación puede ser nula. Cuando se informa
     * un UUID, V002 comprobará que la evaluación exista
     * y pertenezca al mismo ciclo.
     *
     * @param evaluationId identificador opcional
     * @return esta entidad
     */
    public DecisionRecordEntity evaluationId(UUID evaluationId) {
        this.evaluationId = evaluationId;
        return this;
    }

    /**
     * Establece la recomendación.
     *
     * @param recommendation texto opcional
     * @return esta entidad
     */
    public DecisionRecordEntity recommendation(
            String recommendation
    ) {
        this.recommendation = optionalText(
                recommendation, "recommendation", 100
        );
        return this;
    }

    /**
     * Establece las alternativas consideradas.
     *
     * <p>El valor debe contener JSON válido cuando no
     * sea nulo. La validez del documento y su persistencia
     * se comprobarán en las pruebas de integración.
     *
     * @param alternativesConsidered documento JSON opcional
     * @return esta entidad
     */
    public DecisionRecordEntity alternativesConsidered(
            String alternativesConsidered
    ) {
        this.alternativesConsidered = alternativesConsidered;
        return this;
    }

    /**
     * Establece la justificación de selección.
     *
     * @param selectionRationale texto opcional
     * @return esta entidad
     */
    public DecisionRecordEntity selectionRationale(
            String selectionRationale
    ) {
        this.selectionRationale = selectionRationale;
        return this;
    }

    /**
     * Establece las restricciones de riesgo.
     *
     * @param riskConstraints documento JSON opcional
     * @return esta entidad
     */
    public DecisionRecordEntity riskConstraints(
            String riskConstraints
    ) {
        this.riskConstraints = riskConstraints;
        return this;
    }

    /**
     * Establece el estado informativo de autorización.
     *
     * <p>Este atributo no constituye por sí solo
     * una autorización operativa.
     *
     * @param authorizationStatus estado opcional
     * @return esta entidad
     */
    public DecisionRecordEntity authorizationStatus(
            String authorizationStatus
    ) {
        this.authorizationStatus = optionalText(
                authorizationStatus, "authorizationStatus", 50
        );
        return this;
    }

    /**
     * Establece una transición propuesta.
     *
     * @param proposedTransition documento JSON opcional
     * @return esta entidad
     */
    public DecisionRecordEntity proposedTransition(
            String proposedTransition
    ) {
        this.proposedTransition = proposedTransition;
        return this;
    }

    /**
     * Registra la referencia a la transición efectuada.
     *
     * <p>No ejecuta la transición ni modifica el estado
     * del ciclo.
     *
     * @param actualTransitionId UUID opcional
     * @return esta entidad
     */
    public DecisionRecordEntity actualTransitionId(
            UUID actualTransitionId
    ) {
        this.actualTransitionId = actualTransitionId;
        return this;
    }

    /**
     * Establece la versión de las reglas aplicadas.
     *
     * @param decisionRuleVersionId referencia opcional
     * @return esta entidad
     */
    public DecisionRecordEntity decisionRuleVersionId(
            String decisionRuleVersionId
    ) {
        this.decisionRuleVersionId = optionalText(
                decisionRuleVersionId, "decisionRuleVersionId", 100
        );
        return this;
    }
}
