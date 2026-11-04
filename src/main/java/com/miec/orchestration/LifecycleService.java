package com.miec.orchestration;

import com.miec.cycle.CycleEntity;
import com.miec.domain.EventType;
import com.miec.domain.Cycle;
import com.miec.state.CycleState;
import com.miec.system.SystemObjectiveEntity;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import static com.miec.orchestration.LifecycleCommands.*;
import static com.miec.orchestration.LifecycleViews.*;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Casos de uso locales del MVP; coordina datos y auditoría dentro de una transacción. */
@Service
public class LifecycleService {
    private final JdbcTemplate jdbc;
    private final Validator validator;

    /** Recibe acceso transaccional a la base y las mismas validaciones utilizadas por REST. */
    public LifecycleService(JdbcTemplate jdbc, Validator validator) {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    /** Registra un sistema activo; no concede autorizaciones de ejecución. */
    @Transactional(timeout=10)
    public SystemView createSystem(CreateSystem command) {
        validate(command);
        var entity = new SystemObjectiveEntity(command.name()).domain(command.domain())
                .description(command.description()).ownerReference(command.ownerReference());
        UUID id = UUID.randomUUID();
        Instant now = now();
        jdbc.update("""
                INSERT INTO system_objective(system_id,name,domain,description,owner_reference,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, entity.getName(), entity.getDomain(), entity.getDescription(), entity.getOwnerReference(),
                entity.getStatus(), Timestamp.from(now), Timestamp.from(now));
        return getSystem(id);
    }

    /** Consulta datos persistidos sin exponer una entidad modificable. */
    @Transactional(readOnly=true)
    public SystemView getSystem(UUID systemId) {
        requireId(systemId);
        return jdbc.query("SELECT * FROM system_objective WHERE system_id=?", this::system, systemId).stream()
                .findFirst().orElseThrow(() -> new LifecycleException(NOT_FOUND, "SYSTEM_NOT_FOUND", "El sistema no existe"));
    }

    /**
     * Crea un ciclo inactivo A0 y su evento. Bloquear el sistema serializa números
     * y respeta el orden de bloqueo utilizado por CycleHierarchyService.
     */
    @Transactional(timeout=10)
    public CycleView createCycle(UUID systemId, CreateCycle command, String actorId) {
        requireId(systemId);
        validate(command);
        String actor = actor(actorId);
        lockActiveSystem(systemId);
        long number = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COALESCE(MAX(cycle_number),0) FROM cycle WHERE system_id=?", Long.class, systemId)) + 1;
        if (number > Integer.MAX_VALUE) throw new LifecycleException(CONFLICT, "CYCLE_NUMBER_EXHAUSTED", "Se agotó la numeración del sistema");
        var entity = new CycleEntity(systemId).configurationVersionId(command.configurationVersionId());
        UUID id = UUID.randomUUID();
        Instant now = now();
        jdbc.update("""
                INSERT INTO cycle(cycle_id,system_id,cycle_number,state,autonomy_level,configuration_version_id,created_at,updated_at,version)
                VALUES (?,?,?,?,?,?,?,?,0)
                """, id, systemId, (int) number, entity.getState(), entity.getAutonomyLevel(),
                entity.getConfigurationVersionId(), Timestamp.from(now), Timestamp.from(now));
        event(id, EventType.CYCLE_CREATED, actor, "Ciclo creado desde API MVP", now);
        return getCycle(id);
    }

    /** Consulta el ciclo y su versión de concurrencia. */
    @Transactional(readOnly=true)
    public CycleView getCycle(UUID cycleId) {
        requireId(cycleId);
        return jdbc.query("SELECT * FROM cycle WHERE cycle_id=?", this::cycle, cycleId).stream()
                .findFirst().orElseThrow(() -> new LifecycleException(NOT_FOUND, "CYCLE_NOT_FOUND", "El ciclo no existe"));
    }

    /**
     * Inicia una sola vez desde E00 usando la regla del dominio y la versión leída
     * por el cliente. Estado, fecha, evento y transición confirman o revierten juntos.
     */
    @Transactional(timeout=10)
    public CycleView startCycle(UUID cycleId, StartCycle command, String actorId) {
        requireId(cycleId);
        validate(command);
        String actor = actor(actorId);
        var before = getCycle(cycleId);
        lockActiveSystem(before.systemId());
        var locked = jdbc.query("SELECT * FROM cycle WHERE cycle_id=? FOR UPDATE", this::cycle, cycleId);
        if (locked.isEmpty()) throw new LifecycleException(NOT_FOUND, "CYCLE_NOT_FOUND", "El ciclo no existe");
        var current = locked.getFirst();
        if (!current.systemId().equals(before.systemId())) throw new LifecycleException(CONFLICT, "CYCLE_CHANGED", "Cambió el sistema del ciclo");
        if (current.version() != command.expectedVersion()) {
            throw new LifecycleException(CONFLICT, "VERSION_CONFLICT", "La versión del ciclo cambió; consulte su estado actual");
        }
        CycleState source;
        try { source = CycleState.valueOf(current.state()); }
        catch (IllegalArgumentException invalid) { throw new LifecycleException(CONFLICT, "INVALID_CYCLE_STATE", "El ciclo no tiene un estado reconocido"); }
        if (!Cycle.isValidTransition(source, CycleState.E01_INICIALIZANDO)) {
            throw new LifecycleException(CONFLICT, "TRANSITION_NOT_ALLOWED", "Solo se puede iniciar un ciclo inactivo");
        }
        Instant time = now();
        if (jdbc.update("""
                UPDATE cycle SET state='E01_INICIALIZANDO',started_at=?,updated_at=?,version=version+1
                WHERE cycle_id=? AND version=? AND state='E00_INACTIVO' AND started_at IS NULL AND closed_at IS NULL
                """, Timestamp.from(time), Timestamp.from(time), cycleId, command.expectedVersion()) != 1) {
            throw new LifecycleException(CONFLICT, "INVALID_CYCLE_STATE", "El ciclo no admite un nuevo inicio");
        }
        UUID eventId = event(cycleId, EventType.STATE_TRANSITION, actor, command.reason(), time);
        jdbc.update("""
                INSERT INTO state_transition(transition_id,cycle_id,from_state,to_state,trigger_event_id,transition_rule_id,transition_status,occurred_at,created_at)
                VALUES (?,?,'E00_INACTIVO','E01_INICIALIZANDO',?,'MIEC-F01:E00->E01','COMPLETED',?,?)
                """, UUID.randomUUID(), cycleId, eventId, Timestamp.from(time), Timestamp.from(time));
        return getCycle(cycleId);
    }

    /**
     * Consulta transiciones por cursor estable y con un máximo de cien registros.
     * La lectura repetible mantiene coherentes la existencia, el cursor y su página.
     */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public HistoryPage history(UUID cycleId, int limit, UUID after) {
        requireId(cycleId);
        if (limit < 1 || limit > 100) throw new LifecycleException(BAD_REQUEST, "INVALID_LIMIT", "limit debe estar entre 1 y 100");
        getCycle(cycleId);
        String filter = "";
        var parameters = new ArrayList<Object>();
        parameters.add(cycleId);
        if (after != null) {
            var cursor = jdbc.query("SELECT occurred_at FROM state_transition WHERE transition_id=? AND cycle_id=?",
                    (rs, row) -> rs.getTimestamp(1), after, cycleId);
            if (cursor.isEmpty()) throw new LifecycleException(BAD_REQUEST, "INVALID_CURSOR", "El cursor no pertenece al ciclo consultado");
            filter = " AND (t.occurred_at>? OR (t.occurred_at=? AND t.transition_id>?))";
            parameters.add(cursor.getFirst());
            parameters.add(cursor.getFirst());
            parameters.add(after);
        }
        parameters.add(limit + 1);
        var rows = jdbc.query("""
                SELECT t.*,e.actor_id,e.reason FROM state_transition t
                LEFT JOIN cycle_event e ON e.event_id=t.trigger_event_id AND e.cycle_id=t.cycle_id
                WHERE t.cycle_id=?
                """ + filter + " ORDER BY t.occurred_at ASC,t.transition_id ASC LIMIT ?", this::transition, parameters.toArray());
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new HistoryPage(page, rows.size() > limit ? page.getLast().transitionId() : null);
    }

    /** Conserva fechas y campos desconocidos de las transiciones históricas sin inventar auditoría. */
    private TransitionView transition(ResultSet rs, int row) throws SQLException {
        return new TransitionView(rs.getObject("transition_id", UUID.class), rs.getObject("cycle_id", UUID.class),
                rs.getString("from_state"), rs.getString("to_state"), rs.getObject("trigger_event_id", UUID.class),
                rs.getString("actor_id"), rs.getString("reason"), rs.getString("transition_rule_id"),
                rs.getString("transition_status"), rs.getTimestamp("occurred_at").toInstant());
    }

    /** Bloquea el sistema antes de sus ciclos; un sistema inactivo no admite comandos nuevos. */
    void lockActiveSystem(UUID systemId) {
        var systems = jdbc.query("SELECT status FROM system_objective WHERE system_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), systemId);
        if (systems.isEmpty()) throw new LifecycleException(NOT_FOUND, "SYSTEM_NOT_FOUND", "El sistema no existe");
        if (!"ACTIVE".equals(systems.getFirst())) throw new LifecycleException(CONFLICT, "SYSTEM_NOT_ACTIVE", "El sistema no está activo");
    }

    /** Registra procedencia y motivo sin utilizar payload_reference como texto libre. */
    UUID event(UUID cycleId, EventType type, String actor, String reason, Instant time) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cycle_event(event_id,cycle_id,event_type,actor_id,occurred_at,recorded_at,created_at,reason)
                VALUES (?,?,?,?,?,?,?,?)
                """, eventId, cycleId, type.name(), actor, Timestamp.from(time), Timestamp.from(time), Timestamp.from(time), reason);
        return eventId;
    }

    /** Valida también invocaciones internas que no pasan por @Valid en un controlador. */
    void validate(Object command) {
        if (command == null || !validator.validate(command).isEmpty()) {
            throw new LifecycleException(BAD_REQUEST, "VALIDATION_ERROR", "El comando contiene campos inválidos");
        }
    }

    /** Exige una identidad declarada de auditoría, sin confundirla con autenticación. */
    static String actor(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 255) {
            throw new LifecycleException(BAD_REQUEST, "INVALID_ACTOR", "X-MIEC-Actor es obligatorio y admite hasta 255 caracteres");
        }
        return value.trim();
    }

    /** Rechaza identidades ausentes al invocar directamente el servicio. */
    private static void requireId(UUID id) {
        if (id == null) throw new LifecycleException(BAD_REQUEST, "INVALID_ID", "El identificador es obligatorio");
    }

    /** Usa el reloj real tras bloquear, truncado a microsegundos para evitar redondeos futuros en TIMESTAMP(6). */
    Instant now() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT miec_wall_clock()", (rs, row) -> rs.getTimestamp(1).toInstant())).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /** Reconstruye un sistema desde sus columnas persistidas. */
    SystemView system(ResultSet rs, int row) throws SQLException {
        return new SystemView(rs.getObject("system_id", UUID.class), rs.getString("name"), rs.getString("domain"),
                rs.getString("description"), rs.getString("owner_reference"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /** Reconstruye el estado observable de un ciclo sin cambiarlo. */
    CycleView cycle(ResultSet rs, int row) throws SQLException {
        var started = rs.getTimestamp("started_at");
        return new CycleView(rs.getObject("cycle_id", UUID.class), rs.getObject("system_id", UUID.class),
                rs.getObject("cycle_number", Integer.class), rs.getString("state"), rs.getString("autonomy_level"),
                rs.getString("configuration_version_id"), started == null ? null : started.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }
}
