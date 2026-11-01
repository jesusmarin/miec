package com.miec.state;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitud de validación de una transición de estado (MIEC-F01, F04 EST-01).
 *
 * <p>Representa la entrada del proceso que determina si una transición
 * entre estados del ciclo es permitida según las reglas definidas en F01.
 *
 * <p>Este contrato no ejecuta la transición. Solo valida si es permitida
 * conforme a las reglas, invariantes y precondiciones aplicables.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El ciclo es obligatorio: sin ciclo no existe contexto de transición.</li>
 *   <li>Los estados actual y propuesto son obligatorios y deben ser distintos.</li>
 *   <li>El contexto de transición es opcional, pero si se proporciona, debe
 *       ser inmutable.</li>
 *   <li>El instante de validación es obligatorio para trazabilidad.</li>
 * </ul>
 *
 * @param cycleId            identificador del ciclo que solicita la transición
 * @param currentState       estado actual del ciclo conforme a F01
 * @param proposedState      estado al que se desea transicionar
 * @param transitionContext  contexto adicional para la validación (opcional)
 * @param validatedAt        instante en que se solicita la validación
 */
public record TransitionValidationRequest(
        UUID cycleId,
        CycleState currentState,
        CycleState proposedState,
        TransitionContext transitionContext,
        Instant validatedAt
) {

    /**
     * Constructor compacto: valida invariantes y normaliza los datos.
     *
     * @throws NullPointerException     si {@code cycleId}, {@code currentState},
     *                                  {@code proposedState} o {@code validatedAt}
     *                                  son nulos
     * @throws IllegalArgumentException si {@code currentState} y {@code proposedState}
     *                                  son iguales
     */
    public TransitionValidationRequest {
        Objects.requireNonNull(cycleId,
                "El identificador del ciclo es obligatorio");

        Objects.requireNonNull(currentState,
                "El estado actual es obligatorio");

        Objects.requireNonNull(proposedState,
                "El estado propuesto es obligatorio");

        if (currentState == proposedState) {
            throw new IllegalArgumentException(
                    "El estado actual y el estado propuesto deben ser diferentes");
        }

        Objects.requireNonNull(validatedAt,
                "El instante de validación es obligatorio");
    }

    /**
     * Indica si la solicitud incluye contexto adicional para la validación.
     *
     * @return {@code true} si se proporcionó contexto de transición
     */
    public boolean hasTransitionContext() {
        return transitionContext != null;
    }

    /**
     * Representa el contexto adicional para validar una transición.
     *
     * <p>Este record es inmutable y contiene información que puede ser
     * necesaria para evaluar precondiciones específicas de la transición,
     * como autorizaciones, evidencias o eventos desencadenantes.
     *
     * @param authorizationReference referencia a la autorización vigente (opcional)
     * @param evidenceReference      referencia a la evidencia que sustenta la transición (opcional)
     * @param triggerEvent           evento que desencadena la transición (opcional)
     * @param actorId                identificador del actor que solicita la transición (opcional)
     */
    public record TransitionContext(
            String authorizationReference,
            String evidenceReference,
            String triggerEvent,
            String actorId
    ) {

        /**
         * Constructor compacto: normaliza los datos opcionales.
         *
         * <p>Los campos de texto se normalizan con {@code trim()} y se
         * convierten a {@code null} si están en blanco.
         */
        public TransitionContext {
            if (authorizationReference != null) {
                authorizationReference = authorizationReference.trim();
                if (authorizationReference.isBlank()) {
                    authorizationReference = null;
                }
            }

            if (evidenceReference != null) {
                evidenceReference = evidenceReference.trim();
                if (evidenceReference.isBlank()) {
                    evidenceReference = null;
                }
            }

            if (triggerEvent != null) {
                triggerEvent = triggerEvent.trim();
                if (triggerEvent.isBlank()) {
                    triggerEvent = null;
                }
            }

            if (actorId != null) {
                actorId = actorId.trim();
                if (actorId.isBlank()) {
                    actorId = null;
                }
            }
        }

        /**
         * Indica si el contexto incluye alguna referencia de autorización.
         *
         * @return {@code true} si se proporcionó una referencia de autorización
         */
        public boolean hasAuthorization() {
            return authorizationReference != null;
        }

        /**
         * Indica si el contexto incluye alguna referencia de evidencia.
         *
         * @return {@code true} si se proporcionó una referencia de evidencia
         */
        public boolean hasEvidence() {
            return evidenceReference != null;
        }

        /**
         * Indica si el contexto incluye un evento desencadenante.
         *
         * @return {@code true} si se proporcionó un evento desencadenante
         */
        public boolean hasTriggerEvent() {
            return triggerEvent != null;
        }

        /**
         * Indica si el contexto identifica al actor que solicita la transición.
         *
         * @return {@code true} si se proporcionó un identificador de actor
         */
        public boolean hasActor() {
            return actorId != null;
        }
    }
}
