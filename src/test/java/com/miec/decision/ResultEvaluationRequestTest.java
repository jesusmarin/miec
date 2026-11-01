package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;
import com.miec.evidence.EvidenceSufficiency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link ResultEvaluationRequest}.
 *
 * <p>Verifican las invariantes de construcción, la normalización de datos,
 * la inmutabilidad de las listas y el tratamiento de la ausencia de
 * resultados observados como entrada válida.
 */
@DisplayName("Pruebas de la solicitud de evaluación de resultados MIEC")
class ResultEvaluationRequestTest {

    private static final String BASELINE_REFERENCE = "baseline-001";
    private static final String CRITERIA_VERSION = "criteria-0.1.0";

    private final UUID cycleId = UUID.randomUUID();
    private final UUID objectiveId = UUID.randomUUID();
    private final UUID indicatorId = UUID.randomUUID();
    private final Instant evaluatedAt = Instant.now();

    private EvidenceAssessment evidenceAssessment;

    @BeforeEach
    void setUp() {
        evidenceAssessment = new EvidenceAssessment(
                EvidenceSufficiency.SUFFICIENT,
                List.of(UUID.randomUUID()),
                List.of(),
                List.of(),
                "Evidencia suficiente para evaluar",
                Instant.now()
        );
    }

    /**
     * Construye una solicitud válida con los resultados observados indicados.
     *
     * @param observedResults resultados a incluir
     * @return solicitud válida utilizada como base de las pruebas
     */
    private ResultEvaluationRequest requestWith(
            List<ResultEvaluationRequest.ObservedResult> observedResults) {

        return new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidenceAssessment,
                observedResults,
                BASELINE_REFERENCE,
                CRITERIA_VERSION,
                evaluatedAt
        );
    }

    @Test
    @DisplayName("Debe crear una solicitud válida conservando todos sus datos")
    void shouldCreateValidRequest() {
        ResultEvaluationRequest.ObservedResult result =
                new ResultEvaluationRequest.ObservedResult(
                        indicatorId, "100", "Contexto de prueba");

        ResultEvaluationRequest request = requestWith(List.of(result));

        assertThat(request.cycleId()).isEqualTo(cycleId);
        assertThat(request.objectiveId()).isEqualTo(objectiveId);
        assertThat(request.evidenceAssessment()).isEqualTo(evidenceAssessment);
        assertThat(request.observedResults()).containsExactly(result);
        assertThat(request.baselineReference()).isEqualTo(BASELINE_REFERENCE);
        assertThat(request.criteriaVersionId()).isEqualTo(CRITERIA_VERSION);
        assertThat(request.evaluatedAt()).isEqualTo(evaluatedAt);
    }

    @Test
    @DisplayName("Debe aceptar una solicitud sin resultados observados como entrada válida")
    void shouldAcceptRequestWithoutObservedResults() {
        ResultEvaluationRequest request = requestWith(List.of());

        assertThat(request.observedResults()).isEmpty();
        assertThat(request.hasNoObservedResults()).isTrue();
        assertThat(request.observedResultsCount()).isZero();
    }

    @Test
    @DisplayName("Debe reportar presencia de resultados cuando se aporta al menos uno")
    void shouldReportObservedResultsPresence() {
        ResultEvaluationRequest.ObservedResult result =
                new ResultEvaluationRequest.ObservedResult(indicatorId, "50", null);

        ResultEvaluationRequest request = requestWith(List.of(result));

        assertThat(request.hasNoObservedResults()).isFalse();
        assertThat(request.observedResultsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin identificador de ciclo")
    void shouldRejectNullCycleId() {
        assertThatThrownBy(() -> new ResultEvaluationRequest(
                null,
                objectiveId,
                evidenceAssessment,
                List.of(),
                BASELINE_REFERENCE,
                CRITERIA_VERSION,
                evaluatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ciclo");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin identificador de objetivo")
    void shouldRejectNullObjectiveId() {
        assertThatThrownBy(() -> new ResultEvaluationRequest(
                cycleId,
                null,
                evidenceAssessment,
                List.of(),
                BASELINE_REFERENCE,
                CRITERIA_VERSION,
                evaluatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("objetivo");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin evaluación de evidencia")
    void shouldRejectNullEvidenceAssessment() {
        assertThatThrownBy(() -> new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                null,
                List.of(),
                BASELINE_REFERENCE,
                CRITERIA_VERSION,
                evaluatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("evidencia");
    }

    @Test
    @DisplayName("Debe rechazar una lista de resultados observados nula")
    void shouldRejectNullObservedResultsList() {
        assertThatThrownBy(() -> requestWith(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lista vacía");
    }

    @Test
    @DisplayName("Debe rechazar una lista de resultados observados con elementos nulos")
    void shouldRejectNullObservedResultElement() {
        List<ResultEvaluationRequest.ObservedResult> withNull =
                Arrays.asList(
                        new ResultEvaluationRequest.ObservedResult(indicatorId, "100", null),
                        null
                );

        assertThatThrownBy(() -> requestWith(withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elementos nulos");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin referencia a la línea base")
    void shouldRejectNullBaselineReference() {
        assertThatThrownBy(() -> new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidenceAssessment,
                List.of(),
                null,
                CRITERIA_VERSION,
                evaluatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("línea base");
    }

    @Test
    @DisplayName("Debe rechazar una referencia a la línea base en blanco")
    void shouldRejectBlankBaselineReference() {
        assertThatThrownBy(() -> new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidenceAssessment,
                List.of(),
                "   ",
                CRITERIA_VERSION,
                evaluatedAt
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("línea base");
    }

    @Test
    @DisplayName("Debe normalizar la referencia a la línea base eliminando espacios sobrantes")
    void shouldTrimBaselineReference() {
        ResultEvaluationRequest request = new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidenceAssessment,
                List.of(),
                "  " + BASELINE_REFERENCE + "  ",
                CRITERIA_VERSION,
                evaluatedAt
        );

        assertThat(request.baselineReference()).isEqualTo(BASELINE_REFERENCE);
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin versión de criterios")
    void shouldRejectNullCriteriaVersion() {
        assertThatThrownBy(() -> new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidenceAssessment,
                List.of(),
                BASELINE_REFERENCE,
                null,
                evaluatedAt
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("versión de criterios");
    }

    @Test
    @DisplayName("Debe rechazar una versión de criterios en blanco")
    void shouldRejectBlankCriteriaVersion() {
        assertThatThrownBy(() -> new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidenceAssessment,
                List.of(),
                BASELINE_REFERENCE,
                "   ",
                evaluatedAt
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versión de criterios");
    }

    @Test
    @DisplayName("Debe normalizar la versión de criterios eliminando espacios sobrantes")
    void shouldTrimCriteriaVersion() {
        ResultEvaluationRequest request = new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidenceAssessment,
                List.of(),
                BASELINE_REFERENCE,
                "  " + CRITERIA_VERSION + "  ",
                evaluatedAt
        );

        assertThat(request.criteriaVersionId()).isEqualTo(CRITERIA_VERSION);
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin instante de evaluación")
    void shouldRejectNullEvaluatedAt() {
        assertThatThrownBy(() -> new ResultEvaluationRequest(
                cycleId,
                objectiveId,
                evidenceAssessment,
                List.of(),
                BASELINE_REFERENCE,
                CRITERIA_VERSION,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("instante de evaluación");
    }

    @Test
    @DisplayName("Debe conservar el orden de los resultados observados recibidos")
    void shouldPreserveObservedResultsOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        ResultEvaluationRequest.ObservedResult r1 =
                new ResultEvaluationRequest.ObservedResult(third, "30", null);
        ResultEvaluationRequest.ObservedResult r2 =
                new ResultEvaluationRequest.ObservedResult(first, "10", null);
        ResultEvaluationRequest.ObservedResult r3 =
                new ResultEvaluationRequest.ObservedResult(second, "20", null);

        ResultEvaluationRequest request = requestWith(List.of(r1, r2, r3));

        assertThat(request.observedResults()).containsExactly(r1, r2, r3);
    }

    @Test
    @DisplayName("Debe aislar la solicitud de modificaciones posteriores a la lista original")
    void shouldDefensivelyCopyObservedResultsList() {
        List<ResultEvaluationRequest.ObservedResult> mutable = new ArrayList<>();
        ResultEvaluationRequest.ObservedResult original =
                new ResultEvaluationRequest.ObservedResult(indicatorId, "100", null);
        mutable.add(original);

        ResultEvaluationRequest request = requestWith(mutable);
        mutable.add(new ResultEvaluationRequest.ObservedResult(UUID.randomUUID(), "200", null));

        assertThat(request.observedResults()).containsExactly(original);
        assertThat(request.observedResultsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Debe exponer una lista de resultados observados inmutable")
    void shouldExposeImmutableObservedResultsList() {
        ResultEvaluationRequest.ObservedResult result =
                new ResultEvaluationRequest.ObservedResult(indicatorId, "100", null);

        ResultEvaluationRequest request = requestWith(List.of(result));

        assertThatThrownBy(() -> request.observedResults().add(
                new ResultEvaluationRequest.ObservedResult(UUID.randomUUID(), "200", null)
        ))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Debe crear un resultado observado válido con contexto de medición")
    void shouldCreateObservedResultWithContext() {
        ResultEvaluationRequest.ObservedResult result =
                new ResultEvaluationRequest.ObservedResult(
                        indicatorId, "100", "Contexto de prueba");

        assertThat(result.indicatorId()).isEqualTo(indicatorId);
        assertThat(result.observedValue()).isEqualTo("100");
        assertThat(result.measurementContext()).isEqualTo("Contexto de prueba");
    }

    @Test
    @DisplayName("Debe crear un resultado observado válido sin contexto de medición")
    void shouldCreateObservedResultWithoutContext() {
        ResultEvaluationRequest.ObservedResult result =
                new ResultEvaluationRequest.ObservedResult(indicatorId, "100", null);

        assertThat(result.indicatorId()).isEqualTo(indicatorId);
        assertThat(result.observedValue()).isEqualTo("100");
        assertThat(result.measurementContext()).isNull();
    }

    @Test
    @DisplayName("Debe rechazar un resultado observado sin identificador de indicador")
    void shouldRejectObservedResultWithoutIndicatorId() {
        assertThatThrownBy(() ->
                new ResultEvaluationRequest.ObservedResult(null, "100", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("indicador");
    }

    @Test
    @DisplayName("Debe rechazar un resultado observado sin valor observado")
    void shouldRejectObservedResultWithoutObservedValue() {
        assertThatThrownBy(() ->
                new ResultEvaluationRequest.ObservedResult(indicatorId, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("valor observado");
    }

    @Test
    @DisplayName("Debe rechazar un resultado observado con valor en blanco")
    void shouldRejectObservedResultWithBlankValue() {
        assertThatThrownBy(() ->
                new ResultEvaluationRequest.ObservedResult(indicatorId, "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valor observado");
    }

    @Test
    @DisplayName("Debe normalizar el valor observado eliminando espacios sobrantes")
    void shouldTrimObservedValue() {
        ResultEvaluationRequest.ObservedResult result =
                new ResultEvaluationRequest.ObservedResult(indicatorId, "  100  ", null);

        assertThat(result.observedValue()).isEqualTo("100");
    }

    @Test
    @DisplayName("Debe normalizar el contexto de medición eliminando espacios sobrantes")
    void shouldTrimMeasurementContext() {
        ResultEvaluationRequest.ObservedResult result =
                new ResultEvaluationRequest.ObservedResult(indicatorId, "100", "  contexto  ");

        assertThat(result.measurementContext()).isEqualTo("contexto");
    }

    @Test
    @DisplayName("Debe convertir un contexto de medición en blanco a nulo")
    void shouldConvertBlankContextToNull() {
        ResultEvaluationRequest.ObservedResult result =
                new ResultEvaluationRequest.ObservedResult(indicatorId, "100", "   ");

        assertThat(result.measurementContext()).isNull();
    }
}