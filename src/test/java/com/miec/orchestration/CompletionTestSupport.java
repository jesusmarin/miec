package com.miec.orchestration;

import java.util.List;
import java.util.UUID;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.PostMeasurementTestSupport.*;

/** Preparación de un ciclo E08 con muestras fijas mediante los servicios reales. */
final class CompletionTestSupport {
    /** Contenedor de fixtures exclusivo de pruebas. */
    private CompletionTestSupport() { }
    /** Contexto que conserva las versiones necesarias para admitir una evaluación. */
    record Ready(Fixture fixture,UUID setId,long version) { }
    /** Crea ejecución, medición y conjunto sin fabricar estados o permisos por SQL. */
    static Ready sealed(LifecycleService cycles,MeasurementWorkflow measures,InterventionWorkflow intervention,PlanExecutionApplicationService execution,PostMeasurementWorkflow posts) {
        var f=ready(cycles,measures,intervention,execution);var context=posts.record(f.cycleId(),f.planId(),command(f,cycles.getCycle(f.cycleId()).version()),"author");var post=context.measurements().getFirst();
        var closed=posts.seal(f.cycleId(),f.planId(),new SealPost(context.cycleVersion(),f.runId(),f.baselineAt().minusSeconds(1),f.baselineAt().plusSeconds(1),post.measuredAt(),now(),post.method(),
                List.of(new PairSelection(f.indicatorId(),f.baselineId(),post.measurementId())),"conjunto"),"approver");
        return new Ready(f,closed.set().setId(),closed.cycleVersion());
    }
}
