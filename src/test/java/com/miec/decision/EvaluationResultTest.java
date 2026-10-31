
package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;
import com.miec.evidence.EvidenceSufficiency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationResultTest {

    private final UUID id = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();
    private final UUID objectiveId = UUID.randomUUID();
    private final Instant evaluatedAt =
            Instant.parse("2026-10-30T12:00:00Z");

    private EvidenceAssessment createAssessment(
            EvidenceSufficiency sufficiency) {
        return new EvidenceAssessment(
                sufficiency,
                List.of(),
                List.of(),
                List.of(),
                "Evaluación de evidencia",
                evaluatedAt
        );
    }

    private EvaluationResult createValidResult() {
        return new EvaluationResult(
                id,
                cycleId,
                objectiveId,
                ResultClassification.FAVORABLE,
                createAssessment(EvidenceSufficiency.SUFFICIENT),
                ConfidenceLevel.HIGH,
                "Resultado favorable respaldado por evidencia",
                evaluatedAt
        );
    }

    @Test
    @DisplayName("Debe crear un resultado de evaluación con datos válidos")
    void shouldCreateEvaluationResultWithValidData() {
        EvaluationResult result = createValidResult();

        assertNotNull(result);
        assertEquals(id, result.id());
        assertEquals(cycleId, result.cycleId());
        assertEquals(objectiveId, result.objectiveId());
        assertEquals(ResultClassification.FAVORABLE,
                result.classification());
        assertEquals(ConfidenceLevel.HIGH, result.confidenceLevel());
        assertEquals(evaluatedAt, result.evaluatedAt());
    }

    @Test
    @DisplayName("Debe rechazar un identificador de evaluación nulo")
    void shouldRejectNullEvaluationId() {
        assertThrows(NullPointerException.class,
                () -> new EvaluationResult(
                        null, cycleId, objectiveId,
                        ResultClassification.FAVORABLE,
                        createAssessment(EvidenceSufficiency.SUFFICIENT),
                        ConfidenceLevel.HIGH, "Justificación", evaluatedAt));
    }

    @Test
    @DisplayName("Debe rechazar un identificador de ciclo nulo")
    void shouldRejectNullCycleId() {
        assertThrows(NullPointerException.class,
                () -> new EvaluationResult(
                        id, null, objectiveId,
                        ResultClassification.FAVORABLE,
                        createAssessment(EvidenceSufficiency.SUFFICIENT),
                        ConfidenceLevel.HIGH, "Justificación", evaluatedAt));
    }

    @Test
    @DisplayName("Debe rechazar un identificador de objetivo nulo")
    void shouldRejectNullObjectiveId() {
        assertThrows(NullPointerException.class,
                () -> new EvaluationResult(
                        id, cycleId, null,
                        ResultClassification.FAVORABLE,
                        createAssessment(EvidenceSufficiency.SUFFICIENT),
                        ConfidenceLevel.HIGH, "Justificación", evaluatedAt));
    }

    @Test
    @DisplayName("Debe rechazar una clasificación nula")
    void shouldRejectNullClassification() {
        assertThrows(NullPointerException.class,
                () -> new EvaluationResult(
                        id, cycleId, objectiveId, null,
                        createAssessment(EvidenceSufficiency.SUFFICIENT),
                        ConfidenceLevel.HIGH, "Justificación", evaluatedAt));
    }

    @Test
    @DisplayName("Debe rechazar una evaluación de evidencia nula")
    void shouldRejectNullEvidenceAssessment() {
        assertThrows(NullPointerException.class,
                () -> new EvaluationResult(
                        id, cycleId, objectiveId,
                        ResultClassification.FAVORABLE,
                        null, ConfidenceLevel.HIGH,
                        "Justificación", evaluatedAt));
    }

    @Test
    @DisplayName("Debe rechazar un nivel de confianza nulo")
    void shouldRejectNullConfidenceLevel() {
        assertThrows(NullPointerException.class,
                () -> new EvaluationResult(
                        id, cycleId, objectiveId,
                        ResultClassification.FAVORABLE,
                        createAssessment(EvidenceSufficiency.SUFFICIENT),
                        null, "Justificación", evaluatedAt));
    }

    @Test
    @DisplayName("Debe rechazar una fecha de evaluación nula")
    void shouldRejectNullEvaluationTimestamp() {
        assertThrows(NullPointerException.class,
                () -> new EvaluationResult(
                        id, cycleId, objectiveId,
                        ResultClassification.FAVORABLE,
                        createAssessment(EvidenceSufficiency.SUFFICIENT),
                        ConfidenceLevel.HIGH, "Justificación", null));
    }

    @Test
    @DisplayName("Debe rechazar una justificación nula o vacía")
    void shouldRejectInvalidRationale() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new EvaluationResult(
                                id, cycleId, objectiveId,
                                ResultClassification.FAVORABLE,
                                createAssessment(EvidenceSufficiency.SUFFICIENT),
                                ConfidenceLevel.HIGH, null, evaluatedAt)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new EvaluationResult(
                                id, cycleId, objectiveId,
                                ResultClassification.FAVORABLE,
                                createAssessment(EvidenceSufficiency.SUFFICIENT),
                                ConfidenceLevel.HIGH, "   ", evaluatedAt))
        );
    }

    @Test
    @DisplayName("Debe rechazar resultados concluyentes con evidencia no evaluable")
    void shouldRejectConclusiveResultWithNotEvaluableEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult(
                        id, cycleId, objectiveId,
                        ResultClassification.FAVORABLE,
                        createAssessment(EvidenceSufficiency.NOT_EVALUABLE),
                        ConfidenceLevel.HIGH, "Justificación", evaluatedAt));
    }

    @Test
    @DisplayName("Debe rechazar resultados concluyentes con evidencia insuficiente")
    void shouldRejectConclusiveResultWithInsufficientEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationResult(
                        id, cycleId, objectiveId,
                        ResultClassification.UNFAVORABLE,
                        createAssessment(EvidenceSufficiency.INSUFFICIENT),
                        ConfidenceLevel.MODERATE, "Justificación", evaluatedAt));
    }

    @Test
    @DisplayName("Debe aceptar un resultado inconcluso con evidencia no evaluable")
    void shouldAcceptInconclusiveResultWithNotEvaluableEvidence() {
        EvaluationResult result = new EvaluationResult(
                id, cycleId, objectiveId,
                ResultClassification.INCONCLUSIVE,
                createAssessment(EvidenceSufficiency.NOT_EVALUABLE),
                ConfidenceLevel.UNDETERMINED,
                "No existen datos mínimos para evaluar",
                evaluatedAt
        );

        assertEquals(ResultClassification.INCONCLUSIVE,
                result.classification());
    }

    @Test
    @DisplayName("Debe aceptar un resultado inconcluso con evidencia insuficiente")
    void shouldAcceptInconclusiveResultWithInsufficientEvidence() {
        EvaluationResult result = new EvaluationResult(
                id, cycleId, objectiveId,
                ResultClassification.INCONCLUSIVE,
                createAssessment(EvidenceSufficiency.INSUFFICIENT),
                ConfidenceLevel.UNDETERMINED,
                "La evidencia disponible no permite concluir",
                evaluatedAt
        );

        assertEquals(ResultClassification.INCONCLUSIVE,
                result.classification());
    }

    @Test
    @DisplayName("Debe aceptar resultados con evidencia suficiente")
    void shouldAcceptResultWithSufficientEvidence() {
        EvaluationResult result = new EvaluationResult(
                id, cycleId, objectiveId,
                ResultClassification.PARTIAL,
                createAssessment(EvidenceSufficiency.SUFFICIENT),
                ConfidenceLevel.MODERATE,
                "Resultado parcialmente favorable",
                evaluatedAt
        );

        assertEquals(ResultClassification.PARTIAL,
                result.classification());
    }

    @Test
    @DisplayName("Debe aceptar resultados con evidencia aceptable y limitaciones")
    void shouldAcceptResultWithLimitedEvidence() {
        EvaluationResult result = new EvaluationResult(
                id, cycleId, objectiveId,
                ResultClassification.NEUTRAL,
                createAssessment(
                        EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS),
                ConfidenceLevel.LOW,
                "Evaluación con limitaciones documentadas",
                evaluatedAt
        );

        assertEquals(ResultClassification.NEUTRAL,
                result.classification());
    }

    @Test
    @DisplayName("Debe aceptar resultados con evidencia corroborada")
    void shouldAcceptResultWithCorroboratedEvidence() {
        EvaluationResult result = new EvaluationResult(
                id, cycleId, objectiveId,
                ResultClassification.FAVORABLE,
                createAssessment(EvidenceSufficiency.CORROBORATED),
                ConfidenceLevel.HIGH,
                "Resultado corroborado",
                evaluatedAt
        );

        assertEquals(ResultClassification.FAVORABLE,
                result.classification());
    }

    @Test
    @DisplayName("Debe conservar la independencia entre clasificación y confianza")
    void shouldKeepClassificationAndConfidenceIndependent() {
        EvaluationResult result = new EvaluationResult(
                id, cycleId, objectiveId,
                ResultClassification.FAVORABLE,
                createAssessment(EvidenceSufficiency.SUFFICIENT),
                ConfidenceLevel.LOW,
                "Resultado favorable con incertidumbre",
                evaluatedAt
        );

        assertEquals(ResultClassification.FAVORABLE,
                result.classification());
        assertEquals(ConfidenceLevel.LOW, result.confidenceLevel());
    }
}

