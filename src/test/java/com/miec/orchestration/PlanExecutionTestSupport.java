package com.miec.orchestration;

import com.miec.execution.ExecutableActions;
import java.util.ArrayList;
import java.util.List;
import static com.miec.orchestration.InterventionCommands.*;

/** Prepara planes ejecutables mediante el flujo real, sin fabricar aprobaciones por SQL. */
final class PlanExecutionTestSupport {
    /** Contenedor de helpers no instanciable. */
    private PlanExecutionTestSupport() { }

    /** Crea, solicita y aprueba un plan de una o varias acciones para el adaptador exclusivo de test. */
    static InterventionViews.Plan approved(LifecycleService cycles,InterventionWorkflow workflow,int count,boolean executable) {
        var prepared=InterventionTestSupport.prepare(cycles,workflow);
        var actions=new ArrayList<Action>();
        for(int index=0;index<count;index++) actions.add(new Action("PROCESS_EXECUTION","Paso "+index,executable?
                new ExecutableActions.Spec("test-recipient","1","test-destination","application/json","{\"step\":"+index+"}"):null));
        var plan=workflow.propose(prepared.cycle().cycleId(),new ProposePlan(prepared.cycle().version(),prepared.objectiveId(),"resultado","restaurar",actions),"author");
        var requests=new ArrayList<InterventionViews.Authorization>();
        for(var action:plan.actions()) requests.add(workflow.requestAuthorization(plan.planId(),
                new RequestAuthorization(workflow.getPlan(plan.planId()).version(),action.actionId(),600L),"author"));
        for(var request:requests) workflow.decide(request.authorizationId(),new Decide(0L,"aprobar"),true,"approver");
        return workflow.getPlan(plan.planId());
    }
}
