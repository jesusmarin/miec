package com.miec.orchestration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;
import static com.miec.orchestration.MeasurementContracts.*;

/** Lecturas del contexto medible sin dependencias hacia comandos ni planes. */
@Service
class MeasurementQueries {
    private final InterventionSupport store;
    /** Recibe consultas y reloj compartidos del ciclo. */
    MeasurementQueries(InterventionSupport store) { this.store=store; }
    /** Consulta coherente sin requerir plan ni modificar el ciclo. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Context context(UUID cycleId) {
        store.lifecycle.getCycle(cycleId);
        var objectives=store.jdbc.query("""
                SELECT o.* FROM objective o JOIN cycle_objective co ON co.objective_id=o.objective_id
                WHERE co.cycle_id=? ORDER BY o.objective_id
                """,(rs,row)->new Objective(rs.getObject("objective_id",UUID.class),rs.getString("name"),rs.getString("success_criteria"),
                indicators(rs.getObject("objective_id",UUID.class))),cycleId);
        var baselines=store.jdbc.query("SELECT * FROM baseline WHERE cycle_id=? ORDER BY created_at,baseline_id",(rs,row)->new Baseline(
                rs.getObject("baseline_id",UUID.class),rs.getObject("indicator_id",UUID.class),rs.getBigDecimal("observed_value"),
                rs.getString("unit"),rs.getString("source_reference"),rs.getTimestamp("measured_at").toInstant(),rs.getTimestamp("created_at").toInstant(),
                rs.getString("recorded_by"),rs.getString("quality_status"),rs.getObject("evidence_id",UUID.class)),cycleId);
        return new Context(cycleId,objectives.stream().anyMatch(o->!o.criteria().isEmpty())?"MEASURABLE":"LEGACY_UNSTRUCTURED",List.copyOf(objectives),List.copyOf(baselines));
    }
    /** Recupera únicamente los criterios estructurados sin reinterpretar indicadores históricos. */
    private List<Indicator> indicators(UUID objective) {
        return store.jdbc.query("SELECT * FROM indicator WHERE objective_id=? AND comparison_operator IS NOT NULL ORDER BY indicator_id",
                (rs,row)->new Indicator(rs.getObject("indicator_id",UUID.class),rs.getString("name"),rs.getString("unit"),
                        Comparison.valueOf(rs.getString("comparison_operator")),rs.getBigDecimal("acceptance_threshold")),objective);
    }
}
