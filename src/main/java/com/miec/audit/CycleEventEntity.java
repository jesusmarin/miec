package com.miec.audit;

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
 * Entidad JPA que representa un evento significativo del ciclo (MIEC-F03 ENT-13).
 *
 * <p>Esta entidad registra hechos relevantes ocurridos durante la operación del ciclo,
 * permitiendo reconstruir la secuencia de operaciones y mantener la trazabilidad.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El identificador del ciclo es obligatorio.</li>
 *   <li>El tipo de evento es obligatorio.</li>
 *   <li>El instante de ocurrencia es obligatorio.</li>
 *   <li>El identificador único se genera automáticamente.</li>
 * </ul>
 *
 * @see <a href="MIEC-F03 §7.13">MIEC-F03 - Evento y transición</a>
 */
@Entity
@Table(
        name = "cycle_event",
        indexes = {
                @Index(name = "idx_event_cycle", columnList = "cycle_id"),
                @Index(name = "idx_event_type", columnList = "event_type")
        }
)
public class CycleEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "actor_id", length = 255)
    private String actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "payload_reference", length = 500)
    private String payloadReference;

    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    @Column(name = "causation_id", length = 255)
    private String causationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Constructor protegido para JPA.
     */
    protected CycleEventEntity() {
    }

    /**
     * Constructor público para crear un nuevo evento.
     *
     * @param cycleId           identificador del ciclo asociado
     * @param eventType         tipo de evento (ej: "CYCLE_STARTED", "TRANSITION_EXECUTED")
     * @param occurredAt        instante en que ocurrió el evento
     * @throws NullPointerException si cycleId, eventType u occurredAt son nulos
     */
    public CycleEventEntity(UUID cycleId, String eventType, Instant occurredAt) {
        this.cycleId = Objects.requireNonNull(cycleId, "El identificador del ciclo es obligatorio");
        this.eventType = Objects.requireNonNull(eventType, "El tipo de evento es obligatorio");
        this.occurredAt = Objects.requireNonNull(occurredAt, "El instante de ocurrencia es obligatorio");
        this.recordedAt = Instant.now();
        this.createdAt = Instant.now();
    }

    // Getters

    public UUID getEventId() {
        return eventId;
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getPayloadReference() {
        return payloadReference;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // Setters para campos opcionales

    /**
     * Establece el identificador del actor que originó el evento.
     *
     * @param actorId identificador del actor (usuario, componente o sistema)
     * @return esta entidad para encadenamiento
     */
    public CycleEventEntity actorId(String actorId) {
        this.actorId = actorId;
        return this;
    }

    /**
     * Establece la referencia al payload del evento.
     *
     * @param payloadReference referencia al contenido detallado del evento
     * @return esta entidad para encadenamiento
     */
    public CycleEventEntity payloadReference(String payloadReference) {
        this.payloadReference = payloadReference;
        return this;
    }

    /**
     * Establece el identificador de correlación.
     *
     * @param correlationId identificador para correlacionar eventos relacionados
     * @return esta entidad para encadenamiento
     */
    public CycleEventEntity correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    /**
     * Establece el identificador de causación.
     *
     * @param causationId identificador del evento o acción que causó este evento
     * @return esta entidad para encadenamiento
     */
    public CycleEventEntity causationId(String causationId) {
        this.causationId = causationId;
        return this;
    }
}