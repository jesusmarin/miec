
package com.miec.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TransitionProposalTest {

    private final CycleState source =
            CycleState.E02_OBSERVANDO;

    private final CycleState target =
            CycleState.E03_DIAGNOSTICANDO;

    private final Instant timestamp =
            Instant.parse("2026-10-28T10:00:00Z");

    @Test
    @DisplayName("Debe crear una propuesta con datos válidos")
    void shouldCreateValidTransitionProposal() {
        TransitionProposal proposal = new TransitionProposal(
                source, target, "Información suficiente", timestamp);

        assertEquals(source, proposal.sourceState());
        assertEquals(target, proposal.proposedState());
        assertEquals("Información suficiente", proposal.reason());
        assertEquals(timestamp, proposal.proposedAt());
    }

    @Test
    @DisplayName("Debe rechazar un estado de origen nulo")
    void shouldRejectNullSourceState() {
        assertThrows(NullPointerException.class,
                () -> new TransitionProposal(
                        null, target, "Motivo válido", timestamp));
    }

    @Test
    @DisplayName("Debe rechazar un estado propuesto nulo")
    void shouldRejectNullProposedState() {
        assertThrows(NullPointerException.class,
                () -> new TransitionProposal(
                        source, null, "Motivo válido", timestamp));
    }

    @Test
    @DisplayName("Debe rechazar estados de origen y destino iguales")
    void shouldRejectIdenticalStates() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransitionProposal(
                        source, source, "Motivo válido", timestamp));
    }

    @Test
    @DisplayName("Debe rechazar una razón nula")
    void shouldRejectNullReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransitionProposal(
                        source, target, null, timestamp));
    }

    @Test
    @DisplayName("Debe rechazar una razón vacía")
    void shouldRejectBlankReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransitionProposal(
                        source, target, "   ", timestamp));
    }

    @Test
    @DisplayName("Debe eliminar espacios innecesarios de la razón")
    void shouldTrimReason() {
        TransitionProposal proposal = new TransitionProposal(
                source, target, "  Motivo válido  ", timestamp);

        assertEquals("Motivo válido", proposal.reason());
    }

    @Test
    @DisplayName("Debe rechazar una fecha de propuesta nula")
    void shouldRejectNullTimestamp() {
        assertThrows(NullPointerException.class,
                () -> new TransitionProposal(
                        source, target, "Motivo válido", null));
    }
}

