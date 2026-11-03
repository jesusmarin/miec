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
    private final RoundScope scope;
    /** Recibe consultas y reloj compartidos del ciclo. */
    MeasurementQueries(InterventionSupport store,RoundScope scope) { this.store=store;this.scope=scope; }
    /** Consulta coherente sin requerir plan ni modificar el ciclo. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Context context(UUID cycleId) {return context(cycleId,scope.current(cycleId));}
    /** Consulta una ronda concreta; null representa historia sin asignacion explicita. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Context context(UUID cycleId,UUID round) {
        if(round!=null)scope.requireRound(cycleId,round);
        store.lifecycle.getCycle(cycleId);
        var objectives=store.jdbc.query("""
                SELECT o.* FROM objective o JOIN cycle_objective co ON co.objective_id=o.objective_id
                WHERE co.cycle_id=? ORDER BY o.objective_id
                """,(rs,row)->new Objective(rs.getObject("objective_id",UUID.class),rs.getString("name"),rs.getString("success_criteria"),
                indicators(rs.getObject("objective_id",UUID.class))),cycleId);
        var baselines=store.jdbc.query("SELECT * FROM baseline b WHERE cycle_id=? "+(round==null?"":"AND EXISTS(SELECT 1 FROM round_baseline r WHERE r.baseline_id=b.baseline_id AND r.round_id=?) ")+"ORDER BY created_at,baseline_id",(rs,row)->new Baseline(
                rs.getObject("baseline_id",UUID.class),rs.getObject("indicator_id",UUID.class),rs.getBigDecimal("observed_value"),
                rs.getString("unit"),rs.getString("source_reference"),rs.getTimestamp("measured_at").toInstant(),rs.getTimestamp("created_at").toInstant(),
                rs.getString("recorded_by"),rs.getString("quality_status"),rs.getObject("evidence_id",UUID.class)),round==null?new Object[]{cycleId}:new Object[]{cycleId,round});
        return new Context(cycleId,objectives.stream().anyMatch(o->!o.criteria().isEmpty())?"MEASURABLE":"LEGACY_UNSTRUCTURED",List.copyOf(objectives),List.copyOf(baselines));
    }
    /** Recupera únicamente los criterios estructurados sin reinterpretar indicadores históricos. */
    private List<Indicator> indicators(UUID objective) {
        return store.jdbc.query("SELECT * FROM indicator WHERE objective_id=? AND comparison_operator IS NOT NULL ORDER BY indicator_id",
                (rs,row)->new Indicator(rs.getObject("indicator_id",UUID.class),rs.getString("name"),rs.getString("unit"),
                        Comparison.valueOf(rs.getString("comparison_operator")),rs.getBigDecimal("acceptance_threshold")),objective);
    }

    /** Resuelve el mismo criterio estructurado para línea base y resultado posterior. */
    Indicator indicator(UUID cycle,UUID indicator) {
        return context(cycle).objectives().stream().flatMap(o->o.criteria().stream()).filter(i->i.indicatorId().equals(indicator)).findFirst()
                .orElseThrow(()->InterventionSupport.fail(LifecycleException.Kind.BAD_REQUEST,"INVALID_INDICATOR","El indicador no pertenece al ciclo medible"));
    }

    /** No convierte unidades ni infiere equivalencias por nombre o magnitud. */
    static void requireUnit(Indicator indicator,String unit) {
        if(unit==null||!Objects.equals(indicator.unit(),unit.trim())) throw InterventionSupport.fail(LifecycleException.Kind.BAD_REQUEST,"UNIT_MISMATCH","La unidad debe coincidir con el indicador");
    }

    /** Lee resultados, revisiones y conjunto en un snapshot, también después de abandonar E08. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PostContext posts(UUID cycleId,UUID planId) {
        var cycle=store.lifecycle.getCycle(cycleId);
        var cycles=store.jdbc.query("SELECT cycle_id FROM intervention_plan WHERE plan_id=?",(rs,row)->rs.getObject(1,UUID.class),planId);
        if(cycles.isEmpty()) throw InterventionSupport.fail(LifecycleException.Kind.NOT_FOUND,"PLAN_NOT_FOUND","El plan no existe");
        if(!cycleId.equals(cycles.getFirst())) throw InterventionSupport.fail(LifecycleException.Kind.BAD_REQUEST,"PLAN_CYCLE_MISMATCH","El plan no pertenece al ciclo");
        var measurements=store.jdbc.query("SELECT * FROM post_measurement WHERE plan_id=? ORDER BY recorded_at,measurement_id",(rs,row)->new PostMeasurement(
                rs.getObject("measurement_id",UUID.class),rs.getObject("run_id",UUID.class),planId,cycleId,rs.getObject("indicator_id",UUID.class),
                rs.getBigDecimal("observed_value"),rs.getString("unit"),rs.getString("measurement_method"),rs.getString("source_reference"),
                rs.getTimestamp("measured_at").toInstant(),rs.getTimestamp("recorded_at").toInstant(),rs.getString("recorded_by"),rs.getObject("evidence_id",UUID.class),
                "UNVERIFIED",Comparison.valueOf(rs.getString("criterion_operator")),rs.getBigDecimal("criterion_target"),rs.getString("criterion_version")),planId);
        var reviews=store.jdbc.query("SELECT r.* FROM measurement_review r JOIN plan_execution p ON p.run_id=r.run_id WHERE p.plan_id=? ORDER BY reviewed_at,review_id",
                (rs,row)->new MeasurementReview(rs.getObject("review_id",UUID.class),rs.getObject("run_id",UUID.class),rs.getObject("evidence_id",UUID.class),
                        ReviewVerdict.valueOf(rs.getString("verdict")),rs.getString("reviewed_by"),rs.getString("measurement_method"),rs.getString("procedure_reference"),
                        rs.getString("limitations"),rs.getTimestamp("reviewed_at").toInstant()),planId);
        var sets=store.jdbc.query("SELECT * FROM measurement_set WHERE plan_id=?",(rs,row)-> {
            UUID id=rs.getObject("set_id",UUID.class);
            var pairs=store.jdbc.query("SELECT * FROM measurement_pair WHERE set_id=? ORDER BY indicator_id",(pair,index)->new MeasurementPair(
                    pair.getObject("indicator_id",UUID.class),pair.getObject("baseline_id",UUID.class),pair.getObject("measurement_id",UUID.class),
                    pair.getObject("baseline_review_id",UUID.class),pair.getObject("post_review_id",UUID.class)),id);
            return new MeasurementSet(id,rs.getObject("run_id",UUID.class),rs.getString("policy_version"),rs.getString("measurement_method"),
                    rs.getTimestamp("baseline_from").toInstant(),rs.getTimestamp("baseline_to").toInstant(),rs.getTimestamp("post_from").toInstant(),
                    rs.getTimestamp("post_to").toInstant(),rs.getString("sealed_by"),rs.getTimestamp("sealed_at").toInstant(),pairs);
        },planId);
        return new PostContext(cycleId,planId,cycle.version(),measurements,reviews,sets.isEmpty()?null:sets.getFirst());
    }
}
