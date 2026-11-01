package com.miec.state;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Resultado de la validación de una transición de estado (MIEC-F01, F04 EST-01).
 *
 * <p>Representa la salida del proceso que determina si una transición
 * entre estados del ciclo es permitida según las reglas definidas en F01.
 *
 * <p>Este resultado no ejecuta la transición. Solo informa si es permitida
 * y, en caso de rechazo, proporciona las razones y las invariantes violadas.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>Los estados actual y propuesto son obligatorios.</li>
 *   <li>La lista de invariantes violadas puede estar vacía, pero no ser nula.</li>
 *   <li>El motivo de rechazo es obligatorio si la transición no es permitida.</li>
 *   <li>El instante de validación es obligatorio para trazabilidad.</li>
 * </ul>
 *
 * @param allowed             indica si la transición es permitida
 * @param currentState        estado actual del ciclo
 * @param proposedState       estado al que se desea transicionar
 * @param reason              motivo de la validación (éxito o rechazo)
 * @param violatedInvariants  lista de invariantes violadas (vacía si {@code allowed} es verdadero)
 * @param validatedAt         instante en que se realizó la validación
 */
public record TransitionValidationResult(
        boolean allowed,
        CycleState currentState,
        CycleState proposedState,
        String reason,
        List<String> violatedInvariants,
        Instant validatedAt
) {

    /**
     * Constructor compacto: valida invariantes y normaliza los datos.
     *
     * @throws NullPointerException     si {@code currentState}, {@code proposedState},
     *                                  {@code reason}, {@code violatedInvariants} o
     *                                  {@code validatedAt} son nulos
     * @throws IllegalArgumentException si {@code reason} está en blanco, o si
     *                                  {@code allowed} es falso pero no se proporcionó
     *                                  un motivo de rechazo
     */
    public TransitionValidationResult {
        Objects.requireNonNull(currentState,
                "El estado actual es obligatorio");

        Objects.requireNonNull(proposedState,
                "El estado propuesto es obligatorio");

        Objects.requireNonNull(reason,
                "El motivo de la validación es obligatorio");

        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "El motivo de la validación es obligatorio");
        }

        reason = reason.trim();

        Objects.requireNonNull(violatedInvariants,
                "La lista de invariantes violadas es obligatoria");

        violatedInvariants = violatedInvariants.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        if (!allowed && violatedInvariants.isEmpty() && reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Una transición no permitida debe indicar el motivo o las invariantes violadas");
        }

        Objects.requireNonNull(validatedAt,
                "El instante de validación es obligatorio");
    }

    /**
     * Indica si la transición fue rechazada.
     *
     * @return {@code true} si la transición no es permitida
     */
    public boolean isRejected() {
        return !allowed;
    }

    /**
     * Indica si la transición violó alguna invariante del sistema.
     *
     * @return {@code true} si existen invariantes violadas
     */
    public boolean hasViolatedInvariants() {
        return !violatedInvariants.isEmpty();
    }

    /**
     * Devuelve la cantidad de invariantes violadas.
     *
     * @return número de invariantes violadas
     */
    public int violatedInvariantsCount() {
        return violatedInvariants.size();
    }
}
