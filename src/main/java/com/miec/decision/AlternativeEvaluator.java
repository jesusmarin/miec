package com.miec.decision;

import java.util.List;

/**
 * Contrato para la evaluación de alternativas (MIEC-F02, MD-04).
 *
 * <p>Esta interfaz define la responsabilidad de determinar la admisibilidad
 * de las alternativas y compararlas según criterios definidos.
 *
 * <p>El evaluador no ejecuta acciones ni modifica el estado del ciclo.
 * Su única responsabilidad es evaluar las alternativas y proporcionar
 * información para que el motor de decisión pueda seleccionar una recomendación.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El evaluador debe aplicar el filtro de admisibilidad antes de comparar
 *       beneficios (F02 §10.2).</li>
 *   <li>Las alternativas inadmisibles no pueden ser recomendadas (F07-R04).</li>
 *   <li>Los criterios de seguridad y autorización funcionan como restricciones,
 *       no como puntuaciones compensables.</li>
 *   <li>El evaluador no puede ejecutar acciones ni modificar el estado del ciclo.</li>
 * </ul>
 *
 * <p>Esta interfaz representa una responsabilidad lógica (MD-04), no una
 * implementación concreta.
 */
public interface AlternativeEvaluator {

    /**
     * Evalúa las alternativas y determina cuál es la más adecuada.
     *
     * <p>El método debe:
     * <ol>
     *   <li>Aplicar el filtro de admisibilidad a cada alternativa.</li>
     *   <li>Comparar las alternativas admisibles según los criterios.</li>
     *   <li>Identificar la alternativa recomendada.</li>
     *   <li>Proporcionar un rationale que justifique la recomendación.</li>
     * </ol>
     *
     * @param alternatives lista de alternativas a evaluar
     * @return alternativa recomendada, o nulo si no hay alternativas admisibles
     * @throws NullPointerException si {@code alternatives} es nulo
     * @throws IllegalArgumentException si la lista está vacía
     */
    AlternativeAssessment evaluateAlternatives(List<AlternativeAssessment> alternatives);
}
