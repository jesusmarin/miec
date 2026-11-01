package com.miec.state;

/**
 * Contrato para la validación de transiciones de estado (MIEC-F01, F04 EST-01).
 *
 * <p>Esta interfaz define la responsabilidad de determinar si una transición
 * entre estados del ciclo es permitida según las reglas definidas en F01.
 *
 * <p>El validador no ejecuta la transición, no modifica el estado del ciclo y
 * no concede autorizaciones. Su única responsabilidad es validar si la
 * transición es permitida y, en caso de rechazo, proporcionar las razones.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El validador debe respetar las reglas de transición de F01.</li>
 *   <li>El validador debe verificar que no se violen invariantes del sistema
 *       (F01 §8).</li>
 *   <li>El validador no puede ejecutar la transición (F07-R07).</li>
 *   <li>El validador debe registrar el motivo de rechazo cuando corresponda
 *       (F02 §11.2).</li>
 * </ul>
 *
 * <p>Esta interfaz representa una responsabilidad lógica (MD-06), no una
 * implementación concreta. Pueden existir múltiples implementaciones según
 * el contexto o las políticas aplicables.
 */
public interface TransitionValidator {

    /**
     * Valida si una transición de estado es permitida.
     *
     * <p>El método debe:
     * <ol>
     *   <li>Verificar que el ciclo exista y esté en el estado actual indicado.</li>
     *   <li>Comprobar que la transición esté definida en F01.</li>
     *   <li>Verificar que no se violen invariantes del sistema.</li>
     *   <li>Comprobar las precondiciones de la transición.</li>
     *   <li>Devolver un resultado que indique si la transición es permitida
     *       y, en caso de rechazo, las razones.</li>
     * </ol>
     *
     * @param request solicitud de validación con los estados y el contexto
     * @return resultado de la validación con el estado y las razones
     * @throws NullPointerException si {@code request} es nulo
     * @throws IllegalArgumentException si la solicitud no cumple los invariantes
     */
    TransitionValidationResult validate(TransitionValidationRequest request);
}
