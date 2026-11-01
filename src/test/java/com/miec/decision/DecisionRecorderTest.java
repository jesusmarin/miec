package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;
import com.miec.evidence.EvidenceSufficiency;
import com.miec.state.CycleState;
import com.miec.state.TransitionProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link DecisionRecorder}.
 *
 * <p>Estas pruebas verifican que una implementación concreta del registrador
 * cumple con los invariantes de F07 y F02. Se utiliza una implementación
 * simulada con almacenamiento en memoria.
 */
@DisplayName("Pruebas del contrato DecisionRecorder MIEC")
class DecisionRecorderTest {

    /**
     * Implementación simulada del registrador para pruebas.
     *
     * <p>Esta implementación utiliza un mapa en memoria para persistir decisiones.
     */
    private static class StubDecisionRecorder implements DecisionRecorder {

        private final Map<UUID, DecisionOutcome> storage = new HashMap<>();

        @Override
        public UUID record(DecisionOutcome decisionOutcome) {
            if (decisionOutcome == null) {
                throw new NullPointerException("La decisión es obligatoria");
            }

            UUID decisionId = UUID.randomUUID();
            storage.put(decisionId, decisionOutcome);
            return decisionId;
        }

        @Override
        public DecisionOutcome retrieve(UUID decisionId) {
            if (decisionId == null) {
                throw new NullPointerException("El identificador es obligatorio");
            }

            return storage.get(decisionId);
        }
    }

    private DecisionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new StubDecisionRecorder();
    }

    @Test
    @DisplayName("Debe registrar una decisión y devolver un identificador único")
    void shouldRecordDecisionAndReturnId() {
        DecisionOutcome outcome = createDecisionOutcome();

        UUID decisionId = recorder.record(outcome);

        assertThat(decisionId).isNotNull();
    }

    @Test
    @DisplayName("Debe recuperar una decisión registrada por su identificador")
    void shouldRetrieveRecordedDecision() {
        DecisionOutcome outcome = createDecisionOutcome();

        UUID decisionId = recorder.record(outcome);
        DecisionOutcome retrieved = recorder.retrieve(decisionId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved).isEqualTo(outcome);
    }

    @Test
    @DisplayName("Debe devolver nulo al recuperar una decisión inexistente")
    void shouldReturnNullForNonExistentDecision() {
        UUID nonExistentId = UUID.randomUUID();

        DecisionOutcome retrieved = recorder.retrieve(nonExistentId);

        assertThat(retrieved).isNull();
    }

    @Test
    @DisplayName("Debe rechazar el registro de una decisión nula")
    void shouldRejectNullDecision() {
        assertThatThrownBy(() -> recorder.record(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obligatoria");
    }

    @Test
    @DisplayName("Debe rechazar la recuperación con identificador nulo")
    void shouldRejectNullDecisionId() {
        assertThatThrownBy(() -> recorder.retrieve(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obligatorio");
    }

    @Test
    @DisplayName("Debe permitir registrar múltiples decisiones")
    void shouldAllowMultipleDecisions() {
        DecisionOutcome outcome1 = createDecisionOutcome();
        DecisionOutcome outcome2 = createDecisionOutcome();

        UUID id1 = recorder.record(outcome1);
        UUID id2 = recorder.record(outcome2);

        assertThat(id1).isNotEqualTo(id2);

        DecisionOutcome retrieved1 = recorder.retrieve(id1);
        DecisionOutcome retrieved2 = recorder.retrieve(id2);

        assertThat(retrieved1).isEqualTo(outcome1);
        assertThat(retrieved2).isEqualTo(outcome2);
    }

    private DecisionOutcome createDecisionOutcome() {
        EvidenceAssessment evidence = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(),
                "Evidencia suficiente",
                Instant.now()
        );

        EvaluationResult evaluation = new EvaluationResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ResultClassification.FAVORABLE,
                evidence,
                ConfidenceLevel.HIGH,
                "Evaluación de prueba",
                Instant.now()
        );

        AlternativeAssessment alternative = new AlternativeAssessment(
                DecisionAction.CLOSE_CYCLE,
                true,
                "Alternativa admisible",
                "Beneficio esperado",
                List.of(),
                "Bajo",
                false
        );

        DecisionRecommendation recommendation = new DecisionRecommendation(
                DecisionAction.CLOSE_CYCLE,
                "Recomendación de prueba",
                List.of(UUID.randomUUID()),
                Instant.now()
        );

        TransitionProposal transition = new TransitionProposal(
                CycleState.E09_DECIDIENDO,
                CycleState.E14_FINALIZADO,
                "Transición de prueba",
                Instant.now()
        );

        return new DecisionOutcome(
                evaluation,
                List.of(alternative),
                recommendation,
                transition
        );
    }
}