
package com.miec.authorization;

import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationDecision;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationSnapshot;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de IR-010 para MIEC-F03.
 *
 * <p>Verifican las invariantes de alcance, estado
 * y vigencia de las autorizaciones.
 *
 * <p>No necesitan Spring Boot ni conexión a H2,
 * porque la política no accede a infraestructura.
 *
 * <p>Cada resultado debe ser determinista respecto
 * al instante de evaluación proporcionado.
 */
@DisplayName(
        "MIEC-F03 IR-010 - Alcance, vigencia y estado de autorizaciones"
)
class AuthorizationValidityPolicyTest {

    private AuthorizationValidityPolicy policy;

    private UUID authorizationId;
    private UUID planId;
    private UUID actionId;

    private Instant now;

    /**
     * Inicializa los datos independientes de cada prueba.
     */
    @BeforeEach
    void setUp() {
        policy = new AuthorizationValidityPolicy();

        authorizationId = UUID.randomUUID();
        planId = UUID.randomUUID();
        actionId = UUID.randomUUID();

        now = Instant.parse("2026-11-01T15:00:00Z");
    }

    /**
     * Construye una autorización con valores
     * configurables para los escenarios de prueba.
     */
    private AuthorizationSnapshot authorization(
            String status,
            String scope,
            String approvedBy,
            Instant issuedAt,
            Instant expiresAt,
            Instant revokedAt
    ) {
        return new AuthorizationSnapshot(
                authorizationId,
                planId,
                actionId,
                scope,
                status,
                approvedBy,
                issuedAt,
                expiresAt,
                revokedAt
        );
    }

