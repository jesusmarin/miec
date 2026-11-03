package com.miec.orchestration;

import com.miec.decision.DecisionOutcome;
import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.miec.orchestration.CycleCompletionContracts.*;

/** Fachada de cierre con identidad del servidor, sin aceptar actores o permisos del cliente. */
@Service
public class CycleCompletionApplicationService {
    private final CycleCompletionWorkflow workflow;
    private final InterventionIdentity identity;
    /** Reutiliza control de roles y transacciones internas del ciclo. */
    CycleCompletionApplicationService(CycleCompletionWorkflow workflow,InterventionIdentity identity){this.workflow=workflow;this.identity=identity;}
    /** Permite consultar progreso e historia a ambos roles lectores. */
    public View get(UUID cycle){identity.reader();return workflow.view(cycle);}
    /** Publica una recomendación sin efectuar decisión, transición o autorización. */
    public DecisionOutcome recommendation(UUID evaluation){identity.reader();return workflow.preview(evaluation);}
    /** APPROVER decide; el workflow comprueba separación de identidades y admisibilidad. */
    public View decide(UUID cycle,Decide command){return workflow.decide(cycle,command,identity.approver());}
    /** AUTHOR registra aprendizaje y conserva la confianza original de la evaluación. */
    public View learn(UUID cycle,Learn command){return workflow.learn(cycle,command,identity.author());}
    /** APPROVER confirma cierre administrativo sin cambiar la clasificación o la calidad de la evidencia. */
    public View close(UUID cycle,Close command){return workflow.close(cycle,command,identity.approver());}
}
