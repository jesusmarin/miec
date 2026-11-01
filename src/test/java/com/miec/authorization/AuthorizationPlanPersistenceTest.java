
package com.miec.authorization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración para la regla IR-008 de MIEC-F03.
 *
 * <p>Regla: una autorización con ámbito de plan debe
 * referenciar un plan de intervención existente.
 *
 * <p>La restricción se implementa en Flyway V002 mediante
 * FK_AUTHORIZATION_PLAN sobre la tabla authorizations.
 *
 * <p>Estas pruebas verifican:
 * <ul>
 *   <li>La aceptación de autorizaciones con plan existente.</li>
 *   <li>El rechazo de referencias a planes inexistentes.</li>
 *   <li>La nulabilidad de plan_id definida en V001.</li>
 *   <li>La posibilidad de varias autorizaciones por plan.</li>
 *   <li>La protección frente a eliminación de planes usados.</li>
 * </ul>
 *
 * <p>Se utiliza JDBC directamente para verificar la
 * integridad SQL, sin depender de nuevas entidades JPA.
 *
 * <p>La base H2 es independiente de la base principal
 * de desarrollo. Flyway aplica V001 y V002.
 */
@SpringBootTest(
        properties = {
                "spring.datasource.url="
                        + "jdbc:h2:mem:miec_authorization_plan_it;"
                        + "DB_CLOSE_DELAY=-1",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate"
        }
)
@Transactional
@DisplayName(
        "MIEC-F03 IR-008 - Integridad de autorizaciones y planes"
)
class AuthorizationPlanPersistenceTest {

    /** Acceso a la base de datos de integración. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Crea un sistema objetivo válido.
     *
     * @return identificador del sistema creado
     */
    private UUID createSystem() {
        UUID systemId = UUID.randomUUID();

        int rows = jdbcTemplate.update(
                """
                INSERT INTO system_objective (
                    system_id,
                    name
                )
                VALUES (?, ?)
                """,
                systemId,
                "Sistema de prueba IR-008"
        );

        assertEquals(1, rows);
        return systemId;
    }

    /**
     * Crea un ciclo perteneciente al sistema indicado.
     *
     * @param systemId identificador del sistema
     * @return identificador del ciclo creado
     */
    private UUID createCycle(UUID systemId) {
        UUID cycleId = UUID.randomUUID();

        int rows = jdbcTemplate.update(
                """
                INSERT INTO cycle (
                    cycle_id,
                    system_id
                )
                VALUES (?, ?)
                """,
                cycleId,
                systemId
        );

        assertEquals(1, rows);
        return cycleId;
    }

    /**
     * Crea un plan de intervención válido.
     *
     * @return identificador del plan persistido
     */
    private UUID createPlan() {
        UUID systemId = createSystem();
        UUID cycleId = createCycle(systemId);
        UUID planId = UUID.randomUUID();

        int rows = jdbcTemplate.update(
                """
                INSERT INTO intervention_plan (
                    plan_id,
                    cycle_id,
                    status
                )
                VALUES (?, ?, ?)
                """,
                planId,
                cycleId,
                "PROPOSED"
        );

        assertEquals(1, rows);
        return planId;
    }

    /**
     * Inserta una autorización vinculada a un plan.
     *
     * <p>planId puede ser null porque V001 define
     * la columna plan_id como opcional.
     *
     * @param authorizationId identificador de autorización
     * @param planId identificador del plan o null
     * @return número de filas insertadas
     */
    private int insertAuthorization(
            UUID authorizationId,
            UUID planId
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO authorizations (
                    authorization_id,
                    plan_id,
                    requested_by,
                    status,
                    issued_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                authorizationId,
                planId,
                "miec-test",
                "PENDING",
                Timestamp.from(Instant.now())
        );
    }

