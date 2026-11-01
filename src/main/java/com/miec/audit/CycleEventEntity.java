package com.miec.audit;

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
 * ENT-13: Entidad JPA que representa un evento significativo
 * de un ciclo de evolución MIEC.
 *
 * <p>Registra acontecimientos relevantes durante la operación
 * del ciclo, permitiendo reconstruir la secuencia de hechos,
 * identificar sus participantes y preservar su trazabilidad.
 *
 * <p>Un evento puede actuar como desencadenante de una
 * transición de estado registrada mediante ENT-14.
 *
 * <p>Invariantes de la entidad:
 * <ul>
 *   <li>El identificador del ciclo es obligatorio.</li>
 *   <li>El tipo de evento es obligatorio y no puede estar vacío.</li>
 *   <li>La fecha de ocurrencia es obligatoria.</li>
 *   <li>La longitud de los textos respeta el esquema SQL.</li>
 *   <li>El identificador del evento se genera automáticamente.</li>
 *   <li>Las fechas de auditoría se inicializan al persistir.</li>
 * </ul>
 *
 * <p>Integridad referencial IR-007:
 * la restricción FK_TRANSITION_EVENT_CYCLE de Flyway V002
 * relaciona el par (event_id, cycle_id) con las transiciones
 * de estado, impidiendo que una transición utilice un evento
 * desencadenante perteneciente a otro ciclo.
 *
 * <p>Esta clase modela el registro del evento. La validación
 * de integridad entre entidades corresponde a la base de
 * datos y las reglas del motor de evolución MIEC.
 *
 * @see com.miec.cycle.StateTransitionEntity
 */
@Entity
@Table(
        name = "cycle_event",
        indexes = {
                @Index(
                        name = "idx_event_cycle",
                        columnList = "cycle_id"
                ),
                @Index(
                        name = "idx_event_type",
                        columnList = "event_type"
                )
        }
)
public class CycleEventEntity {

    /** Identificador único del evento. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "event_id",
            nullable = false,
            updatable = false
    )
    private UUID eventId;

    /** Identificador del ciclo propietario del evento. */
    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    /** Clasificación funcional del acontecimiento. */
    @Column(
            name = "event_type",
            nullable = false,
            length = 100
    )
    private String eventType;

    /** Identificador del actor que produjo el evento. */
    @Column(name = "actor_id", length = 255)
    private String actorId;

    /** Momento efectivo en que ocurrió el acontecimiento. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Momento en que el acontecimiento quedó registrado. */
    @Column(
            name = "recorded_at",
            nullable = false,
            updatable = false
    )
    private Instant recordedAt;

    /** Referencia externa al contenido detallado del evento. */
    @Column(name = "payload_reference", length = 500)
    private String payloadReference;

    /** Identificador para correlacionar operaciones relacionadas. */
    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    /** Referencia al acontecimiento que causó este evento. */
    @Column(name = "causation_id", length = 255)
    private String causationId;

    /** Momento de creación del registro persistente. */
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    /**
     * Constructor protegido requerido por JPA.
     *
     * <p>Hibernate utiliza este constructor
     * para reconstruir entidades desde la base de datos.
     */
    protected CycleEventEntity() {
    }

    /**
     * Construye un evento asociado a un ciclo de evolución.
     *
     * @param cycleId identificador del ciclo propietario
     * @param eventType clasificación del acontecimiento
     * @param occurredAt instante efectivo de ocurrencia
     * @throws NullPointerException si algún campo obligatorio es nulo
     * @throws IllegalArgumentException si eventType está vacío
     *                                  o supera 100 caracteres
     */
    public CycleEventEntity(
            UUID cycleId,
            String eventType,
            Instant occurredAt
    ) {
        this.cycleId = Objects.requireNonNull(
                cycleId,
                "El identificador del ciclo es obligatorio"
        );

        this.eventType = requireText(
                eventType,
                100,
                "eventType"
        );

        this.occurredAt = Objects.requireNonNull(
                occurredAt,
                "El instante de ocurrencia es obligatorio"
        );
    }

