package com.miec.orchestration;

import com.miec.execution.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static com.miec.orchestration.InterventionCommands.*;
import static org.junit.jupiter.api.Assertions.*;

/** Definiciones ejecutables aprobadas, compatibilidad histórica y configuración que no puede sustituirse. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:executable_plans;DB_CLOSE_DELAY=-1")
class ExecutablePlanTest {
    @Autowired LifecycleService cycles;
    @Autowired InterventionWorkflow workflow;
    @Autowired ExecutableActionCatalog catalog;
    @Autowired ExecutionTestConfiguration.TestAdapter adapter;
    @Autowired JdbcTemplate jdbc;

    /** Solicitud exacta de prueba, sin instrucciones de shell ni endpoints del cliente. */
    private ExecutableActions.Spec spec(String body) { return new ExecutableActions.Spec("test-recipient","1","test-destination","application/json",body); }

    /** La definición se guarda antes de solicitar permisos y es visible al aprobador sin producir efectos. */
    @Test void shouldCaptureExecutableDefinitionInApprovedFingerprint() {
        var prepared=InterventionTestSupport.prepare(cycles,workflow);
        var plan=workflow.propose(prepared.cycle().cycleId(),new ProposePlan(prepared.cycle().version(),prepared.objectiveId(),"resultado","restaurar",
                List.of(new Action("PROCESS_EXECUTION","Descripción",spec("{\"value\":1}")))),"author");
        var definition=plan.actions().getFirst().execution();
        assertEquals("a".repeat(64),definition.bindingFingerprint()); assertEquals("{\"value\":1}",definition.body());
        assertEquals(0,adapter.effects.get());
        var request=workflow.requestAuthorization(plan.planId(),new RequestAuthorization(plan.version(),plan.actions().getFirst().actionId(),600L),"author");
        var changed=workflow.getPlan(plan.planId());
        String document=jdbc.queryForObject("SELECT actions FROM intervention_plan WHERE plan_id=?",String.class,plan.planId());
        jdbc.update("UPDATE intervention_plan SET actions=? FORMAT JSON WHERE plan_id=?",document.replace("test-destination","other-destination"),plan.planId());
        assertNotEquals(changed.fingerprint(),workflow.getPlan(plan.planId()).fingerprint());
        assertEquals("PLAN_CHANGED",assertThrows(LifecycleException.class,()->workflow.decide(request.authorizationId(),new Decide(0L,"aprobar"),true,"approver")).code());
    }

    /** Los planes antiguos no ganan una definición ejecutable por lectura ni por reconstrucción del catálogo. */
    @Test void shouldPreserveDescriptivePlansAndRejectUnknownAdapters() {
        var prepared=InterventionTestSupport.prepare(cycles,workflow);
        var plan=workflow.propose(prepared.cycle().cycleId(),InterventionTestSupport.proposal(prepared,1),"author");
        assertNull(plan.actions().getFirst().execution());
        assertThrows(IllegalArgumentException.class,()->catalog.resolve(null));
        assertThrows(IllegalArgumentException.class,()->catalog.capture(new ExecutableActions.Spec("missing","1","d","application/json","{}")));
        assertThrows(IllegalArgumentException.class,()->catalog.capture(spec("x".repeat(65537))));
    }

    /** Cambiar la ruta instalada invalida el enlace previo; reinstalarla conserva los bytes aprobados. */
    @Test void shouldRejectAdapterBindingChangedAfterApproval() {
        var definition=catalog.capture(spec("{ \"value\": 1 }"));
        adapter.binding="b".repeat(64);
        try { assertThrows(IllegalArgumentException.class,()->catalog.resolve(definition)); }
        finally { adapter.binding="a".repeat(64); }
        assertEquals("{ \"value\": 1 }",catalog.resolve(definition).request().body());
        assertThrows(IllegalArgumentException.class,()->new ExecutableActionCatalog(List.of(adapter,adapter)));
    }
}