    /**
     * Consulta cuántas autorizaciones existen
     * con el identificador proporcionado.
     *
     * @param authorizationId identificador consultado
     * @return cantidad de registros existentes
     */
    private int countAuthorization(UUID authorizationId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM authorizations
                WHERE authorization_id = ?
                """,
                Integer.class,
                authorizationId
        );

        return Objects.requireNonNull(count);
    }

    /**
     * Verifica que pueda registrarse una autorización
     * asociada a un plan previamente persistido.
     */
    @Test
    @DisplayName(
            "Debe aceptar una autorización que referencia un plan existente"
    )
    void shouldAcceptAuthorizationWithExistingPlan() {
        UUID planId = createPlan();
        UUID authorizationId = UUID.randomUUID();

        int rows = insertAuthorization(
                authorizationId,
                planId
        );

        assertEquals(1, rows);

        UUID recoveredPlanId = jdbcTemplate.queryForObject(
                """
                SELECT plan_id
                FROM authorizations
                WHERE authorization_id = ?
                """,
                UUID.class,
                authorizationId
        );

        assertEquals(planId, recoveredPlanId);
    }

    /**
     * Verifica que una autorización no pueda
     * referenciar un plan inexistente.
     *
     * <p>La inserción debe ser rechazada por
     * FK_AUTHORIZATION_PLAN.
     */
    @Test
    @DisplayName(
            "Debe rechazar autorizaciones vinculadas a planes inexistentes"
    )
    void shouldRejectAuthorizationWithUnknownPlan() {
        UUID authorizationId = UUID.randomUUID();
        UUID unknownPlanId = UUID.randomUUID();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAuthorization(
                        authorizationId,
                        unknownPlanId
                )
        );

        assertEquals(
                0,
                countAuthorization(authorizationId)
        );
    }

    /**
     * Comprueba que plan_id pueda ser nulo.
     *
     * <p>Esta prueba verifica exclusivamente
     * la nulabilidad del esquema V001.
     *
     * <p>La validez funcional de una autorización
     * sin plan debe evaluarse posteriormente
     * según su ámbito y reglas de negocio.
     */
    @Test
    @DisplayName(
            "Debe permitir una autorización sin plan porque plan_id es opcional"
    )
    void shouldAcceptAuthorizationWithoutPlan() {
        UUID authorizationId = UUID.randomUUID();

        int rows = insertAuthorization(
                authorizationId,
                null
        );

        assertEquals(1, rows);
        assertEquals(
                1,
                countAuthorization(authorizationId)
        );
    }

    /**
     * Comprueba que el mismo plan pueda recibir
     * varias autorizaciones diferentes.
     *
     * <p>IR-008 no establece unicidad de plan_id
     * en la tabla authorizations.
     */
    @Test
    @DisplayName(
            "Debe permitir varias autorizaciones asociadas al mismo plan"
    )
    void shouldAllowMultipleAuthorizationsForSamePlan() {
        UUID planId = createPlan();

        UUID firstAuthorizationId = UUID.randomUUID();
        UUID secondAuthorizationId = UUID.randomUUID();

        assertEquals(
                1,
                insertAuthorization(
                        firstAuthorizationId,
                        planId
                )
        );

        assertEquals(
                1,
                insertAuthorization(
                        secondAuthorizationId,
                        planId
                )
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM authorizations
                WHERE plan_id = ?
                """,
                Integer.class,
                planId
        );

        assertEquals(2, count);
    }

    /**
     * Verifica que la base impida eliminar un plan
     * mientras existan autorizaciones que lo referencien.
     *
     * <p>Esta comprobación se basa en la restricción
     * de clave foránea sin ON DELETE CASCADE.
     *
     * <p>La eliminación de registros vinculados debe
     * realizarse mediante una operación de dominio
     * explícita que preserve la trazabilidad.
     */
    @Test
    @DisplayName(
            "Debe impedir eliminar un plan referenciado por una autorización"
    )
    void shouldRejectDeletionOfReferencedPlan() {
        UUID planId = createPlan();
        UUID authorizationId = UUID.randomUUID();

        insertAuthorization(authorizationId, planId);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        DELETE FROM intervention_plan
                        WHERE plan_id = ?
                        """,
                        planId
                )
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM intervention_plan
                WHERE plan_id = ?
                """,
                Integer.class,
                planId
        );

        assertEquals(1, count);
    }
}