    /**
     * Valida y normaliza un texto obligatorio.
     *
     * <p>Elimina espacios externos y rechaza valores
     * vacíos o superiores a la longitud permitida.
     *
     * @param value contenido recibido
     * @param maxLength longitud máxima permitida
     * @param field nombre del campo para diagnóstico
     * @return texto validado y normalizado
     * @throws NullPointerException si value es nulo
     * @throws IllegalArgumentException si el texto es vacío
     *                                  o excede su longitud
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
                    field + " supera "
                            + maxLength
                            + " caracteres"
            );
        }

        return normalized;
    }

    /**
     * Valida y normaliza un texto opcional.
     *
     * <p>Los valores nulos están permitidos. Para los
     * valores presentes se eliminan espacios externos
     * y se verifica la longitud máxima.
     *
     * @param value contenido opcional
     * @param maxLength longitud máxima permitida
     * @param field nombre del campo para diagnóstico
     * @return texto normalizado o null
     * @throws IllegalArgumentException si excede la longitud
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
                    field + " supera "
                            + maxLength
                            + " caracteres"
            );
        }

        return normalized;
    }

    /**
     * Inicializa las fechas de auditoría
     * antes de insertar el evento en la base de datos.
     *
     * <p>Se utiliza un único instante para ambas fechas
     * cuando todavía no tienen valor.
     *
     * <p>Las fechas existentes se conservan para
     * evitar su modificación en invocaciones posteriores.
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();

        if (recordedAt == null) {
            recordedAt = now;
        }

        if (createdAt == null) {
            createdAt = now;
        }
    }

    /**
     * Obtiene el identificador del evento.
     *
     * @return UUID generado durante la persistencia
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Obtiene el identificador del ciclo propietario.
     *
     * @return UUID del ciclo
     */
    public UUID getCycleId() {
        return cycleId;
    }

    /**
     * Obtiene la clasificación funcional del evento.
     *
     * @return tipo de evento
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Obtiene el identificador del actor.
     *
     * @return actor asociado o null
     */
    public String getActorId() {
        return actorId;
    }

    /**
     * Obtiene la fecha efectiva del evento.
     *
     * @return instante de ocurrencia
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * Obtiene la fecha de registro del evento.
     *
     * @return instante de registro o null antes de persistir
     */
    public Instant getRecordedAt() {
        return recordedAt;
    }

    /**
     * Obtiene la referencia al contenido detallado.
     *
     * @return referencia opcional
     */
    public String getPayloadReference() {
        return payloadReference;
    }

    /**
     * Obtiene el identificador de correlación.
     *
     * @return identificador opcional
     */
    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * Obtiene el identificador de causación.
     *
     * @return identificador opcional
     */
    public String getCausationId() {
        return causationId;
    }

    /**
     * Obtiene el instante de creación del registro.
     *
     * @return fecha de creación o null antes de persistir
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Establece el actor que originó el evento.
     *
     * @param actorId identificador opcional del actor
     * @return esta entidad para encadenamiento fluido
     */
    public CycleEventEntity actorId(String actorId) {
        this.actorId = optionalText(
                actorId,
                255,
                "actorId"
        );
        return this;
    }

    /**
     * Establece la referencia al contenido del evento.
     *
     * @param payloadReference referencia opcional
     * @return esta entidad para encadenamiento fluido
     */
    public CycleEventEntity payloadReference(
            String payloadReference
    ) {
        this.payloadReference = optionalText(
                payloadReference,
                500,
                "payloadReference"
        );
        return this;
    }

    /**
     * Establece el identificador de correlación.
     *
     * @param correlationId identificador opcional
     * @return esta entidad para encadenamiento fluido
     */
    public CycleEventEntity correlationId(
            String correlationId
    ) {
        this.correlationId = optionalText(
                correlationId,
                255,
                "correlationId"
        );
        return this;
    }

    /**
     * Establece la referencia causal del evento.
     *
     * @param causationId identificador opcional
     * @return esta entidad para encadenamiento fluido
     */
    public CycleEventEntity causationId(
            String causationId
    ) {
        this.causationId = optionalText(
                causationId,
                255,
                "causationId"
        );
        return this;
    }
}
