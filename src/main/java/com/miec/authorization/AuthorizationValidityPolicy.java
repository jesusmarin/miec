
package com.miec.authorization;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Política de validación de autorizaciones de MIEC-F03.
 *
 * <p>Implementa IR-010: alcance, vigencia y estado.
 *
 * <p>Esta clase no concede autorizaciones ni modifica
 * su estado. Únicamente determina si una autorización
 * existente puede utilizarse para una operación concreta.
 *
 * <p>La evaluación es determinista: el instante de
 * comprobación se proporciona explícitamente.
 *
 * <p>No implementa IR-009, que exige verificar
 * la autorización previa a la ejecución.
 */
@Component
public class AuthorizationValidityPolicy {

    /**
     * Datos necesarios para evaluar una autorización.
     *
     * @param authorizationId identificador de autorización
     * @param planId plan autorizado, si corresponde
     * @param actionId acción autorizada, si corresponde
     * @param scope alcance declarado
     * @param status estado de autorización
     * @param approvedBy responsable de aprobación
     * @param issuedAt instante de emisión
     * @param expiresAt vencimiento opcional
     * @param revokedAt revocación opcional
     */
    public record AuthorizationSnapshot(
            UUID authorizationId,
            UUID planId,
            UUID actionId,
            String scope,
            String status,
            String approvedBy,
            Instant issuedAt,
            Instant expiresAt,
            Instant revokedAt
    ) {

        /**
         * Valida la identidad y emisión mínimas.
         */
        public AuthorizationSnapshot {
            Objects.requireNonNull(
                    authorizationId,
                    "authorizationId es obligatorio"
            );
            Objects.requireNonNull(
                    issuedAt,
                    "issuedAt es obligatorio"
            );
        }
    }

    /**
     * Contexto de la operación que necesita autorización.
     *
     * @param planId plan de la operación
     * @param actionId acción de la operación
     * @param requiredScope alcance requerido
     * @param evaluatedAt instante de evaluación
     */
    public record AuthorizationUseRequest(
            UUID planId,
            UUID actionId,
            String requiredScope,
            Instant evaluatedAt
    ) {

        /**
         * Valida los datos obligatorios de la solicitud.
         */
        public AuthorizationUseRequest {
            Objects.requireNonNull(
                    requiredScope,
                    "requiredScope es obligatorio"
            );

            if (requiredScope.isBlank()) {
                throw new IllegalArgumentException(
                        "El alcance requerido no puede estar vacío"
                );
            }

            requiredScope = requiredScope.trim();

            Objects.requireNonNull(
                    evaluatedAt,
                    "evaluatedAt es obligatorio"
            );
        }
    }

    /**
     * Resultado inmutable de una validación.
     *
     * @param allowed indica si se permite utilizarla
     * @param reason justificación de la decisión
     */
    public record AuthorizationDecision(
            boolean allowed,
            String reason
    ) {

        /**
         * Construye una decisión justificada.
         */
        public AuthorizationDecision {
            Objects.requireNonNull(
                    reason,
                    "La justificación es obligatoria"
            );
        }
    }

    /**
     * Evalúa una autorización para una operación.
     *
     * <p>Se aplica una política de denegación por defecto:
     * cualquier condición incumplida impide utilizarla.
     *
     * <p>El alcance debe coincidir exactamente. No se
     * interpretan expresiones parciales ni comodines.
     *
     * <p>Un vencimiento nulo representa ausencia de
     * vencimiento explícito en esta versión de la política.
     *
     * @param authorization autorización registrada
     * @param request operación solicitada
     * @return decisión justificada
     */
    public AuthorizationDecision evaluate(
            AuthorizationSnapshot authorization,
            AuthorizationUseRequest request
    ) {
        Objects.requireNonNull(
                authorization,
                "La autorización es obligatoria"
        );

        Objects.requireNonNull(
                request,
                "La solicitud es obligatoria"
        );

        if (!"APPROVED".equals(authorization.status())) {
            return deny("La autorización no está aprobada");
        }

        if (authorization.approvedBy() == null
                || authorization.approvedBy().isBlank()) {
            return deny("No existe responsable de aprobación");
        }

        if (authorization.scope() == null
                || !request.requiredScope().equals(
                authorization.scope().trim()
        )) {
            return deny("El alcance no corresponde a la operación");
        }

        if (!Objects.equals(
                authorization.planId(),
                request.planId()
        )) {
            return deny("El plan no coincide");
        }

        if (!Objects.equals(
                authorization.actionId(),
                request.actionId()
        )) {
            return deny("La acción no coincide");
        }

        if (authorization.issuedAt().isAfter(
                request.evaluatedAt()
        )) {
            return deny("La autorización todavía no está vigente");
        }

        if (authorization.expiresAt() != null) {
            if (!authorization.expiresAt().isAfter(
                    authorization.issuedAt()
            )) {
                return deny("El período de vigencia es inválido");
            }

            if (!request.evaluatedAt().isBefore(
                    authorization.expiresAt()
            )) {
                return deny("La autorización ha vencido");
            }
        }

        if (authorization.revokedAt() != null) {
            return deny("La autorización está revocada");
        }

        return new AuthorizationDecision(
                true,
                "Autorización válida para la operación"
        );
    }

    /**
     * Construye una decisión de rechazo.
     *
     * @param reason motivo del rechazo
     * @return resultado denegado
     */
    private AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, reason);
    }
}
