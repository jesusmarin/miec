package com.miec.orchestration;

import com.miec.execution.ExecutableActions;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Fixtures medibles preparados mediante servicios reales, sin cambiar estados por SQL. */
final class PostMeasurementTestSupport {
    private static final ThreadLocal<java.util.function.Supplier<Instant>> CLOCK =
            ThreadLocal.withInitial(() -> Instant::now);
    /** Alinea exclusivamente el cliente de laboratorio con el reloj del destinatario de sus fechas. */
    static void observationClock(java.util.function.Supplier<Instant> clock) { CLOCK.set(clock); }
    /** Restaura el reloj del fixture sin afectar otros hilos o servicios productivos. */
    static void resetObservationClock() { CLOCK.remove(); }
    /** Contenedor de utilidades exclusivas de test. */
    private PostMeasurementTestSupport() { }
    /** Identidades confiables simuladas para probar fachadas sin cabeceras de cliente. */
    static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(role.equals("AUTHOR")?"author":"approver",null,
                List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    /** IDs y fechas de un caso independiente de ejecución confirmada. */
    record Fixture(UUID cycleId,UUID planId,UUID runId,UUID indicatorId,UUID baselineId,Instant baselineAt) { }
    /** Ejecuta el recorrido medible completo hasta E08 usando adaptador de pruebas instalado. */
    static Fixture ready(LifecycleService cycles,MeasurementWorkflow measures,InterventionWorkflow intervention,PlanExecutionApplicationService execution) {
        return executed(cycles,measures,intervention,execution,true);
    }
    /** Permite detener el fixture antes de finalizar para probar estados E07/E13 reales. */
    static Fixture executed(LifecycleService cycles,MeasurementWorkflow measures,InterventionWorkflow intervention,PlanExecutionApplicationService execution,boolean finish) {
        authenticate("AUTHOR");var system=cycles.createSystem(new LifecycleCommands.CreateSystem("post",null,null,null));
        var cycle=cycles.createCycle(system.systemId(),new LifecycleCommands.CreateCycle(null),"author");
        cycles.startCycle(cycle.cycleId(),new LifecycleCommands.StartCycle(0L,"iniciar"),"author");
        var objective=measures.initialize(cycle.cycleId(),new MeasurementContracts.Initialize(1L,"latencia","menor a 100ms","criterio",
                List.of(new MeasurementContracts.Criterion("latencia","ms",MeasurementContracts.Comparison.LTE,new BigDecimal("100")))),"author");
        UUID indicator=measures.context(cycle.cycleId()).objectives().getFirst().criteria().getFirst().indicatorId();var before=now().minusSeconds(60);
        var baseline=measures.measure(cycle.cycleId(),new MeasurementContracts.Measure(2L,indicator,new BigDecimal("150"),"ms","laboratorio",before,"base"),"author");
        var closed=measures.complete(cycle.cycleId(),new MeasurementContracts.Complete(3L,"cerrar"),"author");
        var diagnosed=intervention.diagnose(cycle.cycleId(),new InterventionCommands.Diagnose(closed.cycle().version(),closed.resourceId(),"ajustar","diagnóstico"),"author");
        var action=new InterventionCommands.Action("PROCESS_EXECUTION","cambio",new ExecutableActions.Spec("test-recipient","1","test-destination","application/json","{}"));
        var plan=intervention.propose(cycle.cycleId(),new InterventionCommands.ProposePlan(diagnosed.cycle().version(),objective.resourceId(),"reducir","revisión",List.of(action)),"author");
        var request=intervention.requestAuthorization(plan.planId(),new InterventionCommands.RequestAuthorization(plan.version(),plan.actions().getFirst().actionId(),600L),"author");
        intervention.decide(request.authorizationId(),new InterventionCommands.Decide(0L,"aprobar"),true,"approver");plan=intervention.getPlan(plan.planId());
        var run=execution.start(cycle.cycleId(),plan.planId(),new PlanExecutionContracts.Start(cycles.getCycle(cycle.cycleId()).version(),plan.version(),"ejecutar"));
        if(finish) {
            authenticate("APPROVER");execution.finish(plan.planId(),new PlanExecutionContracts.Finish(run.version(),cycles.getCycle(cycle.cycleId()).version(),"confirmar"));
        }
        authenticate("AUTHOR");return new Fixture(cycle.cycleId(),plan.planId(),run.runId(),indicator,baseline.resourceId(),before);
    }
    /** Fecha de cliente representable exactamente por TIMESTAMP(6). */
    static Instant now() { return CLOCK.get().get().truncatedTo(ChronoUnit.MICROS); }
    /** Comando posterior reutiliza el contrato Measure sin reinterpretar unidades. */
    static MeasurementContracts.PostMeasure command(Fixture fixture,long version) {
        return new MeasurementContracts.PostMeasure(fixture.runId(),new MeasurementContracts.Measure(version,fixture.indicatorId(),new BigDecimal("90.12345"),
                "ms","informe de laboratorio",now(),"resultado posterior"),"procedimiento p95 v1");
    }
}
