package com.miec.decision;

/**
 * Contrato para la evaluación de resultados (MIEC-F02, MD-02).
 *
 * <p>Esta interfaz define la responsabilidad de comparar los resultados
 * observados con los criterios de aceptación y producir una evaluación
 * estructurada.
 *
 * <p>El evaluador no ejecuta acciones, no modifica el estado del ciclo y no
 * concede autorizaciones. Su única responsabilidad es producir una evaluación
 * que pueda ser utilizada por el motor de decisión para recomendar una acción.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El evaluador debe respetar la regla F07-R01: no puede declarar un
 *       resultado concluyente con evidencia EV-0 o EV-1.</li>
 *   <li>La confianza no puede sustituir a la evidencia (F07-R03).</li>
 *   <li>El evaluador debe conservar las limitaciones e incertidumbres de la
 *       evaluación de evidencia (F02 §8.2).</li>
 *   <li>El evaluador no puede modificar el estado del ciclo ni conceder
 *       permisos (F07-R07).</li>
 * </ul>
 *
 * <p>Esta interfaz representa una responsabilidad lógica (MD-02), no una
 * implementación concreta. Pueden existir múltiples implementaciones según
 * el dominio o el tipo de criterios.
 */
public interface ResultEvaluator {

    /**
     * Evalúa los resultados observados contra los criterios de aceptación.
     *
     * <p>El método debe:
     * <ol>
     *   <li>Recuperar la evaluación de evidencia del request.</li>
     *   <li>Comparar los resultados observados con la línea base.</li>
     *   <li>Aplicar los criterios de aceptación definidos.</li>
     *   <li>Clasificar el resultado según los niveles RS-01 a RS-06.</li>
     *   <li>Determinar el nivel de confianza de la evaluación.</li>
     *   <li>Proporcionar un rationale que justifique la clasificación.</li>
     * </ol>
     *
     * @param request solicitud de evaluación con resultados y criterios
     * @return evaluación del resultado con clasificación, confianza y rationale
     * @throws NullPointerException si {@code request} es nulo
     * @throws IllegalArgumentException si la solicitud no cumple los invariantes
     */
    EvaluationResult evaluate(ResultEvaluationRequest request);
}
