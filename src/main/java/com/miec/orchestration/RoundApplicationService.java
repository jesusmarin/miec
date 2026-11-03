package com.miec.orchestration;

import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.miec.orchestration.RoundContracts.*;

/** Fachada autenticada; no recibe identidades ni roles desde el cliente. */
@Service
public class RoundApplicationService {
    private final RoundWorkflow workflow;
    private final RoundContinuationWorkflow continuity;
    private final MeasurementQueries measurements;
    private final CycleCompletionWorkflow completion;
    private final InterventionIdentity identity;
    /** Conserva los controles de rol también fuera de HTTP. */
    RoundApplicationService(RoundWorkflow workflow,InterventionIdentity identity,RoundContinuationWorkflow continuity,MeasurementQueries measurements,CycleCompletionWorkflow completion){this.workflow=workflow;this.identity=identity;this.continuity=continuity;this.measurements=measurements;this.completion=completion;}
    /** Consulta historia a ambos roles instalados. */
    public Context get(UUID cycle){identity.reader();return workflow.view(cycle);}
    /** AUTHOR registra la ronda inicial de forma explícita. */
    public Context register(UUID cycle,RegisterInitial command){return workflow.register(cycle,command,identity.author());}
    /** APPROVER concluye sin conceder permisos de ejecución. */
    public Context conclude(UUID cycle,UUID round,CompletePlan command){return workflow.conclude(cycle,round,command,identity.approver());}
    /** AUTHOR conserva una solicitud de continuidad con hechos y limitaciones. */
    public Continuation request(UUID cycle,UUID round,RequestContinuation command){return continuity.request(cycle,round,command,identity.author());}
    /** APPROVER independiente aprueba una solicitud vigente y abre su sucesora. */
    public Context approve(UUID cycle,UUID request,ApproveContinuation command){return continuity.approve(cycle,request,command,identity.approver());}
    /** Consulta el recibo historico sin efectos laterales. */
    public Continuation request(UUID cycle,UUID request){identity.reader();return continuity.get(cycle,request);}
    /** Lee muestras exclusivamente de una ronda identificada. */
    public MeasurementContracts.Context measurements(UUID cycle,UUID round){identity.reader();return measurements.context(cycle,round);}
    /** Lee evaluacion, decision y aprendizaje de una ronda concreta. */
    public CycleCompletionContracts.View completion(UUID cycle,UUID round){identity.reader();return completion.forRound(cycle,round);}
}