    /**
     * Construye una autorización válida de referencia.
     */
    private AuthorizationSnapshot validAuthorization() {
        return authorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                now.minus(1, ChronoUnit.HOURS),
                now.plus(1, ChronoUnit.HOURS),
                null
        );
    }

    /**
     * Construye una solicitud válida de referencia.
     */
    private AuthorizationUseRequest validRequest() {
        return new AuthorizationUseRequest(
                planId,
                actionId,
                "EXECUTE_ACTION",
                now
        );
    }

    /**
     * Comprueba una autorización válida.
     */
    @Test
    @DisplayName(
            "Debe aceptar una autorización aprobada, vigente y con alcance correcto"
    )
    void shouldAcceptValidAuthorization() {
        AuthorizationDecision decision = policy.evaluate(
                validAuthorization(),
                validRequest()
        );

        assertTrue(decision.allowed());
        assertNotNull(decision.reason());
    }

    /**
     * Comprueba que una autorización pendiente
     * no pueda utilizarse.
     */
    @Test
    @DisplayName(
            "Debe rechazar una autorización pendiente"
    )
    void shouldRejectPendingAuthorization() {
        AuthorizationSnapshot authorization = authorization(
                "PENDING",
                "EXECUTE_ACTION",
                null,
                now.minusSeconds(60),
                now.plusSeconds(60),
                null
        );

        assertFalse(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba que el rechazo humano
     * impida utilizar la autorización.
     */
    @Test
    @DisplayName(
            "Debe rechazar una autorización denegada"
    )
    void shouldRejectRejectedAuthorization() {
        AuthorizationSnapshot authorization = authorization(
                "REJECTED",
                "EXECUTE_ACTION",
                "human-approver",
                now.minusSeconds(60),
                now.plusSeconds(60),
                null
        );

        assertFalse(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba el rechazo de un estado no reconocido.
     */
    @Test
    @DisplayName(
            "Debe rechazar estados de autorización desconocidos"
    )
    void shouldRejectUnknownStatus() {
        AuthorizationSnapshot authorization = authorization(
                "UNKNOWN",
                "EXECUTE_ACTION",
                "human-approver",
                now.minusSeconds(60),
                now.plusSeconds(60),
                null
        );

        assertFalse(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba que una autorización aprobada
     * deba identificar a su aprobador.
     */
    @Test
    @DisplayName(
            "Debe rechazar autorizaciones aprobadas sin responsable"
    )
    void shouldRejectMissingApprover() {
        AuthorizationSnapshot authorization = authorization(
                "APPROVED",
                "EXECUTE_ACTION",
                " ",
                now.minusSeconds(60),
                now.plusSeconds(60),
                null
        );

        assertFalse(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba que el alcance autorizado
     * corresponda exactamente al requerido.
     */
    @Test
    @DisplayName(
            "Debe rechazar una operación fuera del alcance autorizado"
    )
    void shouldRejectDifferentScope() {
        AuthorizationUseRequest request =
                new AuthorizationUseRequest(
                        planId,
                        actionId,
                        "DELETE_SYSTEM",
                        now
                );

        assertFalse(
                policy.evaluate(
                        validAuthorization(),
                        request
                ).allowed()
        );
    }

    /**
     * Verifica que una autorización no pueda
     * aplicarse a un plan diferente.
     */
    @Test
    @DisplayName(
            "Debe rechazar el uso de una autorización para otro plan"
    )
    void shouldRejectDifferentPlan() {
        AuthorizationUseRequest request =
                new AuthorizationUseRequest(
                        UUID.randomUUID(),
                        actionId,
                        "EXECUTE_ACTION",
                        now
                );

        assertFalse(
                policy.evaluate(
                        validAuthorization(),
                        request
                ).allowed()
        );
    }

    /**
     * Verifica que una autorización no pueda
     * utilizarse para una acción diferente.
     */
    @Test
    @DisplayName(
            "Debe rechazar el uso de una autorización para otra acción"
    )
    void shouldRejectDifferentAction() {
        AuthorizationUseRequest request =
                new AuthorizationUseRequest(
                        planId,
                        UUID.randomUUID(),
                        "EXECUTE_ACTION",
                        now
                );

        assertFalse(
                policy.evaluate(
                        validAuthorization(),
                        request
                ).allowed()
        );
    }

    /**
     * Comprueba el rechazo de autorizaciones
     * cuya emisión ocurre después del uso solicitado.
     */
    @Test
    @DisplayName(
            "Debe rechazar una autorización cuya emisión es futura"
    )
    void shouldRejectAuthorizationBeforeIssueTime() {
        AuthorizationSnapshot authorization = authorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                now.plusSeconds(60),
                now.plusSeconds(3600),
                null
        );

        assertFalse(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba que la autorización no sea válida
     * desde el instante exacto de vencimiento.
     */
    @Test
    @DisplayName(
            "Debe rechazar una autorización vencida"
    )
    void shouldRejectExpiredAuthorization() {
        AuthorizationSnapshot authorization = authorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                now.minusSeconds(3600),
                now,
                null
        );

        assertFalse(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba que una autorización revocada
     * no permita realizar la operación.
     */
    @Test
    @DisplayName(
            "Debe rechazar una autorización revocada"
    )
    void shouldRejectRevokedAuthorization() {
        AuthorizationSnapshot authorization = authorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                now.minusSeconds(60)
        );

        assertFalse(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba el rechazo de períodos de
     * vigencia inconsistentes.
     */
    @Test
    @DisplayName(
            "Debe rechazar una autorización que vence antes de su emisión"
    )
    void shouldRejectInvalidValidityPeriod() {
        AuthorizationSnapshot authorization = authorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                now.minusSeconds(60),
                now.minusSeconds(120),
                null
        );

        assertFalse(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba la semántica provisional
     * de vencimiento opcional.
     */
    @Test
    @DisplayName(
            "Debe aceptar una autorización válida sin vencimiento explícito"
    )
    void shouldAcceptAuthorizationWithoutExpiration() {
        AuthorizationSnapshot authorization = authorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                now.minusSeconds(60),
                null,
                null
        );

        assertTrue(
                policy.evaluate(
                        authorization,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba que los datos obligatorios
     * de una solicitud sean válidos.
     */
    @Test
    @DisplayName(
            "Debe rechazar solicitudes con alcance vacío"
    )
    void shouldRejectBlankRequestedScope() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthorizationUseRequest(
                        planId,
                        actionId,
                        " ",
                        now
                )
        );
    }
}
