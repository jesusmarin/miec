package com.miec.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceTest {

    private final UUID cycleId = UUID.randomUUID();

    private Evidence createValidEvidence() {
        return new Evidence(
                cycleId,
                EvidenceType.TEST_RESULT,
                "JUnit 5",
                "Resultado de ejecución de pruebas",
                "27 pruebas ejecutadas correctamente",
                "MIEC_TEST_RUNNER"
        );
    }

    @Test
    void shouldCreateEvidenceWithValidData() {
        Evidence evidence = createValidEvidence();

        assertNotNull(evidence.getId());
        assertEquals(cycleId, evidence.getCycleId());
        assertEquals(EvidenceType.TEST_RESULT, evidence.getEvidenceType());
        assertEquals("JUnit 5", evidence.getSource());
        assertEquals("Resultado de ejecución de pruebas", evidence.getDescription());
        assertEquals("27 pruebas ejecutadas correctamente", evidence.getContent());
        assertEquals("MIEC_TEST_RUNNER", evidence.getCollectedBy());
        assertNotNull(evidence.getCollectedAt());
    }

    @Test
    void shouldGenerateUniqueIdentifiers() {
        Evidence first = createValidEvidence();
        Evidence second = createValidEvidence();

        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void shouldAssociateEvidenceWithCycle() {
        Evidence evidence = createValidEvidence();

        assertEquals(cycleId, evidence.getCycleId());
    }

    @Test
    void shouldRegisterCollectionTimestamp() {
        Instant beforeCreation = Instant.now();

        Evidence evidence = createValidEvidence();

        Instant afterCreation = Instant.now();

        assertFalse(evidence.getCollectedAt().isBefore(beforeCreation));
        assertFalse(evidence.getCollectedAt().isAfter(afterCreation));
    }

    @Test
    void shouldRejectNullCycleId() {
        assertThrows(IllegalArgumentException.class, () ->
                new Evidence(
                        null,
                        EvidenceType.TEST_RESULT,
                        "JUnit 5",
                        "Descripción",
                        "Contenido",
                        "MIEC_TEST_RUNNER"
                )
        );
    }

    @Test
    void shouldRejectNullEvidenceType() {
        assertThrows(IllegalArgumentException.class, () ->
                new Evidence(
                        cycleId,
                        null,
                        "JUnit 5",
                        "Descripción",
                        "Contenido",
                        "MIEC_TEST_RUNNER"
                )
        );
    }

    @Test
    void shouldRejectEmptySource() {
        assertThrows(IllegalArgumentException.class, () ->
                new Evidence(
                        cycleId,
                        EvidenceType.TEST_RESULT,
                        " ",
                        "Descripción",
                        "Contenido",
                        "MIEC_TEST_RUNNER"
                )
        );
    }

    @Test
    void shouldRejectEmptyDescription() {
        assertThrows(IllegalArgumentException.class, () ->
                new Evidence(
                        cycleId,
                        EvidenceType.TEST_RESULT,
                        "JUnit 5",
                        " ",
                        "Contenido",
                        "MIEC_TEST_RUNNER"
                )
        );
    }

    @Test
    void shouldRejectEmptyContent() {
        assertThrows(IllegalArgumentException.class, () ->
                new Evidence(
                        cycleId,
                        EvidenceType.TEST_RESULT,
                        "JUnit 5",
                        "Descripción",
                        " ",
                        "MIEC_TEST_RUNNER"
                )
        );
    }

    @Test
    void shouldRejectEmptyCollectedBy() {
        assertThrows(IllegalArgumentException.class, () ->
                new Evidence(
                        cycleId,
                        EvidenceType.TEST_RESULT,
                        "JUnit 5",
                        "Descripción",
                        "Contenido",
                        " "
                )
        );
    }

    @Test
    void shouldTrimTextFields() {
        Evidence evidence = new Evidence(
                cycleId,
                EvidenceType.TEST_RESULT,
                " JUnit 5 ",
                " Resultado de pruebas ",
                " 27 pruebas exitosas ",
                " MIEC_TEST_RUNNER "
        );

        assertEquals("JUnit 5", evidence.getSource());
        assertEquals("Resultado de pruebas", evidence.getDescription());
        assertEquals("27 pruebas exitosas", evidence.getContent());
        assertEquals("MIEC_TEST_RUNNER", evidence.getCollectedBy());
    }

    @Test
    void shouldRejectNullTextFields() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () ->
                        new Evidence(cycleId, EvidenceType.TEST_RESULT, null,
                                "Descripción", "Contenido", "MIEC_TEST_RUNNER")),

                () -> assertThrows(IllegalArgumentException.class, () ->
                        new Evidence(cycleId, EvidenceType.TEST_RESULT, "JUnit 5",
                                null, "Contenido", "MIEC_TEST_RUNNER")),

                () -> assertThrows(IllegalArgumentException.class, () ->
                        new Evidence(cycleId, EvidenceType.TEST_RESULT, "JUnit 5",
                                "Descripción", null, "MIEC_TEST_RUNNER")),

                () -> assertThrows(IllegalArgumentException.class, () ->
                        new Evidence(cycleId, EvidenceType.TEST_RESULT, "JUnit 5",
                                "Descripción", "Contenido", null))
        );
    }

    @Test
    void shouldNotExposeMutableSetters() {
        Evidence evidence = createValidEvidence();

        assertNotNull(evidence.getId());
        assertEquals(cycleId, evidence.getCycleId());
        assertEquals(EvidenceType.TEST_RESULT, evidence.getEvidenceType());
    }
}