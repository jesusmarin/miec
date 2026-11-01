
package com.miec.authorization;

import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationDecision;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationSnapshot;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de AuthorizationPersistenceService.
 *
 * <p>Validan IR-010 mediante las autorizaciones
 * almacenadas en H2 y la política de dominio
 * AuthorizationValidityPolicy.
 *
 * <p>Se comprueba:
 * <ul>
 *   <li>Consulta de autorizaciones persistidas.</li>
 *   <li>Denegación de registros inexistentes.</li>
 *   <li>Estados de aprobación.</li>
 *   <li>Identificación del responsable.</li>
 *   <li>Coincidencia de alcance, plan y acción.</li>
 *   <li>Control temporal de vigencia.</li>
 *   <li>Rechazo de autorizaciones revocadas.</li>
 * </ul>
 *
 * <p>Las pruebas son transaccionales y utilizan
 * una base de datos en memoria independiente.
 *
 * <p>No prueban todavía IR-009 ni la
 * concurrencia de operaciones de ejecución.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_ir010_persistence_it;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-010 - Persistencia y validación de autorizaciones"
)
class AuthorizationPersistenceServiceTest {

    /** Servicio sometido a prueba. */
    @Autowired
    private AuthorizationPersistenceService service;

    /** Acceso JDBC para preparar los registros. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Instante fijo que hace reproducibles las pruebas. */
    private static final Instant NOW =
            Instant.parse("2026-11-01T15:00:00Z");

    /** Referencias independientes de cada prueba. */
    private UUID planId;
    private UUID actionId;

    /**
     * Inicializa los identificadores de cada escenario.
     */
    @BeforeEach
    void setUp() {
        planId = UUID.randomUUID();
        actionId = UUID.randomUUID();
    }

