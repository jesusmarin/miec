package com.miec.decision;

import com.miec.state.TransitionProposal;

/**
 * Contrato para el motor de decisión del MIEC (MIEC-F02, MD-05).
 *
 * <p>Esta interfaz define la responsabilidad de orquestar la evaluación
 * completa de un ciclo, integrando la validación de evidencia, la evaluación
 * de resultados, la generación de alternativas y la selección de una
 * recomendación y transición propuesta.
 *
 * <p>El motor de decisión no ejecuta acciones, no modifica el estado del
 * ciclo y no concede autorizaciones. Su única responsabilidad es producir
 * un {@link DecisionOutcome} estructurado que pueda ser utilizado por el
 * orquestador para determinar el siguiente paso.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El motor debe validar la evidencia antes de clasificar resultados
 *       (F02 §6).</li>
 *   <li>El motor debe respetar la regla F07-R01: no puede declarar un
 *       resultado concluyente con evidencia EV-0 o EV-1.</li>
 *   <li>El motor debe aplicar el filtro de admisibilidad antes de comparar
 *       beneficios (F02 §10.2).</li>
 *   <li>Las alternativas inadmisibles no pueden ser recomendadas (F07-R04).</li>
 *   <li>El motor no puede ejecutar acciones ni modificar el estado del ciclo
 *       (F07-R07).</li>
 *   <li>El motor debe proporcionar un rationale que justifique la recomendación
 *       (F02 §11.2).</li>
 * </ul>
 *
 * <p>Esta interfaz representa una responsabilidad lógica (MD-05), no una
 * implementación concreta. Pueden existir múltiples implementaciones según
 * el dominio o la estrategia de decisión.
 */
public interface DecisionEngine {

    /**
     * Evalúa el ciclo y produce una recomendación estructurada.
     *
     * <p>El método debe:
     * <ol>
     *   <li>Validar la evidencia disponible.</li>
     *   <li>Evaluar los resultados observados contra los criterios.</li>
     *   <li>Clasificar el resultado según los niveles RS-01 a RS-06.</li>
     *   <li>Generar alternativas posibles según el estado y el resultado.</li>
     *   <li>Evaluar la admisibilidad de las alternativas.</li>
     *   <li>Seleccionar una recomendación entre las alternativas admisibles.</li>
     *   <li>Proponer una transición conforme a F01.</li>
     *   <li>Construir un {@link DecisionOutcome} completo.</li>
     * </ol>
     *
     * @param evaluationResult resultado de la evaluación que origina la decisión
     * @return decisión estructurada con evaluación, alternativas, recomendación y transición
     * @throws NullPointerException si {@code evaluationResult} es nulo
     * @throws IllegalArgumentException si no existe ninguna alternativa admisible
     */
    DecisionOutcome decide(EvaluationResult evaluationResult);
}
