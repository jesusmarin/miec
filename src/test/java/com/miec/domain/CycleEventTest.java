package com.miec.domain;

import com.miec.state.CycleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CycleEventTest {

    private UUID cycleId;

    @BeforeEach
    void setUp() {
        cycleId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Debe crear correctamente un evento de transición")
    void shouldCreateStateTransitionEvent() {

        CycleEvent event = new CycleEvent(
                cycleId,
                EventType.STATE_TRANSITION,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                "Inicio del ciclo MIEC",
                "ORCHESTRATOR"
        );

        assertThat(event.getId()).isNotNull();
        assertThat(event.getCycleId()).isEqualTo(cycleId);
        assertThat(event.getEventType())
                .isEqualTo(EventType.STATE_TRANSITION);

        assertThat(event.getPreviousState())
                .isEqualTo(CycleState.E00_INACTIVO);

        assertThat(event.getNewState())
                .isEqualTo(CycleState.E01_INICIALIZANDO);

        assertThat(event.getDescription())
                .isEqualTo("Inicio del ciclo MIEC");

        assertThat(event.getActor())
                .isEqualTo("ORCHESTRATOR");

        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("Debe crear correctamente un evento sin transición")
    void shouldCreateNonTransitionEvent() {

        CycleEvent event = new CycleEvent(
                cycleId,
                EventType.EVIDENCE_REGISTERED,
                null,
                null,
                "Evidencia registrada",
                "EVIDENCE_MODULE"
        );

        assertThat(event.getEventType())
                .isEqualTo(EventType.EVIDENCE_REGISTERED);

        assertThat(event.getPreviousState()).isNull();
        assertThat(event.getNewState()).isNull();
    }

    @Test
    @DisplayName("Debe generar identificadores únicos")
    void shouldGenerateUniqueIdentifiers() {

        CycleEvent firstEvent = createTransitionEvent();
        CycleEvent secondEvent = createTransitionEvent();

        assertThat(firstEvent.getId())
                .isNotEqualTo(secondEvent.getId());
    }

    @Test
    @DisplayName("Debe registrar la fecha de creación del evento")
    void shouldRegisterOccurrenceTime() {

        Instant beforeCreation = Instant.now();

        CycleEvent event = createTransitionEvent();

        Instant afterCreation = Instant.now();

        assertThat(event.getOccurredAt())
                .isNotNull()
                .isBetween(beforeCreation, afterCreation);
    }

    @Test
    @DisplayName("No debe permitir un identificador de ciclo nulo")
    void shouldRejectNullCycleId() {

        assertThatThrownBy(() ->
                new CycleEvent(
                        null,
                        EventType.STATE_TRANSITION,
                        CycleState.E00_INACTIVO,
                        CycleState.E01_INICIALIZANDO,
                        "Inicio del ciclo",
                        "ORCHESTRATOR"
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("identificador del ciclo");
    }

    @Test
    @DisplayName("No debe permitir un tipo de evento nulo")
    void shouldRejectNullEventType() {

        assertThatThrownBy(() ->
                new CycleEvent(
                        cycleId,
                        null,
                        null,
                        null,
                        "Descripción",
                        "ORCHESTRATOR"
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tipo de evento");
    }

    @Test
    @DisplayName("Debe rechazar una transición sin estado anterior")
    void shouldRejectTransitionWithoutPreviousState() {

        assertThatThrownBy(() ->
                new CycleEvent(
                        cycleId,
                        EventType.STATE_TRANSITION,
                        null,
                        CycleState.E01_INICIALIZANDO,
                        "Inicio del ciclo",
                        "ORCHESTRATOR"
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("estado anterior");
    }

    @Test
    @DisplayName("Debe rechazar una transición sin estado nuevo")
    void shouldRejectTransitionWithoutNewState() {

        assertThatThrownBy(() ->
                new CycleEvent(
                        cycleId,
                        EventType.STATE_TRANSITION,
                        CycleState.E00_INACTIVO,
                        null,
                        "Inicio del ciclo",
                        "ORCHESTRATOR"
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nuevo estado");
    }

    @Test
    @DisplayName("Debe rechazar una transición entre estados iguales")
    void shouldRejectTransitionWithSameStates() {

        assertThatThrownBy(() ->
                new CycleEvent(
                        cycleId,
                        EventType.STATE_TRANSITION,
                        CycleState.E02_OBSERVANDO,
                        CycleState.E02_OBSERVANDO,
                        "Sin cambio de estado",
                        "ORCHESTRATOR"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debe cambiar el estado");
    }

    @Test
    @DisplayName("Debe rechazar estados en eventos que no son transiciones")
    void shouldRejectStatesInNonTransitionEvent() {

        assertThatThrownBy(() ->
                new CycleEvent(
                        cycleId,
                        EventType.EVIDENCE_REGISTERED,
                        CycleState.E02_OBSERVANDO,
                        CycleState.E03_DIAGNOSTICANDO,
                        "Evidencia registrada",
                        "EVIDENCE_MODULE"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no deben declarar estados");
    }

    @Test
    @DisplayName("No debe permitir una descripción vacía")
    void shouldRejectEmptyDescription() {

        assertThatThrownBy(() ->
                new CycleEvent(
                        cycleId,
                        EventType.STATE_TRANSITION,
                        CycleState.E00_INACTIVO,
                        CycleState.E01_INICIALIZANDO,
                        " ",
                        "ORCHESTRATOR"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descripción");
    }

    @Test
    @DisplayName("No debe permitir un actor vacío")
    void shouldRejectEmptyActor() {

        assertThatThrownBy(() ->
                new CycleEvent(
                        cycleId,
                        EventType.STATE_TRANSITION,
                        CycleState.E00_INACTIVO,
                        CycleState.E01_INICIALIZANDO,
                        "Inicio del ciclo",
                        " "
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor responsable");
    }

    @Test
    @DisplayName("Debe eliminar espacios externos de descripción y actor")
    void shouldTrimDescriptionAndActor() {

        CycleEvent event = new CycleEvent(
                cycleId,
                EventType.STATE_TRANSITION,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                "  Inicio del ciclo  ",
                "  ORCHESTRATOR  "
        );

        assertThat(event.getDescription())
                .isEqualTo("Inicio del ciclo");

        assertThat(event.getActor())
                .isEqualTo("ORCHESTRATOR");
    }

    @Test
    @DisplayName("Debe permitir identificar el ciclo asociado")
    void shouldPreserveCycleReference() {

        CycleEvent event = createTransitionEvent();

        assertThat(event.getCycleId()).isEqualTo(cycleId);
    }

    private CycleEvent createTransitionEvent() {

        return new CycleEvent(
                cycleId,
                EventType.STATE_TRANSITION,
                CycleState.E00_INACTIVO,
                CycleState.E01_INICIALIZANDO,
                "Inicio del ciclo",
                "ORCHESTRATOR"
        );
    }
}
