
package com.miec.authorization;

import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationDecision;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationSnapshot;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio de consulta y evaluación de autorizaciones persistidas.
 *
 * <p>Implementa la integración de IR-010 con la base de datos
 * definida en MIEC-F03, utilizando Flyway V001 y V002.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Consultar autorizaciones por su identificador.</li>
 *   <li>Reconstruir el contexto necesario para su evaluación.</li>
 *   <li>Delegar las reglas de vigencia, alcance y estado
 *       a AuthorizationValidityPolicy.</li>
 *   <li>Denegar el uso de autorizaciones inexistentes.</li>
 *   <li>Conservar la trazabilidad mediante decisiones justificadas.</li>
 * </ul>
 *
 * <p>Este servicio no crea, aprueba, modifica, revoca
 * ni elimina autorizaciones.
 *
 * <p>No implementa IR-009, relativa a la exigencia
 * de autorización previa a la ejecución.
 *
 * <p>Las consultas son de solo lectura y se ejecutan
 * mediante JdbcTemplate, evitando introducir
 * entidades JPA adicionales.
 */
@Service
public class AuthorizationPersistenceService {

    /** Acceso JDBC a las autorizaciones persistidas. */
    private final JdbcTemplate jdbcTemplate;

    /** Política de validación de autorizaciones. */
    private final AuthorizationValidityPolicy validityPolicy;

    /**
     * Construye el servicio con sus dependencias.
     *
     * @param jdbcTemplate acceso obligatorio a la base de datos
     * @param validityPolicy política obligatoria de IR-010
     */
    public AuthorizationPersistenceService(
            JdbcTemplate jdbcTemplate,
            AuthorizationValidityPolicy validityPolicy
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "JdbcTemplate es obligatorio"
        );

        this.validityPolicy = Objects.requireNonNull(
                validityPolicy,
                "AuthorizationValidityPolicy es obligatoria"
        );
    }

    /**
     * Recupera una autorización existente por identificador.
     *
     * <p>Obtiene únicamente las columnas utilizadas por
     * la política IR-010.
     *
     * <p>Las fechas almacenadas como TIMESTAMP se
     * convierten a Instant para su evaluación.
     *
     * @param authorizationId identificador de autorización
     * @return autorización encontrada o Optional.empty()
     * @throws NullPointerException si el identificador es nulo
     */
    @Transactional(readOnly = true)
    public Optional<AuthorizationSnapshot> findById(
            UUID authorizationId
    ) {
        Objects.requireNonNull(
                authorizationId,
                "authorizationId es obligatorio"
        );

        List<AuthorizationSnapshot> results = jdbcTemplate.query(
                """
                SELECT
                    authorization_id,
                    plan_id,
                    action_id,
                    scope,
                    status,
                    approved_by,
                    issued_at,
                    expires_at,
                    revoked_at
                FROM authorizations
                WHERE authorization_id = ?
                """,
                (rs, rowNum) -> new AuthorizationSnapshot(
                        rs.getObject(
                                "authorization_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "plan_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "action_id",
                                UUID.class
                        ),
                        rs.getString("scope"),
                        rs.getString("status"),
                        rs.getString("approved_by"),
                        toInstant(
                                rs.getTimestamp("issued_at")
                        ),
                        toInstant(
                                rs.getTimestamp("expires_at")
                        ),
                        toInstant(
                                rs.getTimestamp("revoked_at")
                        )
                ),
                authorizationId
        );

        return results.stream().findFirst();
    }

    /**
     * Convierte un TIMESTAMP SQL a Instant.
     *
     * <p>Las fechas opcionales pueden ser nulas.
     * La fecha issued_at es obligatoria según V001.
     *
     * @param timestamp fecha procedente de JDBC
     * @return instante correspondiente o null
     */
    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toInstant();
    }

    /**
     * Evalúa una autorización persistida frente
     * a una solicitud de uso determinada.
     *
     * <p>Si la autorización existe, delega la decisión
     * en AuthorizationValidityPolicy.
     *
     * <p>Si no existe, devuelve una denegación.
     * No concede permisos por ausencia de información.
     *
     * @param authorizationId identificador de autorización
     * @param request contexto de la operación solicitada
     * @return decisión justificada de autorización
     * @throws NullPointerException ante argumentos nulos
     */
    @Transactional(readOnly = true)
    public AuthorizationDecision evaluate(
            UUID authorizationId,
            AuthorizationUseRequest request
    ) {
        Objects.requireNonNull(
                authorizationId,
                "authorizationId es obligatorio"
        );

        Objects.requireNonNull(
                request,
                "AuthorizationUseRequest es obligatorio"
        );

        Optional<AuthorizationSnapshot> authorization =
                findById(authorizationId);

        if (authorization.isEmpty()) {
            return new AuthorizationDecision(
                    false,
                    "La autorización no existe: " + authorizationId
            );
        }

        return validityPolicy.evaluate(
                authorization.get(),
                request
        );
    }
}