    /**
     * Inserta una autorización directamente
     * en la tabla definida por Flyway V001.
     *
     * <p>Los campos plan_id y action_id son
     * referencias opcionales en el esquema.
     *
     * <p>Este método prepara registros para evaluar
     * IR-010, no implementa un proceso de aprobación.
     *
     * @param status estado de autorización
     * @param scope alcance declarado
     * @param approvedBy responsable aprobador
     * @param issuedAt fecha de emisión
     * @param expiresAt vencimiento opcional
     * @param revokedAt revocación opcional
     * @return identificador del registro creado
     */
    private UUID insertAuthorization(
            String status,
            String scope,
            String approvedBy,
            Instant issuedAt,
            Instant expiresAt,
            Instant revokedAt
    ) {
        UUID authorizationId = UUID.randomUUID();

        assertEquals(
                1,
                jdbcTemplate.update(
                        """
                        INSERT INTO authorizations (
                            authorization_id,
                            plan_id,
                            action_id,
                            requested_by,
                            approved_by,
                            scope,
                            status,
                            issued_at,
                            expires_at,
                            revoked_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        authorizationId,
                        null,
                        actionId,
                        "miec-test-requester",
                        approvedBy,
                        scope,
                        status,
                        Timestamp.from(issuedAt),
                        toTimestamp(expiresAt),
                        toTimestamp(revokedAt)
                )
        );

        return authorizationId;
    }

    /**
     * Convierte un instante opcional
     * a su representación JDBC.
     *
     * @param instant instante opcional
     * @return timestamp o null
     */
    private Timestamp toTimestamp(Instant instant) {
        return instant == null
                ? null
                : Timestamp.from(instant);
    }

    /**
     * Registra una autorización aprobada y vigente.
     *
     * @return identificador de la autorización
     */
    private UUID insertValidAuthorization() {
        return insertAuthorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.plus(1, ChronoUnit.HOURS),
                null
        );
    }

    /**
     * Construye una solicitud que corresponde
     * a los datos de la autorización de referencia.
     *
     * <p>La autorización insertada corresponde
     * a una acción sin ámbito de plan explícito.
     *
     * @return solicitud de utilización
     */
    private AuthorizationUseRequest validRequest() {
        return new AuthorizationUseRequest(
                null,
                actionId,
                "EXECUTE_ACTION",
                NOW
        );
    }

    /**
     * Verifica la aceptación de una autorización
     * persistida con todos los requisitos válidos.
     */
    @Test
    @DisplayName(
            "Debe aceptar una autorización persistida, aprobada y vigente"
    )
    void shouldAcceptValidPersistedAuthorization() {
        UUID authorizationId = insertValidAuthorization();

        AuthorizationDecision decision = service.evaluate(
                authorizationId,
                validRequest()
        );

        assertTrue(decision.allowed());
        assertNotNull(decision.reason());

        AuthorizationSnapshot persisted = service.findById(
                authorizationId
        ).orElseThrow();

        assertAll(
                () -> assertEquals(
                        authorizationId,
                        persisted.authorizationId()
                ),
                () -> assertEquals(
                        "APPROVED",
                        persisted.status()
                ),
                () -> assertEquals(
                        actionId,
                        persisted.actionId()
                ),
                () -> assertEquals(
                        "EXECUTE_ACTION",
                        persisted.scope()
                )
        );
    }

    /**
     * Comprueba que una autorización inexistente
     * no produzca una autorización implícita.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización inexistente"
    )
    void shouldDenyUnknownAuthorization() {
        UUID unknownId = UUID.randomUUID();

        assertTrue(service.findById(unknownId).isEmpty());

        AuthorizationDecision decision = service.evaluate(
                unknownId,
                validRequest()
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("no existe"));
    }

    /**
     * Comprueba que una solicitud pendiente
     * no autorice la operación.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización pendiente"
    )
    void shouldDenyPendingAuthorization() {
        UUID authorizationId = insertAuthorization(
                "PENDING",
                "EXECUTE_ACTION",
                null,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                null
        );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Verifica el rechazo de una autorización
     * cuyo estado es REJECTED.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización rechazada"
    )
    void shouldDenyRejectedAuthorization() {
        UUID authorizationId = insertAuthorization(
                "REJECTED",
                "EXECUTE_ACTION",
                "human-approver",
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                null
        );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba que el registro aprobado
     * identifique a quien concedió la aprobación.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización aprobada sin responsable"
    )
    void shouldDenyAuthorizationWithoutApprover() {
        UUID authorizationId = insertAuthorization(
                "APPROVED",
                "EXECUTE_ACTION",
                null,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                null
        );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Verifica la coincidencia exacta del alcance.
     */
    @Test
    @DisplayName(
            "Debe denegar una operación fuera del alcance autorizado"
    )
    void shouldDenyDifferentScope() {
        UUID authorizationId = insertValidAuthorization();

        AuthorizationUseRequest request =
                new AuthorizationUseRequest(
                        null,
                        actionId,
                        "DELETE_SYSTEM",
                        NOW
                );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        request
                ).allowed()
        );
    }

    /**
     * Comprueba que una autorización sin plan
     * no pueda aplicarse a una operación que
     * exige un plan concreto.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización sin el plan requerido"
    )
    void shouldDenyDifferentPlan() {
        UUID authorizationId = insertValidAuthorization();

        AuthorizationUseRequest request =
                new AuthorizationUseRequest(
                        planId,
                        actionId,
                        "EXECUTE_ACTION",
                        NOW
                );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        request
                ).allowed()
        );
    }

    /**
     * Comprueba que la identidad de la acción
     * coincida con la autorizada.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización para una acción diferente"
    )
    void shouldDenyDifferentAction() {
        UUID authorizationId = insertValidAuthorization();

        AuthorizationUseRequest request =
                new AuthorizationUseRequest(
                        null,
                        UUID.randomUUID(),
                        "EXECUTE_ACTION",
                        NOW
                );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        request
                ).allowed()
        );
    }

    /**
     * Comprueba que no se permita utilizar
     * una autorización desde el instante
     * exacto de su vencimiento.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización vencida"
    )
    void shouldDenyExpiredAuthorization() {
        UUID authorizationId = insertAuthorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW,
                null
        );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Verifica que la revocación impida
     * utilizar la autorización.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización revocada"
    )
    void shouldDenyRevokedAuthorization() {
        UUID authorizationId = insertAuthorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.plus(1, ChronoUnit.HOURS),
                NOW.minusSeconds(30)
        );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba el tratamiento de una autorización
     * cuyo vencimiento explícito es nulo.
     *
     * <p>Según la política IR-010 actual, null
     * significa ausencia de fecha de vencimiento.
     */
    @Test
    @DisplayName(
            "Debe aceptar una autorización válida sin vencimiento explícito"
    )
    void shouldAcceptAuthorizationWithoutExpiration() {
        UUID authorizationId = insertAuthorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                NOW.minus(1, ChronoUnit.HOURS),
                null,
                null
        );

        assertTrue(
                service.evaluate(
                        authorizationId,
                        validRequest()
                ).allowed()
        );
    }

    /**
     * Comprueba que una autorización no
     * pueda utilizarse antes de su emisión.
     */
    @Test
    @DisplayName(
            "Debe denegar una autorización con fecha de emisión futura"
    )
    void shouldDenyAuthorizationBeforeIssuedAt() {
        UUID authorizationId = insertAuthorization(
                "APPROVED",
                "EXECUTE_ACTION",
                "human-approver",
                NOW.plusSeconds(60),
                NOW.plusSeconds(3600),
                null
        );

        assertFalse(
                service.evaluate(
                        authorizationId,
                        validRequest()
                ).allowed()
        );
    }
}
