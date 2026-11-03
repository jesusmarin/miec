package com.miec.orchestration;

import com.miec.action.PlannedActionJsonV01;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;
import static com.miec.orchestration.InterventionCommands.*;
import static org.junit.jupiter.api.Assertions.*;

/** Propuestas con contexto, acciones reales del contrato v01 y rollback del agregado. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:intervention_plan;DB_CLOSE_DELAY=-1")
class InterventionPlanTest {
    @Autowired private LifecycleService cycles;
    @Autowired private InterventionWorkflow workflow;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlannedActionJsonV01 parser;

    /** Las identidades son asignadas por servidor y el documento lo interpreta el núcleo IR-009. */
    @Test void shouldProposeImmutableVersionedPlan() {
        var prepared=InterventionTestSupport.prepare(cycles,workflow);
        var plan=workflow.propose(prepared.cycle().cycleId(),InterventionTestSupport.proposal(prepared,2),"author");
        assertEquals(plan,workflow.getPlan(plan.planId()));
        assertEquals(2,plan.actions().size());
        assertNotEquals(plan.actions().get(0).actionId(),plan.actions().get(1).actionId());
        assertEquals(64,plan.fingerprint().length());
        assertEquals("PROPOSED",plan.status());
        assertEquals(0,plan.version());
        String json=jdbc.queryForObject("SELECT actions FROM intervention_plan WHERE plan_id=?",String.class,plan.planId());
        assertEquals(2,parser.parse(plan.planId(),plan.cycleId(),json).actions().size());
        var current=cycles.getCycle(plan.cycleId());
        assertEquals("E04_PLANIFICANDO",current.state());
        var duplicate=new ProposePlan(current.version(),prepared.objectiveId(),"resultado","revertir",List.of(new Action("PROCESS_EXECUTION","a")));
        assertEquals("ACTIVE_PLAN_EXISTS",assertThrows(LifecycleException.class,()->workflow.propose(plan.cycleId(),duplicate,"author")).code());
    }

    /** Objetivo ajeno o un tipo que podría interpretarse como ejecución arbitraria no se admiten. */
    @Test void shouldRejectForeignObjectiveAndUnsupportedAction() {
        var prepared=InterventionTestSupport.prepare(cycles,workflow);
        var invalid=new ProposePlan(4L,UUID.randomUUID(),"resultado","revertir",List.of(new Action("PROCESS_EXECUTION","a")));
        assertThrows(LifecycleException.class,()->workflow.propose(prepared.cycle().cycleId(),invalid,"author"));
        var unsupported=new ProposePlan(4L,prepared.objectiveId(),"resultado","revertir",List.of(new Action("SHELL_COMMAND","a")));
        assertThrows(LifecycleException.class,()->workflow.propose(prepared.cycle().cycleId(),unsupported,"author"));
        assertEquals(prepared.cycle(),cycles.getCycle(prepared.cycle().cycleId()));
    }

    /** Fallar al auditar revierte plan y versión del ciclo. */
    @Test void shouldRollbackPlanWhenAuditFails() {
        var prepared=InterventionTestSupport.prepare(cycles,workflow); String actor=UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE cycle_event ADD CONSTRAINT mvp02_plan_failure CHECK(actor_id <> '"+actor+"')");
        try {
            assertThrows(DataIntegrityViolationException.class,()->workflow.propose(prepared.cycle().cycleId(),InterventionTestSupport.proposal(prepared,1),actor));
        } finally { jdbc.execute("ALTER TABLE cycle_event DROP CONSTRAINT mvp02_plan_failure"); }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM intervention_plan WHERE cycle_id=?",Integer.class,prepared.cycle().cycleId()));
        assertEquals(prepared.cycle(),cycles.getCycle(prepared.cycle().cycleId()));
    }
}
