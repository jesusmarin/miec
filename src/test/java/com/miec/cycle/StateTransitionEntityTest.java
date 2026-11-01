
package com.miec.cycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de ENT-14: StateTransitionEntity.
 *
 * <p>Comprueban la construcción de transiciones,
 * los campos obligatorios, los límites de longitud,
 * los datos opcionales y la auditoría.
 *
 * <p>Estas pruebas no necesitan Spring Boot ni H2.
 * La clave foránea compuesta de IR-007 se comprobará
 * en las pruebas de integración posteriores.
 */
@DisplayName("MIEC-F03 ENT-14 - Pruebas de StateTransitionEntity")
class StateTransitionEntityTest {

    /**
     * Construye una transición válida para reutilizar
     * en los diferentes escenarios de prueba.
     *
     * @return transición correctamente inicializada
     */
    private StateTransitionEntity createValidTransition() {
        return new StateTransitionEntity(
                UUID.randomUUID(),
                "E00_INACTIVO",
                "E01_PREPARACION",
                "COMPLETED",
                Instant.parse("2026-11-01T15:00:00Z")
        );
    }

    /**
     * Comprueba la inicialización de los
     * datos obligatorios.
     */
    @Test
    @DisplayName("Debe construir una transición con todos sus campos obligatorios")
    void shouldCreateValidTransition() {
        UUID cycleId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        StateTransitionEntity transition =
                new StateTransitionEntity(
                        cycleId,
                        "E00_INACTIVO",
                        "E01_PREPARACION",
                        "COMPLETED",
                        occurredAt
                );

        assertAll(
                () -> assertEquals(
                        cycleId, transition.getCycleId()
                ),
                () -> assertEquals(
                        "E00_INACTIVO",
                        transition.getFromState()
                ),
                () -> assertEquals(
                        "E01_PREPARACION",
                        transition.getToState()
                ),
                () -> assertEquals(
                        "COMPLETED",
                        transition.getTransitionStatus()
                ),
                () -> assertEquals(
                        occurredAt,
                        transition.getOccurredAt()
                )
        );
    }

    /**
     * Verifica que una transición siempre
     * pertenezca a un ciclo.
     */
    @Test
    @DisplayName("Debe rechazar un identificador de ciclo nulo")
    void shouldRejectNullCycleId() {
        assertThrows(
                NullPointerException.class,
                () -> new StateTransitionEntity(
                        null,
                        "E00",
                        "E01",
                        "COMPLETED",
                        Instant.now()
                )
        );
    }

    /**
     * Verifica la obligatoriedad
     * del estado de origen.
     */
    @Test
    @DisplayName("Debe rechazar un estado de origen nulo")
    void shouldRejectNullFromState() {
        assertThrows(
                NullPointerException.class,
                () -> new StateTransitionEntity(
                        UUID.randomUUID(),
                        null,
                        "E01",
                        "COMPLETED",
                        Instant.now()
                )
        );
    }

