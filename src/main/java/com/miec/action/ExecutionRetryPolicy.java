
package com.miec.action;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Política de reintentos controlados de MIEC-F03 IR-009.
 *
 * <p>Determina si una acción planificada puede
 * ejecutarse nuevamente después de un intento fallido.
 *
 * <p>Principios:
 * <ul>
 *   <li>No repetir acciones completadas.</li>
 *   <li>No repetir acciones en ejecución.</li>
 *   <li>No repetir acciones con resultado desconocido.</li>
 *   <li>Exigir confirmación de ausencia de efectos.</li>
 *   <li>Revalidar la autorización antes del reintento.</li>
 *   <li>Respetar el límite máximo de intentos.</li>
 * </ul>
 *
 * <p>Esta política es determinista y no tiene
 * efectos secundarios. No consulta ni modifica
 * registros persistidos.
 *
 * <p>La coordinación transaccional, la persistencia
 * y la ejecución real corresponden a otros servicios.
 */
@Component
public class ExecutionRetryPolicy {

    /**
     * Estado conocido del último intento.
     */
    public enum ExecutionStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        UNKNOWN
    }

    /**
     * Resultado conocido de los efectos de un intento.
     *
     * <p>NO_EFFECT_CONFIRMED significa que se verificó
     * que el intento anterior no produjo efectos.
     *
     * <p>EFFECT_UNKNOWN representa una situación
     * cuya consecuencia todavía no se conoce.
     */
    public enum EffectOutcome {
        NO_EFFECT_CONFIRMED,
        EFFECT_APPLIED,
        EFFECT_UNKNOWN
    }

    /**
     * Contexto necesario para evaluar un reintento.
     *
     * @param status estado del último intento
     * @param effectOutcome efectos del intento anterior
     * @param attemptsPerformed intentos ya realizados
     * @param maxAttempts límite total de intentos
     * @param authorizationValid autorización vigente y válida
     */
    public record RetryRequest(
            ExecutionStatus status,
            EffectOutcome effectOutcome,
            int attemptsPerformed,
            int maxAttempts,
            boolean authorizationValid
    ) {
        /**
         * Valida la consistencia básica de la solicitud.
         */
        public RetryRequest {
            Objects.requireNonNull(
                    status,
                    "status es obligatorio"
            );

            Objects.requireNonNull(
                    effectOutcome,
                    "effectOutcome es obligatorio"
            );

            if (attemptsPerformed < 0) {
                throw new IllegalArgumentException(
                        "attemptsPerformed no puede ser negativo"
                );
            }

            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "maxAttempts debe ser mayor o igual a 1"
                );
            }

            if (attemptsPerformed < 1) {
                throw new IllegalArgumentException(
                        "Un reintento requiere un intento previo"
                );
            }
        }
    }

    /**
     * Resultado de la evaluación.
     *
     * @param allowed indica si se permite reintentar
     * @param reason explicación de la decisión
     * @param nextAttemptNumber número del próximo intento,
     *                          o null si está denegado
     */
    public record RetryDecision(
            boolean allowed,
            String reason,
            Integer nextAttemptNumber
    ) {
        public RetryDecision {
            Objects.requireNonNull(
                    reason,
                    "reason es obligatorio"
            );

            if (allowed && nextAttemptNumber == null) {
                throw new IllegalArgumentException(
                        "Un reintento autorizado requiere número"
                );
            }

            if (!allowed && nextAttemptNumber != null) {
                throw new IllegalArgumentException(
                        "Un reintento denegado no debe tener número"
                );
            }
        }
    }

    /**
     * Determina si puede realizarse otro intento.
     *
     * <p>La evaluación es conservadora:
     * únicamente FAILED con ausencia confirmada
     * de efectos puede ser reconsiderado.
     *
     * <p>Una autorización válida en esta evaluación
     * no sustituye la validación transaccional
     * inmediatamente anterior a ejecutar.
     *
     * @param request contexto del último intento
     * @return decisión justificada
     */
    public RetryDecision evaluate(RetryRequest request) {
        Objects.requireNonNull(
                request,
                "request es obligatorio"
        );

        if (request.status() == ExecutionStatus.COMPLETED) {
            return deny(
                    "IR-009: la acción ya fue completada"
            );
        }

        if (request.status() == ExecutionStatus.IN_PROGRESS) {
            return deny(
                    "IR-009: existe una ejecución en curso"
            );
        }

        if (request.status() == ExecutionStatus.UNKNOWN) {
            return deny(
                    "IR-009: resultado desconocido; "
                            + "se requiere reconciliación"
            );
        }

        if (request.status() == ExecutionStatus.PENDING) {
            return deny(
                    "IR-009: una acción pendiente "
                            + "no corresponde a un reintento"
            );
        }

        if (request.effectOutcome()
                != EffectOutcome.NO_EFFECT_CONFIRMED) {
            return deny(
                    "IR-009: no se ha confirmado "
                            + "la ausencia de efectos"
            );
        }

        if (!request.authorizationValid()) {
            return deny(
                    "IR-009: autorización inválida "
                            + "para el reintento"
            );
        }

        if (request.attemptsPerformed()
                >= request.maxAttempts()) {
            return deny(
                    "IR-009: límite de intentos alcanzado"
            );
        }

        return new RetryDecision(
                true,
                "IR-009: reintento permitido",
                request.attemptsPerformed() + 1
        );
    }

    /**
     * Construye una decisión negativa.
     *
     * @param reason motivo del rechazo
     * @return decisión denegada
     */
    private RetryDecision deny(String reason) {
        return new RetryDecision(
                false,
                reason,
                null
        );
    }
}

