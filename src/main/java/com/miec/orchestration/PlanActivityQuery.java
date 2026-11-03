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
    /** Consulta marcadores sin acoplar IR-009 al modelo de rondas. */
    PlanActivityQuery(JdbcTemplate jdbc){this.jdbc=jdbc;}
    /** Excluye planes concluidos sin modificar su autorización histórica. */
    List<UUID> active(UUID cycle){return jdbc.query("""
        SELECT plan_id FROM intervention_plan p WHERE cycle_id=?
        AND status IN ('PROPOSED','AWAITING_AUTHORIZATION','APPROVED')
        AND NOT EXISTS(SELECT 1 FROM plan_completion c WHERE c.plan_id=p.plan_id) ORDER BY plan_id
        """,(rs,row)->rs.getObject(1,UUID.class),cycle);}
    /** Impide despachar un plan ya concluido; las consultas históricas siguen disponibles. */
    void requireOpen(UUID plan){if(jdbc.queryForObject("SELECT COUNT(*) FROM plan_completion WHERE plan_id=?",Integer.class,plan)>0)
        throw fail(CONFLICT,"PLAN_CONCLUDED","El plan tiene conclusión operativa");}
    /** La conclusión no autoriza otra propuesta dentro de la misma ronda. */
    void requireProposalRound(UUID cycle){if(jdbc.queryForObject("SELECT COUNT(*) FROM cycle_round WHERE cycle_id=? AND round_state='ACTIVE'",Integer.class,cycle)>0)
        throw fail(CONFLICT,"NEW_ROUND_REQUIRED","La nueva planificación requiere continuidad de ronda aún no habilitada");}
}
