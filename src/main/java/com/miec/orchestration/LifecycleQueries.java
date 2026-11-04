package com.miec.orchestration;

import com.miec.state.CycleState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.*;
import static com.miec.orchestration.LifecycleNavigationViews.*;
import static com.miec.orchestration.LifecycleException.Kind.BAD_REQUEST;

/** Listados de solo lectura, con una instantanea por pagina y agregaciones acotadas a sus sistemas. */
@Service
public class LifecycleQueries {
    private final JdbcTemplate jdbc;
    private final LifecycleService lifecycle;

    /** Comparte mapeadores de detalle y existencia de recursos sin modificar el servicio de comandos. */
    public LifecycleQueries(JdbcTemplate jdbc, LifecycleService lifecycle) { this.jdbc = jdbc; this.lifecycle = lifecycle; }

    /**
     * Lista sistemas por creacion descendente, con busqueda literal de nombre y estado administrativo exacto.
     * Ejecuta hasta tres consultas sin cursor y cuatro con cursor, independientemente del numero de elementos.
     * La consistencia es por pagina; no se conserva una transaccion entre peticiones HTTP.
     */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public SystemPage systems(int limit, String after, String q, String status) {
        validateLimit(limit);
        q = filter(q, 255); status = filter(status, 50);
        if (q != null) q = q.toLowerCase(Locale.ROOT);
        String context = NavigationCursor.context("systems", q, status);
        var anchor = NavigationCursor.decode(after, context);
        if (anchor != null) validateAnchor("SELECT created_at FROM system_objective WHERE system_id=?", anchor, anchor.id());
        var conditions = new StringBuilder(" WHERE 1=1");
        var parameters = new ArrayList<Object>();
        if (q != null) {
            conditions.append(" AND LOWER(s.name) LIKE ? ESCAPE '!'");
            parameters.add("%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (status != null) { conditions.append(" AND s.status=?"); parameters.add(status); }
        appendPosition(conditions, parameters, "s.system_id", anchor);
        parameters.add(limit + 1);
        var rows = jdbc.query("SELECT s.* FROM system_objective s" + conditions + " ORDER BY s.created_at DESC,s.system_id DESC LIMIT ?",
                lifecycle::system, parameters.toArray());
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        if (page.isEmpty()) return new SystemPage(List.of(), null);
        Object[] ids = page.stream().map(LifecycleViews.SystemView::systemId).toArray();
        String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
        var counts = new HashMap<UUID, Map<String, Long>>();
        jdbc.query("SELECT system_id,state,COUNT(*) AS quantity FROM cycle WHERE system_id IN (" + placeholders + ") GROUP BY system_id,state",
                (rs, row) -> {
                    counts.computeIfAbsent(rs.getObject("system_id", UUID.class), ignored -> new TreeMap<>()).put(rs.getString("state"), rs.getLong("quantity"));
                    return Boolean.TRUE;
                }, ids);
        var recent = new HashMap<UUID, RecentCycle>();
        jdbc.query("""
                WITH page_cycles AS (
                    SELECT cycle_id,system_id,cycle_number,state,version,created_at,updated_at FROM cycle WHERE system_id IN (%s)
                ), event_times AS (
                    SELECT e.cycle_id,MAX(e.recorded_at) AS last_event_at FROM cycle_event e
                    JOIN page_cycles c ON c.cycle_id=e.cycle_id GROUP BY e.cycle_id
                ), activity AS (
                    SELECT c.*,CASE WHEN e.last_event_at IS NULL OR e.last_event_at<COALESCE(c.updated_at,c.created_at)
                        THEN COALESCE(c.updated_at,c.created_at) ELSE e.last_event_at END AS activity_at
                    FROM page_cycles c LEFT JOIN event_times e ON e.cycle_id=c.cycle_id
                ), ranked AS (
                    SELECT a.*,ROW_NUMBER() OVER(PARTITION BY system_id ORDER BY activity_at DESC,cycle_number DESC,cycle_id DESC) AS row_rank
                    FROM activity a
                ) SELECT * FROM ranked WHERE row_rank=1
                """.formatted(placeholders), (rs, row) -> {
                    recent.put(rs.getObject("system_id", UUID.class), new RecentCycle(rs.getObject("cycle_id", UUID.class), rs.getInt("cycle_number"),
                            rs.getString("state"), rs.getLong("version"), rs.getTimestamp("activity_at").toInstant()));
                    return Boolean.TRUE;
                }, ids);
        var items = page.stream().map(system -> {
            var states = counts.getOrDefault(system.systemId(), Map.of());
            var last = recent.get(system.systemId());
            return new SystemSummary(system.systemId(), system.name(), system.domain(), system.status(), system.createdAt(),
                    states.values().stream().mapToLong(Long::longValue).sum(), states, last == null ? null : last.lastActivityAt(), last);
        }).toList();
        var last = page.getLast();
        return new SystemPage(items, rows.size() > limit ? NavigationCursor.encode(context, last.createdAt(), last.systemId()) : null);
    }

    /** Lista ciclos del sistema, incluso inactivos o terminales; un sistema inexistente conserva 404. */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public CyclePage cycles(UUID systemId, int limit, String after, String state) {
        validateLimit(limit);
        state = filter(state, 50);
        if (state != null) {
            try { CycleState.valueOf(state); }
            catch (IllegalArgumentException invalid) { throw new LifecycleException(BAD_REQUEST, "INVALID_STATE", "state debe identificar un estado oficial F01"); }
        }
        lifecycle.getSystem(systemId);
        String context = NavigationCursor.context("cycles", systemId.toString(), state);
        var anchor = NavigationCursor.decode(after, context);
        if (anchor != null) validateAnchor("SELECT created_at FROM cycle WHERE cycle_id=? AND system_id=?", anchor, anchor.id(), systemId);
        var conditions = new StringBuilder(" WHERE c.system_id=?");
        var parameters = new ArrayList<Object>(); parameters.add(systemId);
        if (state != null) { conditions.append(" AND c.state=?"); parameters.add(state); }
        appendPosition(conditions, parameters, "c.cycle_id", anchor);
        parameters.add(limit + 1);
        var rows = jdbc.query("SELECT c.* FROM cycle c" + conditions + " ORDER BY c.created_at DESC,c.cycle_id DESC LIMIT ?", lifecycle::cycle, parameters.toArray());
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        var last = page.isEmpty() ? null : page.getLast();
        return new CyclePage(page, rows.size() > limit ? NavigationCursor.encode(context, last.createdAt(), last.cycleId()) : null);
    }

    /** Confirma que la posicion pertenece a un recurso persistido del ambito, sin exigir su filtro mutable actual. */
    private void validateAnchor(String sql, NavigationCursor.Position anchor, Object... parameters) {
        var rows = jdbc.query(sql, (rs, row) -> rs.getTimestamp(1).toInstant(), parameters);
        if (rows.size() != 1 || !rows.getFirst().equals(anchor.createdAt())) throw NavigationCursor.invalid();
    }

    /** Construye una comparacion descendente por fecha/UUID sin recibir nombres SQL del cliente. */
    private static void appendPosition(StringBuilder sql, List<Object> parameters, String idColumn, NavigationCursor.Position anchor) {
        if (anchor == null) return;
        String alias = idColumn.substring(0, idColumn.indexOf('.'));
        sql.append(" AND (").append(alias).append(".created_at<? OR (").append(alias).append(".created_at=? AND ").append(idColumn).append("<?))");
        parameters.add(Timestamp.from(anchor.createdAt())); parameters.add(Timestamp.from(anchor.createdAt())); parameters.add(anchor.id());
    }

    /** Acota cada pagina antes de sumar la fila usada para detectar continuacion. */
    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new LifecycleException(BAD_REQUEST, "INVALID_LIMIT", "limit debe estar entre 1 y 100");
    }

    /** Normaliza espacios externos; rechaza controles y longitudes invalidas sin reinterpretar metadatos como permisos. */
    private static String filter(String value, int maximum) {
        if (value == null) return null;
        if (value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw new LifecycleException(BAD_REQUEST, "INVALID_FILTER", "El filtro contiene una longitud o caracteres no admitidos");
        return value.isBlank() ? null : value.trim();
    }
}
