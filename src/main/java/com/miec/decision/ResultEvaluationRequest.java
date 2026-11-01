package com.miec.decision;

import com.miec.evidence.EvidenceAssessment;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitud de evaluación de resultados (MIEC-F02, MD-02).
 *
 * <p>Representa la entrada del proceso que compara los resultados observados
 * con los criterios de aceptación y produce una evaluación estructurada.
 *
 * <p>Este contrato transporta la evaluación de evidencia completa, no solo
 * la suficiencia, para que el evaluador pueda considerar las limitaciones
 * e incertidumbres registradas al emitir su conclusión.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El ciclo y el objetivo son obligatorios: sin objetivo no existe
 *       criterio de evaluación.</li>
 *   <li>La evaluación de evidencia es obligatoria y debe provenir de un
 *       validador previo (MD-01).</li>
 *   <li>Los resultados observados pueden estar vacíos si no se dispone de
 *       mediciones, pero no ser nulos.</li>
 *   <li>La versión de criterios es obligatoria para trazabilidad (F07-R12).</li>
 * </ul>
 *
 * @param cycleId           identificador del ciclo que solicita la evaluación
 * @param objectiveId       identificador del objetivo frente al cual se
 *                          evaluarán los resultados
 * @param evidenceAssessment evaluación de la evidencia disponible (de MD-01)
 * @param observedResults   resultados medidos después de la intervención
 * @param baselineReference referencia a la línea base contra la cual comparar
 * @param criteriaVersionId versión de los criterios de evaluación aplicables
 * @param evaluatedAt       instante en que se formula la solicitud
 */
public record ResultEvaluationRequest(
        UUID cycleId,
        UUID objectiveId,
        EvidenceAssessment evidenceAssessment,
        List<ObservedResult> observedResults,
        String baselineReference,
        String criteriaVersionId,
        Instant evaluatedAt
) {

    /**
     * Constructor compacto: valida invariantes y normaliza los datos.
     *
     * @throws NullPointerException     si {@code cycleId}, {@code objectiveId},
     *                                  {@code evidenceAssessment},
     *                                  {@code observedResults},
     *                                  {@code baselineReference},
     *                                  {@code criteriaVersionId} o
     *                                  {@code evaluatedAt} son nulos
     * @throws IllegalArgumentException si {@code baselineReference} o
     *                                  {@code criteriaVersionId} están en blanco
     */
    public ResultEvaluationRequest {
        Objects.requireNonNull(cycleId,
                "El identificador del ciclo es obligatorio");

        Objects.requireNonNull(objectiveId,
                "El identificador del objetivo es obligatorio");

        Objects.requireNonNull(evidenceAssessment,
                "La evaluación de evidencia es obligatoria");

        observedResults = validateObservedResults(observedResults);

        Objects.requireNonNull(baselineReference,
                "La referencia a la línea base es obligatoria");

        if (baselineReference.isBlank()) {
            throw new IllegalArgumentException(
                    "La referencia a la línea base es obligatoria");
        }

        baselineReference = baselineReference.trim();

        Objects.requireNonNull(criteriaVersionId,
                "La versión de criterios es obligatoria");

        if (criteriaVersionId.isBlank()) {
            throw new IllegalArgumentException(
                    "La versión de criterios es obligatoria");
        }

        criteriaVersionId = criteriaVersionId.trim();

        Objects.requireNonNull(evaluatedAt,
                "El instante de evaluación es obligatorio");
    }

    /**
     * Valida y normaliza la lista de resultados observados.
     *
     * <p>Admite la lista vacía como representación explícita de la ausencia
     * de mediciones. Rechaza elementos nulos.
     *
     * @param results lista aportada por el solicitante
     * @return copia inmutable de la lista, en el orden recibido
     * @throws NullPointerException     si la lista es nula
     * @throws IllegalArgumentException si contiene elementos nulos
     */
    private static List<ObservedResult> validateObservedResults(
            List<ObservedResult> results) {

        Objects.requireNonNull(results,
                "La lista de resultados observados es obligatoria; "
                        + "use una lista vacía si no hay mediciones disponibles");

        for (ObservedResult result : results) {
            if (result == null) {
                throw new IllegalArgumentException(
                        "La lista de resultados observados no puede contener elementos nulos");
            }
        }

        return List.copyOf(results);
    }

    /**
     * Indica si la solicitud se formuló sin resultados observados.
     *
     * <p>Es un atajo de lectura, no un juicio sobre la suficiencia: la
     * clasificación definitiva corresponde al evaluador.
     *
     * @return {@code true} si no se aportó ningún resultado observado
     */
    public boolean hasNoObservedResults() {
        return observedResults.isEmpty();
    }

    /**
     * Devuelve la cantidad de resultados observados aportados.
     *
     * @return número de resultados observados recibidos
     */
    public int observedResultsCount() {
        return observedResults.size();
    }

    /**
     * Representa un resultado observado para un indicador específico.
     *
     * <p>Este record es inmutable y valida sus invariantes en el constructor.
     *
     * @param indicatorId  identificador del indicador medido
     * @param observedValue valor observado (puede ser numérico, categórico o estructurado)
     * @param measurementContext contexto de la medición (opcional)
     */
    public record ObservedResult(
            UUID indicatorId,
            String observedValue,
            String measurementContext
    ) {

        /**
         * Constructor compacto: valida invariantes.
         *
         * @throws NullPointerException     si {@code indicatorId} o
         *                                  {@code observedValue} son nulos
         * @throws IllegalArgumentException si {@code observedValue} está en blanco
         */
        public ObservedResult {
            Objects.requireNonNull(indicatorId,
                    "El identificador del indicador es obligatorio");

            Objects.requireNonNull(observedValue,
                    "El valor observado es obligatorio");

            if (observedValue.isBlank()) {
                throw new IllegalArgumentException(
                        "El valor observado es obligatorio");
            }

            observedValue = observedValue.trim();

            if (measurementContext != null) {
                measurementContext = measurementContext.trim();

                if (measurementContext.isBlank()) {
                    measurementContext = null;
                }
            }
        }
    }
}
