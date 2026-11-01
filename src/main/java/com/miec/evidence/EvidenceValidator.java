package com.miec.evidence;

/**
 * Contrato para la validación de suficiencia de evidencia (MIEC-F02, MD-01).
 *
 * <p>Esta interfaz define la responsabilidad de determinar si la evidencia
 * disponible es suficiente para emitir una evaluación conforme a los criterios
 * establecidos.
 *
 * <p>El validador no ejecuta acciones, no modifica el estado del ciclo y no
 * concede autorizaciones. Su única responsabilidad es producir una evaluación
 * de la evidencia que pueda ser utilizada por el motor de decisión.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El validador debe poder abstenerse si la evidencia es insuficiente
 *       (F02 §3.7).</li>
 *   <li>La suficiencia no puede calcularse sumando dimensiones sin una
 *       política de dominio definida (F07 §5.1).</li>
 *   <li>El validador no puede modificar el estado del ciclo ni conceder
 *       permisos (F07-R07).</li>
 *   <li>Las limitaciones e incertidumbres identificadas deben conservarse
 *       en el resultado (F02 §6.2).</li>
 * </ul>
 *
 * <p>Esta interfaz representa una responsabilidad lógica (MD-01), no una
 * implementación concreta. Pueden existir múltiples implementaciones según
 * el dominio o el tipo de evidencia.
 */
public interface EvidenceValidator {

    /**
     * Valida la suficiencia de la evidencia disponible para una evaluación.
     *
     * <p>El método debe:
     * <ol>
     *   <li>Verificar la integridad y procedencia de las evidencias.</li>
     *   <li>Evaluar su pertinencia respecto al objetivo.</li>
     *   <li>Identificar limitaciones e incertidumbres.</li>
     *   <li>Clasificar la suficiencia según los niveles EV-0 a EV-4.</li>
     *   <li>Proporcionar un rationale que justifique la clasificación.</li>
     * </ol>
     *
     * @param request solicitud de validación con las evidencias y el contexto
     * @return evaluación de la evidencia con suficiencia, limitaciones y rationale
     * @throws NullPointerException si {@code request} es nulo
     * @throws IllegalArgumentException si la solicitud no cumple los invariantes
     */
    EvidenceAssessment validate(EvidenceValidationRequest request);
}
