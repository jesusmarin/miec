package com.miec.action;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Propiedad temporal sin apropiación automática ni inferencias sobre efectos externos. */
@Service
public class ExecutionActivityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String executorId = UUID.randomUUID().toString();
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    /** Credenciales de una adquisición; el token es global y nunca se reutiliza. */
    public record Ownership(UUID executionId, String executorId, long fencingToken) {
        /** Exige identidad completa y token positivo. */
        public Ownership {
            Objects.requireNonNull(executionId);
            Objects.requireNonNull(executorId);
            if (executorId.isBlank() || executorId.length() > 255 || fencingToken < 1) {
                throw new IllegalArgumentException("Propiedad inválida");
            }
        }
    }

    /** Configura vigencia y frecuencia, con margen para renovaciones. */
    public ExecutionActivityService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${miec.execution.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${miec.execution.heartbeat-interval:PT5S}") Duration heartbeatInterval) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval);
        if (heartbeatInterval.compareTo(Duration.ofMillis(1)) < 0
                || leaseDuration.compareTo(Duration.ofHours(1)) > 0
                || heartbeatInterval.multipliedBy(2).compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("Heartbeat debe ser positivo y menor que la mitad del lease (máximo 1h)");
        }
    }

    /** Devuelve la identidad única de esta instancia de proceso. */
    public String executorId() { return executorId; }

    /** Devuelve la vigencia configurada para cada adquisición y renovación. */
    public Duration leaseDuration() { return leaseDuration; }

    /** Devuelve la frecuencia del supervisor. */
    public Duration heartbeatInterval() { return heartbeatInterval; }

    /** Lee el reloj de pared del motor, incluso después de esperar un bloqueo; no usa inicio de transacción. */
    Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT miec_wall_clock()",
                (rs, row) -> rs.getTimestamp(1).toInstant()));
    }

    /** Reserva una generación global; un rollback puede dejar huecos. */
    long nextToken() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT nextval('execution_fencing_sequence')", Long.class));
    }

    /**
     * Renueva solo una propiedad vigente. Cero filas o lease vencido rechazan
     * la renovación; los errores de conexión se propagan sin habilitar reintentos.
     *
     * @param owner credenciales devueltas por el inicio autorizado
     * @return nueva fecha de vencimiento confirmada
     */
    public Instant heartbeat(Ownership owner) {
        Objects.requireNonNull(owner);
        return Objects.requireNonNull(transaction.execute(status -> {
            var rows = jdbc.query("""
                    SELECT lease_expires_at FROM execution_attempt
                    WHERE execution_id=? AND executor_id=? AND fencing_token=?
                      AND execution_status='IN_PROGRESS' FOR UPDATE
                    """, (rs, row) -> rs.getTimestamp(1).toInstant(),
                    owner.executionId(), owner.executorId(), owner.fencingToken());
            Instant now = databaseNow();
            if (rows.isEmpty() || !rows.getFirst().isAfter(now)) {
                throw new ExecutionAttemptService.AttemptDeniedException("IR-009: propiedad ausente o vencida");
            }
            Instant expires = now.plus(leaseDuration);
            int count = jdbc.update("""
                    UPDATE execution_attempt SET heartbeat_at=?,lease_expires_at=?,version=version+1
                    WHERE execution_id=? AND executor_id=? AND fencing_token=?
                      AND execution_status='IN_PROGRESS' AND lease_expires_at>?
                    """, java.sql.Timestamp.from(now), java.sql.Timestamp.from(expires),
                    owner.executionId(), owner.executorId(), owner.fencingToken(), java.sql.Timestamp.from(now));
            if (count != 1) throw new ExecutionAttemptService.AttemptDeniedException("IR-009: renovación rechazada");
            return expires;
        }));
    }
}