    /**
     * Verifica que el estado de origen
     * no pueda contener solamente espacios.
     */
    @Test
    @DisplayName("Debe rechazar un estado de origen vacío")
    void shouldRejectBlankFromState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StateTransitionEntity(
                        UUID.randomUUID(),
                        "   ",
                        "E01",
                        "COMPLETED",
                        Instant.now()
                )
        );
    }

    /**
     * Verifica la obligatoriedad
     * del estado de destino.
     */
    @Test
    @DisplayName("Debe rechazar un estado de destino nulo")
    void shouldRejectNullToState() {
        assertThrows(
                NullPointerException.class,
                () -> new StateTransitionEntity(
                        UUID.randomUUID(),
                        "E00",
                        null,
                        "COMPLETED",
                        Instant.now()
                )
        );
    }

    /**
     * Verifica que el estado de destino
     * no pueda estar vacío.
     */
    @Test
    @DisplayName("Debe rechazar un estado de destino vacío")
    void shouldRejectBlankToState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StateTransitionEntity(
                        UUID.randomUUID(),
                        "E00",
                        "  ",
                        "COMPLETED",
                        Instant.now()
                )
        );
    }

    /**
     * Verifica la obligatoriedad del
     * estado de la transición.
     */
    @Test
    @DisplayName("Debe rechazar un estado de transición nulo")
    void shouldRejectNullTransitionStatus() {
        assertThrows(
                NullPointerException.class,
                () -> new StateTransitionEntity(
                        UUID.randomUUID(),
                        "E00",
                        "E01",
                        null,
                        Instant.now()
                )
        );
    }

    /**
     * Verifica que el estado de transición
     * no pueda estar vacío.
     */
    @Test
    @DisplayName("Debe rechazar un estado de transición vacío")
    void shouldRejectBlankTransitionStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StateTransitionEntity(
                        UUID.randomUUID(),
                        "E00",
                        "E01",
                        "  ",
                        Instant.now()
                )
        );
    }

    /**
     * Verifica la obligatoriedad de la
     * fecha efectiva del cambio.
     */
    @Test
    @DisplayName("Debe rechazar una fecha de ocurrencia nula")
    void shouldRejectNullOccurredAt() {
        assertThrows(
                NullPointerException.class,
                () -> new StateTransitionEntity(
                        UUID.randomUUID(),
                        "E00",
                        "E01",
                        "COMPLETED",
                        null
                )
        );
    }

    /**
     * Comprueba los límites de longitud
     * de las columnas obligatorias.
     */
    @Test
    @DisplayName("Debe rechazar textos obligatorios superiores a 50 caracteres")
    void shouldRejectOversizedRequiredFields() {
        UUID cycleId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new StateTransitionEntity(
                                cycleId,
                                "A".repeat(51),
                                "E01",
                                "COMPLETED",
                                occurredAt
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new StateTransitionEntity(
                                cycleId,
                                "E00",
                                "B".repeat(51),
                                "COMPLETED",
                                occurredAt
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new StateTransitionEntity(
                                cycleId,
                                "E00",
                                "E01",
                                "C".repeat(51),
                                occurredAt
                        )
                )
        );
    }

    /**
     * Comprueba que los valores obligatorios
     * se normalicen al crear la transición.
     */
    @Test
    @DisplayName("Debe eliminar espacios externos de los estados")
    void shouldNormalizeRequiredFields() {
        StateTransitionEntity transition =
                new StateTransitionEntity(
                        UUID.randomUUID(),
                        "  E00_INACTIVO  ",
                        "  E01_PREPARACION  ",
                        "  COMPLETED  ",
                        Instant.now()
                );

        assertAll(
                () -> assertEquals(
                        "E00_INACTIVO",
                        transition.getFromState()
                ),
                () -> assertEquals(
                        "E01_PREPARACION",
                        transition.getToState()
                ),
                () -> assertEquals(
                        "COMPLETED",
                        transition.getTransitionStatus()
                )
        );
    }

    /**
     * Verifica que una transición pueda contener
     * un evento desencadenante.
     */
    @Test
    @DisplayName("Debe asociar un evento desencadenante opcional")
    void shouldSetTriggerEventId() {
        UUID eventId = UUID.randomUUID();
        StateTransitionEntity transition =
                createValidTransition();

        assertSame(
                transition,
                transition.triggerEventId(eventId)
        );
        assertEquals(
                eventId,
                transition.getTriggerEventId()
        );
    }

    /**
     * Comprueba que el evento desencadenante
     * puede estar ausente.
     */
    @Test
    @DisplayName("Debe permitir transiciones sin evento desencadenante")
    void shouldAllowNullTriggerEventId() {
        StateTransitionEntity transition =
                createValidTransition();

        transition.triggerEventId(null);

        assertNull(transition.getTriggerEventId());
    }

    /**
     * Verifica la configuración de los
     * campos opcionales restantes.
     */
    @Test
    @DisplayName("Debe almacenar la regla y la referencia de autorización")
    void shouldSetOptionalReferences() {
        StateTransitionEntity transition =
                createValidTransition();

        StateTransitionEntity result = transition
                .transitionRuleId("MIEC-F01-R001")
                .authorizationReference("AUTH-001");

        assertSame(transition, result);
        assertEquals(
                "MIEC-F01-R001",
                transition.getTransitionRuleId()
        );
        assertEquals(
                "AUTH-001",
                transition.getAuthorizationReference()
        );
    }

    /**
     * Comprueba que las referencias opcionales
     * puedan quedar sin valor.
     */
    @Test
    @DisplayName("Debe permitir referencias opcionales nulas")
    void shouldAllowNullOptionalReferences() {
        StateTransitionEntity transition =
                createValidTransition()
                        .transitionRuleId(null)
                        .authorizationReference(null);

        assertNull(transition.getTransitionRuleId());
        assertNull(transition.getAuthorizationReference());
    }

    /**
     * Comprueba la eliminación de espacios
     * en las referencias opcionales.
     */
    @Test
    @DisplayName("Debe normalizar las referencias opcionales")
    void shouldNormalizeOptionalReferences() {
        StateTransitionEntity transition =
                createValidTransition()
                        .transitionRuleId("  RULE-01  ")
                        .authorizationReference("  AUTH-01  ");

        assertEquals(
                "RULE-01",
                transition.getTransitionRuleId()
        );
        assertEquals(
                "AUTH-01",
                transition.getAuthorizationReference()
        );
    }

    /**
     * Comprueba las longitudes definidas
     * por el esquema de persistencia.
     */
    @Test
    @DisplayName("Debe rechazar referencias opcionales demasiado largas")
    void shouldRejectOversizedOptionalReferences() {
        StateTransitionEntity transition =
                createValidTransition();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> transition.transitionRuleId(
                                "R".repeat(101)
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> transition.authorizationReference(
                                "A".repeat(256)
                        )
                )
        );
    }

    /**
     * Comprueba que la creación del registro
     * inicialice su fecha de auditoría.
     */
    @Test
    @DisplayName("Debe inicializar la fecha de creación antes de persistir")
    void shouldInitializeCreatedAt() {
        StateTransitionEntity transition =
                createValidTransition();

        assertNull(transition.getCreatedAt());

        transition.onCreate();

        assertNotNull(transition.getCreatedAt());
    }

    /**
     * Comprueba que la fecha de auditoría
     * no cambie si el callback se ejecuta nuevamente.
     */
    @Test
    @DisplayName("Debe conservar la fecha de creación en llamadas repetidas")
    void shouldPreserveCreatedAt() {
        StateTransitionEntity transition =
                createValidTransition();

        transition.onCreate();
        Instant firstCreationDate =
                transition.getCreatedAt();

        transition.onCreate();

        assertEquals(
                firstCreationDate,
                transition.getCreatedAt()
        );
    }
}
