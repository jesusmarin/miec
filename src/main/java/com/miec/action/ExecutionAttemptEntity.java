
package com.miec.action;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MIEC-F03 IR-009: registro persistente de un intento
 * de ejecución de una acción planificada.
 *
 * <p>Una acción del plan puede tener varios intentos,
 * cada uno identificado mediante executionId.
 *
 * <p>Esta entidad representa exclusivamente el estado
 * y la trazabilidad de un intento. No concede permisos,
 * no ejecuta acciones ni decide sobre reintentos.
 *
 * <p>La autorización corresponde a IR-010 y la evaluación
 * de reintentos corresponde a ExecutionRetryPolicy.
 *
 * <p>La tabla execution_attempt será creada por V003.
 */
@Entity
@Table(name = "execution_attempt")
public class ExecutionAttemptEntity {

    /**
     * Estados permitidos de un intento.
     */
    public enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        UNKNOWN
    }

    /**
     * Resultado conocido de los efectos del intento.
     *
     * <p>La confirmación de ausencia de efectos debe
     * provenir de una verificación fiable.
     */
    public enum EffectOutcome {
        NO_EFFECT_CONFIRMED,
        EFFECT_APPLIED,
        EFFECT_UNKNOWN
    }

    /** Identificador único de este intento. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "execution_id",
            nullable = false,
            updatable = false
    )
    private UUID executionId;

    /** Acción definida en el contrato JSON del plan. */
    @Column(
            name = "planned_action_id",
            nullable = false,
            updatable = false
    )
    private UUID plannedActionId;

    /** Plan de intervención propietario. */
    @Column(
            name = "plan_id",
            nullable = false,
            updatable = false
    )
    private UUID planId;

    /** Ciclo al que pertenece el plan. */
    @Column(
            name = "cycle_id",
            nullable = false,
            updatable = false
    )
    private UUID cycleId;

    /** Autorización asociada a este intento. */
    @Column(
            name = "authorization_id",
            nullable = false,
            updatable = false
    )
    private UUID authorizationId;

    /** Número de intento, comenzando en uno. */
    @Column(
            name = "attempt_number",
            nullable = false,
            updatable = false
    )
    private int attemptNumber;

    /** Estado actual del intento. */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "execution_status",
            nullable = false,
            length = 50
    )
    private Status status;

    /** Resultado conocido de los efectos. */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "effect_outcome",
            nullable = false,
            length = 50
    )
    private EffectOutcome effectOutcome;

    /** Identificador del actor responsable. */
    @Column(
            name = "actor_id",
            nullable = false,
            length = 255,
            updatable = false
    )
    private String actorId;

    /** Instancia y generación que adquirieron la propiedad mediante JDBC. */
    @Column(name = "executor_id", length = 255)
    private String executorId;

    /** Token global de adquisición conservado para trazabilidad. */
    @Column(name = "fencing_token")
    private Long fencingToken;

    /** Última renovación confirmada del supervisor. */
    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    /** Fin exclusivo de la vigencia de propiedad. */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /** Momento de inicio efectivo. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** Momento en que terminó el intento. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** Referencia al resultado de ejecución. */
    @Column(
            name = "result_reference",
            length = 500
    )
    private String resultReference;

    /** Referencia al detalle del error. */
    @Column(
            name = "error_reference",
            length = 500
    )
    private String errorReference;

    /** Momento de creación del registro. */
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    /** Versión para concurrencia optimista JPA. */
    @Version
    @Column(
            name = "version",
            nullable = false
    )
    private long version;

    /**
     * Constructor requerido por Hibernate.
     */
    protected ExecutionAttemptEntity() {
    }

    /**
     * Crea un intento pendiente.
     *
     * <p>El número de intento debe ser asignado por
     * el servicio transaccional responsable de coordinar
     * los reintentos de una misma acción.
     *
     * @param plannedActionId acción planificada
     * @param planId plan propietario
     * @param cycleId ciclo propietario
     * @param authorizationId autorización aplicable
     * @param attemptNumber número de intento
     * @param actorId ejecutor identificado
     */
    public ExecutionAttemptEntity(
            UUID plannedActionId,
            UUID planId,
            UUID cycleId,
            UUID authorizationId,
            int attemptNumber,
            String actorId
    ) {
        this.plannedActionId = Objects.requireNonNull(
                plannedActionId, "plannedActionId es obligatorio"
        );
        this.planId = Objects.requireNonNull(
                planId, "planId es obligatorio"
        );
        this.cycleId = Objects.requireNonNull(
                cycleId, "cycleId es obligatorio"
        );
        this.authorizationId = Objects.requireNonNull(
                authorizationId, "authorizationId es obligatorio"
        );

        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "attemptNumber debe ser mayor o igual a uno"
            );
        }

        this.attemptNumber = attemptNumber;
        this.actorId = requireText(actorId, "actorId", 255);
        this.status = Status.PENDING;
        this.effectOutcome = EffectOutcome.EFFECT_UNKNOWN;
    }

    /**
     * Normaliza un texto obligatorio.
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

        if (normalized.isEmpty()
                || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " debe contener entre 1 y "
                            + maxLength + " caracteres"
            );
        }

        return normalized;
    }

    /**
     * Normaliza una referencia opcional.
     */
    private static String optionalText(
            String value,
            String field
    ) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.length() > 500) {
            throw new IllegalArgumentException(
                    field + " supera 500 caracteres"
            );
        }

        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Inicializa la fecha de creación del registro.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Inicia la ejecución de un intento pendiente.
     *
     * @throws IllegalStateException si no está pendiente
     */
    public void start() {
        requireStatus(Status.PENDING);

        this.startedAt = Instant.now();
        this.status = Status.IN_PROGRESS;
    }

    /**
     * Registra una ejecución completada.
     *
     * <p>La ejecución completada se considera un efecto
     * aplicado. No debe reintentarse.
     *
     * @param resultReference referencia opcional al resultado
     */
    public void complete(String resultReference) {
        requireStatus(Status.IN_PROGRESS);

        String reference = optionalText(
                resultReference, "resultReference"
        );

        this.resultReference = reference;
        this.effectOutcome = EffectOutcome.EFFECT_APPLIED;
        this.completedAt = Instant.now();
        this.status = Status.COMPLETED;
    }

    /**
     * Registra un intento fallido.
     *
     * <p>Una falla solo permite declarar:
     * <ul>
     *   <li>NO_EFFECT_CONFIRMED: ausencia de efectos verificada.</li>
     *   <li>EFFECT_APPLIED: se confirmó algún efecto.</li>
     * </ul>
     *
     * <p>Si el resultado es desconocido, debe utilizarse
     * markUnknown() en lugar de fail().
     *
     * @param outcome resultado conocido de los efectos
     * @param errorReference referencia obligatoria al error
     */
    public void fail(
            EffectOutcome outcome,
            String errorReference
    ) {
        requireStatus(Status.IN_PROGRESS);

        Objects.requireNonNull(
                outcome, "effectOutcome es obligatorio"
        );

        if (outcome == EffectOutcome.EFFECT_UNKNOWN) {
            throw new IllegalArgumentException(
                    "Un resultado incierto debe registrarse "
                            + "mediante markUnknown()"
            );
        }

        String reference = requireText(
                errorReference, "errorReference", 500
        );

        this.errorReference = reference;
        this.effectOutcome = outcome;
        this.completedAt = Instant.now();
        this.status = Status.FAILED;
    }

    /**
     * Registra un resultado incierto.
     *
     * <p>Evita interpretar un fallo de comunicación
     * como ausencia confirmada de efectos.
     *
     * @param errorReference referencia al incidente
     */
    public void markUnknown(String errorReference) {
        requireStatus(Status.IN_PROGRESS);

        String reference = requireText(
                errorReference, "errorReference", 500
        );

        this.errorReference = reference;
        this.effectOutcome = EffectOutcome.EFFECT_UNKNOWN;
        this.completedAt = Instant.now();
        this.status = Status.UNKNOWN;
    }

    /**
     * Comprueba que una transición parta del estado
     * esperado.
     *
     * @param expected estado permitido
     */
    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Transición de ejecución inválida: "
                            + status + " -> desde " + expected
            );
        }
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public UUID getPlannedActionId() {
        return plannedActionId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Status getStatus() {
        return status;
    }

    public EffectOutcome getEffectOutcome() {
        return effectOutcome;
    }

    public String getActorId() {
        return actorId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getResultReference() {
        return resultReference;
    }

    public String getErrorReference() {
        return errorReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
