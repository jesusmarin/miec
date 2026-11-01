package com.miec.decision;

import com.miec.state.CycleState;
import com.miec.state.TransitionProposal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementación concreta del motor de decisión del MIEC (MIEC-F02, MD-05).
 *
 * <p>Esta clase orquesta la evaluación completa de un ciclo, integrando
 * la validación de evidencia, la evaluación de resultados, la generación
 * de alternativas y la selección de una recomendación y transición propuesta.
 *
 * <p>El motor no ejecuta acciones, no modifica el estado del ciclo y no
 * concede autorizaciones. Su única responsabilidad es producir un
 * {@link DecisionOutcome} estructurado.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El motor valida la evidencia antes de clasificar resultados (F02 §6).</li>
 *   <li>El motor respeta la regla F07-R01: no declara un resultado concluyente
 *       con evidencia EV-0 o EV-1.</li>
 *   <li>El motor aplica el filtro de admisibilidad antes de comparar beneficios
 *       (F02 §10.2).</li>
 *   <li>Las alternativas inadmisibles no pueden ser recomendadas (F07-R04).</li>
 *   <li>El motor no ejecuta acciones ni modifica el estado del ciclo (F07-R07).</li>
 *   <li>El motor proporciona un rationale que justifica la recomendación
 *       (F02 §11.2).</li>
 * </ul>
 */
public class DefaultDecisionEngine implements DecisionEngine {

    private final AlternativeGenerator alternativeGenerator;
    private final AlternativeEvaluator alternativeEvaluator;

    /**
     * Constructor que inyecta las dependencias necesarias.
     *
     * @param alternativeGenerator generador de alternativas
     * @param alternativeEvaluator evaluador de alternativas
     * @throws NullPointerException si alguna dependencia es nula
     */
    public DefaultDecisionEngine(
            AlternativeGenerator alternativeGenerator,
            AlternativeEvaluator alternativeEvaluator) {

        this.alternativeGenerator = Objects.requireNonNull(
                alternativeGenerator,
                "El generador de alternativas es obligatorio");

        this.alternativeEvaluator = Objects.requireNonNull(
                alternativeEvaluator,
                "El evaluador de alternativas es obligatorio");
    }

    /**
     * {@inheritDoc}
     *
     * <p>El método orquesta los siguientes pasos:
     * <ol>
     *   <li>Generar alternativas posibles según el resultado de la evaluación.</li>
     *   <li>Evaluar la admisibilidad de las alternativas.</li>
     *   <li>Seleccionar una recomendación entre las alternativas admisibles.</li>
     *   <li>Proponer una transición conforme a F01.</li>
     *   <li>Construir un {@link DecisionOutcome} completo.</li>
     * </ol>
     *
     * @throws NullPointerException     si {@code evaluationResult} es nulo
     * @throws IllegalArgumentException si no existe ninguna alternativa admisible
     */
    @Override
    public DecisionOutcome decide(EvaluationResult evaluationResult) {
        Objects.requireNonNull(evaluationResult,
                "El resultado de evaluación es obligatorio");

        // Paso 1: Generar alternativas según la clasificación
        List<AlternativeAssessment> alternatives =
                alternativeGenerator.generateAlternatives(evaluationResult);

        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException(
                    "No se generaron alternativas para la evaluación");
        }

        // Paso 2: Evaluar alternativas y seleccionar la recomendación
        AlternativeAssessment recommended =
                alternativeEvaluator.evaluateAlternatives(alternatives);

        if (recommended == null) {
            throw new IllegalArgumentException(
                    "No existe ninguna alternativa admisible");
        }

        // Paso 3: Construir la recomendación
        DecisionRecommendation recommendation = new DecisionRecommendation(
                recommended.action(),
                buildRecommendationRationale(evaluationResult, recommended),
                evaluationResult.evidenceAssessment().evidenceIds(),
                Instant.now()
        );

        // Paso 4: Proponer transición según la acción
        TransitionProposal transition = proposeTransition(
                evaluationResult,
                recommended.action()
        );

        // Paso 5: Construir el resultado completo
        return new DecisionOutcome(
                evaluationResult,
                alternatives,
                recommendation,
                transition
        );
    }

    /**
     * Construye el rationale de la recomendación.
     *
     * @param evaluation resultado de la evaluación
     * @param recommended alternativa recomendada
     * @return rationale que justifica la recomendación
     */
    private String buildRecommendationRationale(
            EvaluationResult evaluation,
            AlternativeAssessment recommended) {

        return String.format(
                "Recomendación basada en evaluación %s: %s. %s",
                evaluation.classification().getCode(),
                evaluation.rationale(),
                recommended.reason()
        );
    }

    /**
     * Propone una transición conforme a F01 según la acción recomendada.
     *
     * @param evaluation resultado de la evaluación
     * @param action acción recomendada
     * @return transición propuesta
     */
    private TransitionProposal proposeTransition(
            EvaluationResult evaluation,
            DecisionAction action) {

        CycleState targetState = determineTargetState(action);

        return new TransitionProposal(
                CycleState.E09_DECIDIENDO,
                targetState,
                buildTransitionRationale(action),
                Instant.now()
        );
    }

    /**
     * Determina el estado objetivo según la acción recomendada.
     *
     * <p>Esta implementación utiliza una tabla de decisión simplificada.
     * Las transiciones definitivas deberán validarse contra F01.
     *
     * @param action acción recomendada
     * @return estado objetivo propuesto
     */
    private CycleState determineTargetState(DecisionAction action) {
        return switch (action) {
            case CLOSE_CYCLE -> CycleState.E10_APRENDIENDO;
            case ADJUST_PLAN, REFORMULATE_HYPOTHESIS -> CycleState.E04_PLANIFICANDO;
            case COLLECT_EVIDENCE, ESTABLISH_BASELINE -> CycleState.E02_OBSERVANDO;
            case MAINTAIN_CURRENT_STATE -> CycleState.E10_APRENDIENDO;
            case REVERT_CHANGE -> CycleState.E13_RECUPERANDO;
            case REQUEST_HUMAN_REVIEW -> CycleState.E05_ESPERANDO_AUTORIZACION;
            case SUSPEND_CYCLE -> CycleState.E12_SUSPENDIDO;
            case ABORT_INTERVENTION -> CycleState.E15_ABORTADO;
            case CONTINUE_PLAN -> CycleState.E10_APRENDIENDO;
        };
    }

    /**
     * Construye el rationale de la transición propuesta.
     *
     * @param action acción recomendada
     * @return rationale que justifica la transición
     */
    private String buildTransitionRationale(DecisionAction action) {
        return String.format(
                "Transición propuesta según la recomendación de acción: %s",
                action.name()
        );
    }
}
