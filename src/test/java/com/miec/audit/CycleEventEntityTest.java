
package com.miec.audit;

import com.miec.audit.CycleEventEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de ENT-13: CycleEventEntity.
 *
 * <p>Validan la construcciÃ³n de eventos,
 * sus invariantes de dominio, los campos
 * opcionales y la inicializaciÃ³n de auditorÃ­a.
 *
 * <p>No requieren Spring Boot ni una base de datos.
 */
@DisplayName("MIEC-F03 ENT-13 - Pruebas de CycleEventEntity")
class CycleEventEntityTest {

    /**
     * Construye un evento vÃ¡lido para las pruebas.
     *
     * @return entidad correctamente inicializada
     */
    private com.miec.audit.CycleEventEntity createValidEvent() {
        return new com.miec.audit.CycleEventEntity(
                UUID.randomUUID(),
                "EVALUATION_COMPLETED",
                Instant.parse("2026-11-01T15:00:00Z")
        );
    }

    /**
     * Comprueba la conservaciÃ³n de los datos
     * obligatorios proporcionados al constructor.
     */
    @Test
    @DisplayName("Debe crear un evento con sus campos obligatorios")
    void shouldCreateValidEvent() {
        UUID cycleId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        com.miec.audit.CycleEventEntity event = new com.miec.audit.CycleEventEntity(
                cycleId,
                "EVALUATION_COMPLETED",
                occurredAt
        );

        assertEquals(cycleId, event.getCycleId());
        assertEquals(
                "EVALUATION_COMPLETED",
                event.getEventType()
        );
        assertEquals(occurredAt, event.getOccurredAt());
    }

    /**
     * Comprueba que no se admite un evento
     * sin ciclo propietario.
     */
    @Test
    @DisplayName("Debe rechazar un identificador de ciclo nulo")
    void shouldRejectNullCycleId() {
        assertThrows(
                NullPointerException.class,
                () -> new com.miec.audit.CycleEventEntity(
                        null,
                        "EVENT",
                        Instant.now()
                )
        );
    }

    /**
     * Comprueba la obligatoriedad del tipo de evento.
     */
    @Test
    @DisplayName("Debe rechazar un tipo de evento nulo")
    void shouldRejectNullEventType() {
        assertThrows(
                NullPointerException.class,
                () -> new com.miec.audit.CycleEventEntity(
                        UUID.randomUUID(),
                        null,
                        Instant.now()
                )
        );
    }

    /**
     * Comprueba que no se admiten tipos vacÃ­os.
     */
    @Test
    @DisplayName("Debe rechazar un tipo de evento vacÃ­o")
    void shouldRejectEmptyEventType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new com.miec.audit.CycleEventEntity(
                        UUID.randomUUID(),
                        "   ",
                        Instant.now()
                )
        );
    }

    /**
     * Comprueba la longitud mÃ¡xima del tipo de evento.
     */
    @Test
    @DisplayName("Debe rechazar tipos de evento mayores de 100 caracteres")
    void shouldRejectOversizedEventType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new com.miec.audit.CycleEventEntity(
                        UUID.randomUUID(),
                        "X".repeat(101),
                        Instant.now()
                )
        );
    }

    /**
     * Comprueba que la ocurrencia es obligatoria.
     */
    @Test
    @DisplayName("Debe rechazar una fecha de ocurrencia nula")
    void shouldRejectNullOccurredAt() {
        assertThrows(
                NullPointerException.class,
                () -> new com.miec.audit.CycleEventEntity(
                        UUID.randomUUID(),
                        "EVENT",
                        null
                )
        );
    }

    /**
     * Comprueba la normalizaciÃ³n del tipo
     * de evento proporcionado.
     */
    @Test
    @DisplayName("Debe eliminar espacios externos del tipo de evento")
    void shouldNormalizeEventType() {
        com.miec.audit.CycleEventEntity event = new com.miec.audit.CycleEventEntity(
                UUID.randomUUID(),
                "  CYCLE_STARTED  ",
                Instant.now()
        );

        assertEquals(
                "CYCLE_STARTED",
                event.getEventType()
        );
    }

    /**
     * Comprueba la configuraciÃ³n fluida
     * de los campos opcionales.
     */
    @Test
    @DisplayName("Debe almacenar actor, contenido, correlaciÃ³n y causalidad")
    void shouldSetOptionalFields() {
        com.miec.audit.CycleEventEntity event = createValidEvent();

        com.miec.audit.CycleEventEntity result = event
                .actorId("actor-001")
                .payloadReference("payload-001")
                .correlationId("correlation-001")
                .causationId("event-previous");

        assertSame(event, result);
        assertEquals("actor-001", event.getActorId());
        assertEquals(
                "payload-001",
                event.getPayloadReference()
        );
        assertEquals(
                "correlation-001",
                event.getCorrelationId()
        );
        assertEquals(
                "event-previous",
                event.getCausationId()
        );
    }

    /**
     * Comprueba que todos los campos opcionales
     * admiten valores nulos.
     */
    @Test
    @DisplayName("Debe permitir campos opcionales nulos")
    void shouldAcceptNullOptionalFields() {
        com.miec.audit.CycleEventEntity event = createValidEvent();

        event.actorId(null)
                .payloadReference(null)
                .correlationId(null)
                .causationId(null);

        assertNull(event.getActorId());
        assertNull(event.getPayloadReference());
        assertNull(event.getCorrelationId());
        assertNull(event.getCausationId());
    }

    /**
     * Comprueba la normalizaciÃ³n de los
     * campos de texto opcionales.
     */
    @Test
    @DisplayName("Debe normalizar espacios en campos opcionales")
    void shouldNormalizeOptionalFields() {
        com.miec.audit.CycleEventEntity event = createValidEvent()
                .actorId("  actor-001  ")
                .correlationId("  trace-001  ");

        assertEquals("actor-001", event.getActorId());
        assertEquals(
                "trace-001",
                event.getCorrelationId()
        );
    }

    /**
     * Comprueba los lÃ­mites de longitud
     * establecidos por el esquema V001.
     */
    @Test
    @DisplayName("Debe rechazar campos opcionales que excedan su longitud")
    void shouldRejectOversizedOptionalFields() {
        com.miec.audit.CycleEventEntity event = createValidEvent();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> event.actorId("A".repeat(256))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> event.payloadReference(
                                "P".repeat(501)
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> event.correlationId(
                                "C".repeat(256)
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> event.causationId(
                                "E".repeat(256)
                        )
                )
        );
    }

    /**
     * Comprueba la inicializaciÃ³n de las
     * fechas de auditorÃ­a antes de persistir.
     */
    @Test
    @DisplayName("Debe inicializar las fechas de auditorÃ­a antes de persistir")
    void shouldInitializeAuditDates() {
        com.miec.audit.CycleEventEntity event = createValidEvent();

        assertNull(event.getRecordedAt());
        assertNull(event.getCreatedAt());

        event.onCreate();

        assertNotNull(event.getRecordedAt());
        assertNotNull(event.getCreatedAt());
    }

    /**
     * Comprueba que el callback de persistencia
     * no reemplace fechas previamente inicializadas.
     */
    @Test
    @DisplayName("Debe conservar las fechas de auditorÃ­a en invocaciones repetidas")
    void shouldPreserveAuditDates() {
        CycleEventEntity event = createValidEvent();

        event.onCreate();

        Instant recordedAt = event.getRecordedAt();
        Instant createdAt = event.getCreatedAt();

        event.onCreate();

        assertEquals(recordedAt, event.getRecordedAt());
        assertEquals(createdAt, event.getCreatedAt());
    }
}

