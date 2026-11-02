
package com.miec.action;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MIEC-F03 IR-009-C08.1.
 *
 * Servicio de diagnóstico de intentos de ejecución
 * potencialmente interrumpidos.
 *
 * <p>Consulta intentos IN_PROGRESS cuya fecha de inicio
 * supera un umbral de antigüedad configurado.
 *
 * <p>Principios:
 * <ul>
 *   <li>Solo lectura: no modifica execution_attempt.</li>
 *   <li>No declara automáticamente una ejecución abandonada.</li>
 *   <li>No habilita reintentos ni modifica autorizaciones.</li>
 *   <li>Devuelve información para reconciliación posterior.</li>
 * </ul>
 *
 * <p>Los intentos administrados se revisan por vencimiento de su lease.
 * Los históricos sin propiedad conservan el criterio por started_at.
 * Un heartbeat confirma actividad del supervisor, no progreso del trabajo.
 */
@Service
public class InterruptedExecutionDetectionService {

    private static final int MAX_RESULTS = 1000;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * Constructor utilizado por Spring.
     *
     * @param jdbc acceso de solo lectura a los intentos
     */
    @Autowired
    public InterruptedExecutionDetectionService(
            JdbcTemplate jdbc
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = null;
    }

    /**
     * Constructor con reloj inyectable para pruebas.
     *
     * @param jdbc acceso a la base de datos
     * @param clock fuente de tiempo
     */
    InterruptedExecutionDetectionService(
            JdbcTemplate jdbc,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Información de un intento que requiere revisión.
     *
     * <p>Este registro no representa una autorización
     * para reintentar ni una decisión de reconciliación.
     *
     * @param executionId identificador del intento
     * @param planId plan de intervención
     * @param actionId acción planificada
     * @param attemptNumber número del intento
     * @param startedAt momento de inicio registrado
     */
    public record ReviewCandidate(
            UUID executionId,
            UUID planId,
            UUID actionId,
            int attemptNumber,
            Instant startedAt
    ) {
        public ReviewCandidate {
            Objects.requireNonNull(executionId);
            Objects.requireNonNull(planId);
            Objects.requireNonNull(actionId);
            Objects.requireNonNull(startedAt);

            if (attemptNumber < 1) {
                throw new IllegalArgumentException(
                        "attemptNumber debe ser positivo"
                );
            }
        }
    }

    /** Clasificación de diagnóstico; no representa una transición ni permiso de reintento. */
    public enum ActivityClassification { ACTIVA, REVISION, INDETERMINADA }

    /** Identidad, clasificación y evidencia temporal de una ejecución en curso. */
    public record ActivityDiagnostic(UUID executionId, ActivityClassification classification,
            String executorId, Long fencingToken, Instant heartbeatAt, Instant leaseExpiresAt) { }

    /** Lee el reloj del motor en producción o el reloj fijo inyectado para pruebas. */
    private Instant now() {
        return clock == null ? Objects.requireNonNull(jdbc.queryForObject("SELECT miec_wall_clock()",
                (rs, row) -> rs.getTimestamp(1).toInstant())) : clock.instant();
    }

    /**
     * Clasifica hasta mil intentos en curso sin modificarlos. Un lease vigente
     * acredita actividad; uno vencido exige revisión; la ausencia de propiedad
     * señala un registro histórico indeterminado.
     *
     * @param maxResults máximo de resultados entre 1 y 1000
     * @return diagnósticos inmutables ordenados por inicio e identificador
     */
    public List<ActivityDiagnostic> diagnose(int maxResults) {
        if (maxResults < 1 || maxResults > MAX_RESULTS) throw new IllegalArgumentException("Límite inválido");
        Instant now = now();
        return List.copyOf(jdbc.query("""
                SELECT execution_id,executor_id,fencing_token,heartbeat_at,lease_expires_at
                FROM execution_attempt WHERE execution_status='IN_PROGRESS'
                ORDER BY started_at,execution_id LIMIT ?
                """, (rs, row) -> {
            var expiry = rs.getTimestamp("lease_expires_at");
            var heartbeat = rs.getTimestamp("heartbeat_at");
            var classification = expiry == null ? ActivityClassification.INDETERMINADA
                    : expiry.toInstant().isAfter(now) ? ActivityClassification.ACTIVA : ActivityClassification.REVISION;
            return new ActivityDiagnostic(rs.getObject("execution_id", UUID.class), classification,
                    rs.getString("executor_id"), rs.getObject("fencing_token", Long.class),
                    heartbeat == null ? null : heartbeat.toInstant(), expiry == null ? null : expiry.toInstant());
        }, maxResults));
    }

    /**
     * Busca leases vencidos y registros históricos IN_PROGRESS cuya antigüedad
     * sea igual o superior al umbral indicado. Un lease vigente queda excluido
     * independientemente de la duración total de la ejecución.
     *
     * <p>No realiza transiciones de estado.
     *
     * @param minimumAge antigüedad mínima positiva
     * @param maxResults cantidad máxima de registros
     * @return candidatos ordenados por fecha de inicio
     */
    public List<ReviewCandidate> findCandidates(
            Duration minimumAge,
            int maxResults
    ) {
        Objects.requireNonNull(
                minimumAge,
                "minimumAge es obligatorio"
        );

        if (minimumAge.isZero() || minimumAge.isNegative()) {
            throw new IllegalArgumentException(
                    "minimumAge debe ser positivo"
            );
        }

        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new IllegalArgumentException(
                    "maxResults debe estar entre 1 y "
                            + MAX_RESULTS
            );
        }

        Instant now = now();
        Instant cutoff = now.minus(minimumAge);

        String sql = """
                SELECT
                    execution_id,
                    plan_id,
                    planned_action_id,
                    attempt_number,
                    started_at
                FROM execution_attempt
                WHERE execution_status = 'IN_PROGRESS'
                  AND ((executor_id IS NULL AND started_at <= ?)
                    OR (executor_id IS NOT NULL AND lease_expires_at <= ?))
                ORDER BY started_at ASC, execution_id ASC
                LIMIT ?
                """;

        List<ReviewCandidate> candidates = jdbc.query(
                sql,
                (rs, rowNum) -> new ReviewCandidate(
                        rs.getObject(
                                "execution_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "plan_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "planned_action_id",
                                UUID.class
                        ),
                        rs.getInt("attempt_number"),
                        rs.getTimestamp(
                                "started_at"
                        ).toInstant()
                ),
                Timestamp.from(cutoff),
                Timestamp.from(now),
                maxResults
        );

        return List.copyOf(candidates);
    }
}
