
package com.miec.cycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ENT-14: Transición de estado de un ciclo de evolución MIEC.
 *
 * <p>Representa el cambio de un estado de un ciclo a otro,
 * conservando información sobre su origen, destino,
 * evento desencadenante, regla aplicada y autorización.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>La transición pertenece obligatoriamente a un ciclo.</li>
 *   <li>Los estados de origen y destino son obligatorios.</li>
 *   <li>El estado de la transición es obligatorio.</li>
 *   <li>La fecha de ocurrencia es obligatoria.</li>
 *   <li>El evento desencadenante es opcional.</li>
 * </ul>
 *
 * <p>IR-007: si triggerEventId tiene un valor, la base
 * de datos exige que el evento referenciado pertenezca
 * al mismo ciclo mediante FK_TRANSITION_EVENT_CYCLE.
 *
 * <p>La validez semántica del cambio entre estados
 * corresponde al motor de estados MIEC-F01; esta entidad
 * modela su persistencia, no decide las transiciones.
 */
@Entity
@Table(name = "state_transition")
public class StateTransitionEntity {

    /** Identificador único de la transición. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "transition_id",
            nullable = false,
            updatable = false
    )
    private UUID transitionId;

    /** Identificador del ciclo propietario. */
    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    /** Estado anterior al cambio. */
    @Column(name = "from_state", nullable = false, length = 50)
    private String fromState;

    /** Estado resultante del cambio. */
    @Column(name = "to_state", nullable = false, length = 50)
    private String toState;

    /** Evento desencadenante, cuando existe. */
    @Column(name = "trigger_event_id")
    private UUID triggerEventId;

    /** Identificador de la regla que autorizó el cambio. */
    @Column(name = "transition_rule_id", length = 100)
    private String transitionRuleId;

    /** Referencia a una autorización aplicable. */
    @Column(name = "authorization_reference", length = 255)
    private String authorizationReference;

    /** Estado de ejecución o registro de la transición. */
    @Column(
            name = "transition_status",
            nullable = false,
            length = 50
    )
    private String transitionStatus;

    /** Momento efectivo del cambio de estado. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Momento de creación del registro. */
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    /**
     * Constructor protegido requerido por JPA.
     */
    protected StateTransitionEntity() {
    }

    /**
     * Construye una transición de estado.
     *
     * @param cycleId ciclo propietario
     * @param fromState estado de origen
     * @param toState estado de destino
     * @param transitionStatus estado de la transición
     * @param occurredAt instante efectivo del cambio
     */
    public StateTransitionEntity(
            UUID cycleId,
            String fromState,
            String toState,
            String transitionStatus,
            Instant occurredAt
    ) {
        this.cycleId = Objects.requireNonNull(
                cycleId,
                "El identificador del ciclo es obligatorio"
        );
        this.fromState = requireText(
                fromState, 50, "fromState"
        );
        this.toState = requireText(
                toState, 50, "toState"
        );
        this.transitionStatus = requireText(
                transitionStatus, 50, "transitionStatus"
        );
        this.occurredAt = Objects.requireNonNull(
                occurredAt,
                "La fecha de ocurrencia es obligatoria"
        );
    }

    /**
     * Valida y normaliza un campo obligatorio.
     *
     * @param value valor recibido
     * @param maxLength longitud máxima admitida
     * @param field nombre del campo
     * @return texto normalizado
     */
    private static String requireText(
            String value,
            int maxLength,
            String field
    ) {
        Objects.requireNonNull(
                value,
                field + " es obligatorio"
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
     * Valida y normaliza un campo opcional.
     *
     * @param value valor recibido, posiblemente nulo
     * @param maxLength longitud máxima admitida
     * @param field nombre del campo
     * @return texto normalizado o null
     */
    private static String optionalText(
            String value,
            int maxLength,
            String field
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
     * Inicializa la fecha de creación durante
     * la primera persistencia.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * @return identificador de la transición
     */
    public UUID getTransitionId() {
        return transitionId;
    }

    /**
     * @return identificador del ciclo propietario
     */
    public UUID getCycleId() {
        return cycleId;
    }

    /**
     * @return estado anterior
     */
    public String getFromState() {
        return fromState;
    }

    /**
     * @return estado de destino
     */
    public String getToState() {
        return toState;
    }

    /**
     * @return UUID del evento desencadenante
     */
    public UUID getTriggerEventId() {
        return triggerEventId;
    }

    /**
     * @return identificador de la regla aplicada
     */
    public String getTransitionRuleId() {
        return transitionRuleId;
    }

    /**
     * @return referencia a la autorización
     */
    public String getAuthorizationReference() {
        return authorizationReference;
    }

    /**
     * @return estado de la transición
     */
    public String getTransitionStatus() {
        return transitionStatus;
    }

    /**
     * @return momento efectivo del cambio
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * @return momento de creación del registro
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Asocia opcionalmente un evento desencadenante.
     *
     * <p>La compatibilidad entre el ciclo de la transición
     * y el del evento se verifica en la base de datos.
     *
     * @param triggerEventId UUID del evento, o null
     * @return esta entidad
     */
    public StateTransitionEntity triggerEventId(
            UUID triggerEventId
    ) {
        this.triggerEventId = triggerEventId;
        return this;
    }

    /**
     * Establece la regla de transición aplicada.
     *
     * @param transitionRuleId identificador opcional
     * @return esta entidad
     */
    public StateTransitionEntity transitionRuleId(
            String transitionRuleId
    ) {
        this.transitionRuleId = optionalText(
                transitionRuleId,
                100,
                "transitionRuleId"
        );
        return this;
    }

    /**
     * Establece la referencia de autorización.
     *
     * @param authorizationReference referencia opcional
     * @return esta entidad
     */
    public StateTransitionEntity authorizationReference(
            String authorizationReference
    ) {
        this.authorizationReference = optionalText(
                authorizationReference,
                255,
                "authorizationReference"
        );
        return this;
    }
}

