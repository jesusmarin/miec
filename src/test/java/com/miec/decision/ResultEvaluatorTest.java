package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;
import com.miec.evidence.EvidenceSufficiency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link ResultEvaluator}.
 *
 * <p>Estas pruebas verifican que una implementación concreta del evaluador
 * cumple con los invariantes de F07 y F02. Se utiliza una implementación
 * simulada para validar el comportamiento esperado.
 */
@DisplayName("Pruebas del contrato ResultEvaluator MIEC")
class ResultEvaluatorTest {

    /**
     * Implementación simulada del evaluador para pruebas.
     *
     * <p>Esta implementación devuelve una evaluación basada en la suficiencia
     * de la evidencia y la cantidad de resultados observados.
     */
    private static class StubResultEvaluator implements ResultEvaluator {

        @Override
        public EvaluationResult evaluate(ResultEvaluationRequest request) {
            if (request == null) {
                throw new NullPointerException("El request es obligatorio");
            }

            EvidenceAssessment evidence = request.evidenceAssessment();
            ResultClassification classification;
            ConfidenceLevel confidence;
            String rationale;

            if (!evidence.allowsEvaluation()) {
                classification = ResultClassification.INCONCLUSIVE;
                confidence = ConfidenceLevel.UNDETERMINED;
                rationale = "La evidencia es insuficiente para emitir una conclusión";
            } else if (request.hasNoObservedResults()) {
                classification = ResultClassification.NEUTRAL;
                confidence = ConfidenceLevel.LOW;
                rationale = "No se observaron resultados para evaluar";
            } else {
                classification = ResultClassification.FAVORABLE;
                confidence = ConfidenceLevel.HIGH;
                rationale = "Los resultados cumplen los criterios de aceptación";
            }

            return new EvaluationResult(
                    UUID.randomUUID(),
                    request.cycleId(),
                    request.objectiveId(),
                    classification,
                    evidence,
                    confidence,
                    rationale,
                    Instant.now()
            );
        }
    }

    private final ResultEvaluator evaluator = new StubResultEvaluator();

    @Test
    @DisplayName("Debe clasificar como INCONCLUSIVE cuando la evidencia es insuficiente")
    void shouldClassifyAsInconclusiveWithInsufficientEvidence() {
        EvidenceAssessment evidence = new EvidenceAssessment(
                EvidenceSufficiency.INSUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of("Evidencia limitada"),
                List.of(),
                "Evidencia insuficiente",
                Instant.now()
        );

        ResultEvaluationRequest request = new ResultEvaluationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                evidence,
                List.of(),
                "baseline-001",
                "criteria-0.1.0",
                Instant.now()
        );

        EvaluationResult result = evaluator.evaluate(request);

        assertThat(result.classification()).isEqualTo(ResultClassification.INCONCLUSIVE);
        assertThat(result.confidenceLevel()).isEqualTo(ConfidenceLevel.UNDETERMINED);
    }

    @Test
    @DisplayName("Debe clasificar como NEUTRAL cuando no hay resultados observados")
    void shouldClassifyAsNeutralWithNoObservedResults() {
        EvidenceAssessment evidence = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(),
                "Evidencia suficiente",
                Instant.now()
        );

        ResultEvaluationRequest request = new ResultEvaluationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                evidence,
                List.of(),
                "baseline-001",
                "criteria-0.1.0",
                Instant.now()
        );

        EvaluationResult result = evaluator.evaluate(request);

        assertThat(result.classification()).isEqualTo(ResultClassification.NEUTRAL);
        assertThat(result.confidenceLevel()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    @DisplayName("Debe clasificar como FAVORABLE cuando hay resultados y evidencia suficiente")
    void shouldClassifyAsFavorableWithResultsAndSufficientEvidence() {
        EvidenceAssessment evidence = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(),
                "Evidencia suficiente",
                Instant.now()
        );

        ResultEvaluationRequest.ObservedResult observed =
                new ResultEvaluationRequest.ObservedResult(UUID.randomUUID(), "100", null);

        ResultEvaluationRequest request = new ResultEvaluationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                evidence,
                List.of(observed),
                "baseline-001",
                "criteria-0.1.0",
                Instant.now()
        );

        EvaluationResult result = evaluator.evaluate(request);

        assertThat(result.classification()).isEqualTo(ResultClassification.FAVORABLE);
        assertThat(result.confidenceLevel()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    @DisplayName("Debe rechazar un request nulo")
    void shouldRejectNullRequest() {
        assertThatThrownBy(() -> evaluator.evaluate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obligatorio");
    }

    @Test
    @DisplayName("Debe conservar los identificadores de ciclo y objetivo en el resultado")
    void shouldPreserveCycleAndObjectiveIds() {
        UUID cycleId = UUID.randomUUID();
        UUID objectiveId = UUID.randomUUID();

        EvidenceAssessment evidence = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(),
                "Evidencia suficiente",
                Instant.now()
        );

        ResultEvaluationRequest request = new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidence,
                List.of(),
                "baseline-001",
                "criteria-0.1.0",
                Instant.now()
        );

        EvaluationResult result = evaluator.evaluate(request);

        assertThat(result.cycleId()).isEqualTo(cycleId);
        assertThat(result.objectiveId()).isEqualTo(objectiveId);
    }

    @Test
    @DisplayName("Debe proporcionar un rationale que justifique la clasificación")
    void shouldProvideRationaleForClassification() {
        EvidenceAssessment evidence = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(),
                "Evidencia suficiente",
                Instant.now()
        );

        ResultEvaluationRequest request = new ResultEvaluationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                evidence,
                List.of(),
                "baseline-001",
                "criteria-0.1.0",
                Instant.now()
        );

        EvaluationResult result = evaluator.evaluate(request);

        assertThat(result.rationale()).isNotBlank();
        assertThat(result.evaluatedAt()).isNotNull();
    }
}