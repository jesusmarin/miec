package com.miec.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Pruebas del registro de decisiones DecisionRecord")
class DecisionRecordTest {

    private final UUID cycleId = UUID.randomUUID();
    private final UUID evidenceId = UUID.randomUUID();

    private DecisionRecord createValidDecision() {
        return new DecisionRecord(
                cycleId,
                DecisionType.CONTINUE,
                "Continuar el ciclo",
                "Las pruebas finalizaron correctamente.",
                List.of(evidenceId),
                "MIEC_DECISION_ENGINE"
        );
    }

    @Test
    @DisplayName("Debe crear un registro de decisión con datos válidos")
    void shouldCreateDecisionWithValidData() {
        DecisionRecord decision = createValidDecision();

        assertNotNull(decision.getId());
        assertEquals(cycleId, decision.getCycleId());
        assertEquals(DecisionType.CONTINUE, decision.getDecisionType());
        assertEquals("Continuar el ciclo", decision.getOutcome());
        assertEquals(
                "Las pruebas finalizaron correctamente.",
                decision.getRationale()
        );
        assertEquals(List.of(evidenceId), decision.getEvidenceIds());
        assertEquals("MIEC_DECISION_ENGINE", decision.getDecidedBy());
        assertNotNull(decision.getRecordedAt());
    }

    @Test
    @DisplayName("Debe generar identificadores únicos para cada decisión")
    void shouldGenerateUniqueIdentifiers() {
        DecisionRecord first = createValidDecision();
        DecisionRecord second = createValidDecision();

        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    @DisplayName("Debe registrar la fecha de creación de la decisión")
    void shouldRegisterTimestamp() {
        Instant beforeCreation = Instant.now();

        DecisionRecord decision = createValidDecision();

        Instant afterCreation = Instant.now();

        assertFalse(decision.getRecordedAt().isBefore(beforeCreation));
        assertFalse(decision.getRecordedAt().isAfter(afterCreation));
    }

    @Test
    @DisplayName("Debe identificar las decisiones que tienen evidencias")
    void shouldIdentifyDecisionsWithEvidence() {
        DecisionRecord decision = createValidDecision();

        assertTrue(decision.hasEvidence());
    }

    @Test
    @DisplayName("Debe permitir registrar una decisión sin evidencias")
    void shouldAllowDecisionWithoutEvidence() {
        DecisionRecord decision = new DecisionRecord(
                cycleId,
                DecisionType.SUSPEND,
                "Suspender el ciclo",
                "Se requiere una revisión de seguridad.",
                List.of(),
                "MIEC_POLICY_ENGINE"
        );

        assertTrue(decision.getEvidenceIds().isEmpty());
        assertFalse(decision.hasEvidence());
    }

    @Test
    @DisplayName("Debe conservar una copia inmutable de la lista de evidencias")
    void shouldDefensivelyCopyEvidenceIds() {
        List<UUID> evidenceIds = new ArrayList<>();
        evidenceIds.add(evidenceId);

        DecisionRecord decision = new DecisionRecord(
                cycleId,
                DecisionType.CONTINUE,
                "Continuar",
                "Evidencias verificadas.",
                evidenceIds,
                "MIEC_DECISION_ENGINE"
        );

        evidenceIds.add(UUID.randomUUID());

        assertEquals(1, decision.getEvidenceIds().size());
        assertEquals(evidenceId, decision.getEvidenceIds().get(0));
    }

    @Test
    @DisplayName("No debe permitir modificar la lista de evidencias")
    void shouldRejectEvidenceListModification() {
        DecisionRecord decision = createValidDecision();

        assertThrows(
                UnsupportedOperationException.class,
                () -> decision.getEvidenceIds().add(UUID.randomUUID())
        );
    }

    @Test
    @DisplayName("Debe rechazar un identificador de ciclo nulo")
    void shouldRejectNullCycleId() {
        assertThrows(
                NullPointerException.class,
                () -> new DecisionRecord(
                        null,
                        DecisionType.CONTINUE,
                        "Continuar",
                        "Justificación",
                        List.of(),
                        "MIEC_DECISION_ENGINE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar un tipo de decisión nulo")
    void shouldRejectNullDecisionType() {
        assertThrows(
                NullPointerException.class,
                () -> new DecisionRecord(
                        cycleId,
                        null,
                        "Continuar",
                        "Justificación",
                        List.of(),
                        "MIEC_DECISION_ENGINE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar un resultado vacío")
    void shouldRejectEmptyOutcome() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionRecord(
                        cycleId,
                        DecisionType.CONTINUE,
                        " ",
                        "Justificación",
                        List.of(),
                        "MIEC_DECISION_ENGINE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar una justificación vacía")
    void shouldRejectEmptyRationale() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionRecord(
                        cycleId,
                        DecisionType.CONTINUE,
                        "Continuar",
                        " ",
                        List.of(),
                        "MIEC_DECISION_ENGINE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar una lista de evidencias nula")
    void shouldRejectNullEvidenceList() {
        assertThrows(
                NullPointerException.class,
                () -> new DecisionRecord(
                        cycleId,
                        DecisionType.CONTINUE,
                        "Continuar",
                        "Justificación",
                        null,
                        "MIEC_DECISION_ENGINE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar identificadores de evidencia nulos")
    void shouldRejectNullEvidenceIdentifiers() {
        List<UUID> evidenceIds = new ArrayList<>();
        evidenceIds.add(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionRecord(
                        cycleId,
                        DecisionType.CONTINUE,
                        "Continuar",
                        "Justificación",
                        evidenceIds,
                        "MIEC_DECISION_ENGINE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar un responsable vacío")
    void shouldRejectEmptyDecidedBy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionRecord(
                        cycleId,
                        DecisionType.CONTINUE,
                        "Continuar",
                        "Justificación",
                        List.of(),
                        " "
                )
        );
    }

    @Test
    @DisplayName("Debe eliminar espacios externos de los campos de texto")
    void shouldTrimTextFields() {
        DecisionRecord decision = new DecisionRecord(
                cycleId,
                DecisionType.CONTINUE,
                " Continuar el ciclo ",
                " Las pruebas fueron satisfactorias ",
                List.of(evidenceId),
                " MIEC_DECISION_ENGINE "
        );

        assertEquals("Continuar el ciclo", decision.getOutcome());
        assertEquals(
                "Las pruebas fueron satisfactorias",
                decision.getRationale()
        );
        assertEquals("MIEC_DECISION_ENGINE", decision.getDecidedBy());
    }

    @Test
    @DisplayName("Debe conservar el orden de los identificadores de evidencia")
    void shouldPreserveEvidenceOrder() {
        UUID firstEvidence = UUID.randomUUID();
        UUID secondEvidence = UUID.randomUUID();

        DecisionRecord decision = new DecisionRecord(
                cycleId,
                DecisionType.CONTINUE,
                "Continuar",
                "Evidencias ordenadas.",
                List.of(firstEvidence, secondEvidence),
                "MIEC_DECISION_ENGINE"
        );

        assertEquals(
                List.of(firstEvidence, secondEvidence),
                decision.getEvidenceIds()
        );
    }
}
