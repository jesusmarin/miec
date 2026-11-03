package com.miec.orchestration;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.CONFLICT;

/** Actividad lógica separada del estado histórico APPROVED. */
@Component
class PlanActivityQuery {
    private final JdbcTemplate jdbc;
    private final RoundScope scope;
    /** Consulta marcadores sin acoplar IR-009 al modelo de rondas. */
    PlanActivityQuery(JdbcTemplate jdbc,RoundScope scope){this.jdbc=jdbc;this.scope=scope;}
    /** Excluye planes concluidos sin modificar su autorización histórica. */
    List<UUID> active(UUID cycle){return jdbc.query("""
        SELECT plan_id FROM intervention_plan p WHERE cycle_id=?
        AND EXISTS(SELECT 1 FROM cycle c WHERE c.cycle_id=p.cycle_id AND c.closed_at IS NULL AND c.state NOT IN ('E14_FINALIZADO','E15_ABORTADO'))
        AND status IN ('PROPOSED','AWAITING_AUTHORIZATION','APPROVED')
        AND NOT EXISTS(SELECT 1 FROM plan_completion c WHERE c.plan_id=p.plan_id) ORDER BY plan_id
        """,(rs,row)->rs.getObject(1,UUID.class),cycle);}
    /** Impide despachar un plan ya concluido; las consultas históricas siguen disponibles. */
    void requireOpen(UUID plan){
        var cycle=jdbc.queryForObject("SELECT cycle_id FROM intervention_plan WHERE plan_id=?",UUID.class,plan);
        scope.requirePlan(cycle,plan);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM plan_completion WHERE plan_id=?",Integer.class,plan)>0)
        throw fail(CONFLICT,"PLAN_CONCLUDED","El plan tiene conclusión operativa");}
    /** La conclusión no autoriza otra propuesta dentro de la misma ronda. */
    void requireProposalRound(UUID cycle){if(jdbc.queryForObject("SELECT COUNT(*) FROM cycle_round r JOIN round_plan_binding p ON p.round_id=r.round_id WHERE r.cycle_id=? AND r.round_state='ACTIVE'",Integer.class,cycle)>0)
        throw fail(CONFLICT,"NEW_ROUND_REQUIRED","La nueva planificación requiere una ronda sucesora aprobada");}
}
