package com.miec.orchestration;

import static com.miec.orchestration.InterventionCommands.*;

/** Escenarios de preparación compartidos; todos utilizan los servicios reales del MVP. */
final class InterventionTestSupport {
    /** Identidades y contexto necesarios para proponer un plan. */
    record Prepared(LifecycleViews.CycleView cycle,java.util.UUID objectiveId) { }

    /** Ejecuta las etapas iniciales sin insertar fixtures manuales en tablas de dominio. */
    static Prepared prepare(LifecycleService cycles,InterventionWorkflow workflow) {
        var system=cycles.createSystem(new LifecycleCommands.CreateSystem("intervención",null,null,null));
        var cycle=cycles.createCycle(system.systemId(),new LifecycleCommands.CreateCycle(null),"author");
        cycles.startCycle(cycle.cycleId(),new LifecycleCommands.StartCycle(0L,"inicio"),"author");
        var objective=workflow.initialize(cycle.cycleId(),new Initialize(1L,"Reducir fallos","Prueba sin errores","inicializar"),"author");
        var evidence=workflow.observe(cycle.cycleId(),new Observe(2L,"Fallo observado",null,"observar"),"author");
        var diagnosis=workflow.diagnose(cycle.cycleId(),new Diagnose(3L,evidence.resourceId(),"Ajustar proceso","diagnosticar"),"author");
        return new Prepared(diagnosis.cycle(),objective.resourceId());
    }

    /** Construye una propuesta de una o varias acciones del catálogo inicial. */
    static ProposePlan proposal(Prepared prepared,int actions) {
        return new ProposePlan(prepared.cycle().version(),prepared.objectiveId(),"Prueba satisfactoria","Restaurar configuración",
                java.util.stream.IntStream.range(0,actions).mapToObj(i->new Action("PROCESS_EXECUTION","Acción "+i)).toList());
    }
}
