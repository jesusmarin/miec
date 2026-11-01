package com.miec.decision;

import java.util.List;

/**
 * Contrato para la generación de alternativas de acción (MIEC-F02, MD-03).
 *
 * <p>Esta interfaz define la responsabilidad de identificar las acciones
 * posibles según el estado del ciclo, el resultado de la evaluación y las
 * restricciones aplicables.
 *
 * <p>El generador no evalúa las alternativas ni determina su admisibilidad.
 * Su única responsabilidad es producir un conjunto de opciones que puedan
 * ser evaluadas posteriormente por {@link AlternativeEvaluator}.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El generador debe considerar el estado actual del ciclo (F01).</li>
 *   <li>El generador debe respetar las restricciones conocidas del sistema.</li>
 *   <li>El generador no puede ejecutar acciones ni modificar el estado del ciclo.</li>
 *   <li>Las alternativas generadas deben ser estructuralmente válidas.</li>
 * </ul>
 *
 * <p>Esta interfaz representa una responsabilidad lógica (MD-03), no una
 * implementación concreta. Pueden existir múltiples implementaciones según
 * el dominio o la estrategia de generación.
 */
public interface AlternativeGenerator {

    /**
     * Genera alternativas de acción posibles según el contexto.
     *
     * <p>El método debe:
     * <ol>
     *   <li>Considerar el estado actual del ciclo.</li>
     *   <li>Analizar el resultado de la evaluación.</li>
     *   <li>Identificar las acciones permitidas según las políticas.</li>
     *   <li>Producir una lista de alternativas estructuradas.</li>
     * </ol>
     *
     * @param evaluationResult resultado de la evaluación que origina la necesidad de alternativas
     * @return lista de alternativas posibles, nunca nula (puede estar vacía)
     * @throws NullPointerException si {@code evaluationResult} es nulo
     */
    List<AlternativeAssessment> generateAlternatives(EvaluationResult evaluationResult);
}