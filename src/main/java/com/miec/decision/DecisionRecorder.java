package com.miec.decision;

import java.util.UUID;

/**
 * Contrato para el registrador de decisiones del MIEC (MIEC-F02, MD-07).
 *
 * <p>Esta interfaz define la responsabilidad de registrar de forma persistente
 * las decisiones emitidas por el motor, conservando su trazabilidad, las
 * evidencias que las sustentan y las autorizaciones asociadas.
 *
 * <p>El registrador no ejecuta acciones, no modifica el estado del ciclo y no
 * concede autorizaciones. Su única responsabilidad es conservar el registro
 * de la decisión para auditoría y aprendizaje futuro.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El registrador debe conservar la evaluación, la recomendación, las
 *       alternativas consideradas y la transición propuesta (F02 §15.1).</li>
 *   <li>El registrador debe vincular la decisión con sus evidencias y las
 *       versiones de reglas utilizadas (F07-R12).</li>
 *   <li>El registrador no puede modificar una decisión histórica sin registrar
 *       el cambio (F03 §10).</li>
 *   <li>El registrador debe permitir recuperar la decisión para auditoría
 *       (F01 INV-02).</li>
 * </ul>
 *
 * <p>Esta interfaz representa una responsabilidad lógica (MD-07), no una
 * implementación concreta. Pueden existir múltiples implementaciones según
 * el mecanismo de persistencia utilizado.
 */
public interface DecisionRecorder {

    /**
     * Registra una decisión emitida por el motor.
     *
     * <p>El método debe:
     * <ol>
     *   <li>Validar que la decisión esté completa.</li>
     *   <li>Asignar un identificador único a la decisión.</li>
     *   <li>Persistir la decisión con todas sus referencias.</li>
     *   <li>Registrar el estado de autorización y ejecución.</li>
     *   <li>Devolver el identificador asignado.</li>
     * </ol>
     *
     * @param decisionOutcome decisión a registrar
     * @return identificador único asignado a la decisión registrada
     * @throws NullPointerException si {@code decisionOutcome} es nulo
     * @throws IllegalArgumentException si la decisión está incompleta
     */
    UUID record(DecisionOutcome decisionOutcome);

    /**
     * Recupera una decisión registrada por su identificador.
     *
     * @param decisionId identificador de la decisión
     * @return decisión registrada, o nulo si no existe
     * @throws NullPointerException si {@code decisionId} es nulo
     */
    DecisionOutcome retrieve(UUID decisionId);
}
