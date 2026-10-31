

package com.miec.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceAssessmentTest {

    private final Instant assessedAt =
            Instant.parse("2026-10-30T12:00:00Z");

    @Test
    @DisplayName("Debe crear una evaluación con datos válidos")
    void shouldCreateAssessmentWithValidData() {
        EvidenceAssessment assessment = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of("Limitación documentada"),
                List.of("Incertidumbre pendiente"),
                "Evaluación basada en evidencias verificables",
                assessedAt
        );

        assertNotNull(assessment);
        assertEquals(EvidenceSufficiency.SUFFICIENT,
                assessment.sufficiency());
        assertEquals(assessedAt, assessment.assessedAt());
    }

    @Test
    @DisplayName("Debe rechazar una suficiencia de evidencia nula")
    void shouldRejectNullSufficiency() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceAssessment(
                        null, List.of(), List.of(), List.of(),
                        "Evaluación válida", assessedAt));
    }

    @Test
    @DisplayName("Debe rechazar una lista de identificadores nula")
    void shouldRejectNullEvidenceIds() {
        assertThrows(NullPointerException.class,
                () -> new EvidenceAssessment(
                        EvidenceSufficiency.SUFFICIENT,
                        null, List.of(), List.of(),
                        "Evaluación válida", assessedAt));
    }

    @Test
    @DisplayName("Debe rechazar identificadores nulos dentro de la lista")
    void shouldRejectNullEvidenceIdElements() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceAssessment(
                        EvidenceSufficiency.SUFFICIENT,
                        java.util.Arrays.asList(UUID.randomUUID(), null),
                        List.of(), List.of(),
                        "Evaluación válida", assessedAt));
    }

    @Test
    @DisplayName("Debe rechazar una lista de limitaciones nula")
    void shouldRejectNullLimitations() {
        assertThrows(NullPointerException.class,
                () -> new EvidenceAssessment(
                        EvidenceSufficiency.SUFFICIENT,
                        List.of(), null, List.of(),
                        "Evaluación válida", assessedAt));
    }

    @Test
    @DisplayName("Debe rechazar limitaciones vacías o nulas")
    void shouldRejectInvalidLimitations() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new EvidenceAssessment(
                                EvidenceSufficiency.SUFFICIENT,
                                List.of(), List.of(" "),
                                List.of(), "Evaluación válida", assessedAt)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new EvidenceAssessment(
                                EvidenceSufficiency.SUFFICIENT,
                                List.of(),
                                java.util.Arrays.asList("Válida", null),
                                List.of(), "Evaluación válida", assessedAt))
        );
    }

    @Test
    @DisplayName("Debe rechazar una lista de incertidumbres nula")
    void shouldRejectNullUnresolvedUncertainties() {
        assertThrows(NullPointerException.class,
                () -> new EvidenceAssessment(
                        EvidenceSufficiency.SUFFICIENT,
                        List.of(), List.of(), null,
                        "Evaluación válida", assessedAt));
    }

    @Test
    @DisplayName("Debe rechazar incertidumbres vacías o nulas")
    void shouldRejectInvalidUnresolvedUncertainties() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new EvidenceAssessment(
                                EvidenceSufficiency.SUFFICIENT,
                                List.of(), List.of(), List.of(" "),
                                "Evaluación válida", assessedAt)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new EvidenceAssessment(
                                EvidenceSufficiency.SUFFICIENT,
                                List.of(), List.of(),
                                java.util.Arrays.asList("Pendiente", null),
                                "Evaluación válida", assessedAt))
        );
    }

    @Test
    @DisplayName("Debe rechazar una justificación nula o vacía")
    void shouldRejectInvalidRationale() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new EvidenceAssessment(
                                EvidenceSufficiency.SUFFICIENT,
                                List.of(), List.of(), List.of(),
                                null, assessedAt)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new EvidenceAssessment(
                                EvidenceSufficiency.SUFFICIENT,
                                List.of(), List.of(), List.of(),
                                "   ", assessedAt))
        );
    }

    @Test
    @DisplayName("Debe rechazar una fecha de evaluación nula")
    void shouldRejectNullAssessmentTimestamp() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceAssessment(
                        EvidenceSufficiency.SUFFICIENT,
                        List.of(), List.of(), List.of(),
                        "Evaluación válida", null));
    }

    @Test
    @DisplayName("Debe permitir listas vacías cuando no hay elementos pendientes")
    void shouldAllowEmptyLists() {
        EvidenceAssessment assessment = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(), List.of(), List.of(),
                "No se identificaron limitaciones ni incertidumbres",
                assessedAt
        );

        assertTrue(assessment.evidenceIds().isEmpty());
        assertTrue(assessment.limitations().isEmpty());
        assertTrue(assessment.unresolvedUncertainties().isEmpty());
    }

    @Test
    @DisplayName("Debe devolver colecciones inmutables")
    void shouldReturnImmutableCollections() {
        EvidenceAssessment assessment = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of("Limitación"),
                List.of("Incertidumbre"),
                "Evaluación válida",
                assessedAt
        );

        assertAll(
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> assessment.evidenceIds().add(UUID.randomUUID())),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> assessment.limitations().add("Otra limitación")),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> assessment.unresolvedUncertainties()
                                .add("Otra incertidumbre"))
        );
    }

    @Test
    @DisplayName("Debe permitir evaluación cuando la suficiencia lo admite")
    void shouldAllowEvaluationWithSufficientEvidence() {
        EvidenceAssessment assessment = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(), List.of(), List.of(),
                "Evidencia suficiente", assessedAt
        );

        assertTrue(assessment.allowsEvaluation());
    }

    @Test
    @DisplayName("No debe permitir evaluación cuando la evidencia es insuficiente")
    void shouldNotAllowEvaluationWithInsufficientEvidence() {
        EvidenceAssessment assessment = new EvidenceAssessment(
                EvidenceSufficiency.INSUFFICIENT,
                List.of(), List.of(), List.of(),
                "Evidencia insuficiente", assessedAt
        );

        assertFalse(assessment.allowsEvaluation());
    }

    @Test
    @DisplayName("Debe eliminar espacios externos de los textos")
    void shouldTrimTextValues() {
        EvidenceAssessment assessment = new EvidenceAssessment(
                EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS,
                List.of(),
                List.of("  Limitación  "),
                List.of("  Incertidumbre  "),
                "  Justificación  ",
                assessedAt
        );

        assertEquals("Limitación", assessment.limitations().getFirst());
        assertEquals("Incertidumbre",
                assessment.unresolvedUncertainties().getFirst());
        assertEquals("Justificación", assessment.rationale());
    }
}
