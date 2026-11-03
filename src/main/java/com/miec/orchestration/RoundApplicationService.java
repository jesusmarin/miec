package com.miec.orchestration;

import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.miec.orchestration.RoundContracts.*;

/** Fachada autenticada; no recibe identidades ni roles desde el cliente. */
@Service
public class RoundApplicationService {
    private final RoundWorkflow workflow;
    private final InterventionIdentity identity;
    /** Conserva los controles de rol también fuera de HTTP. */
    RoundApplicationService(RoundWorkflow workflow,InterventionIdentity identity){this.workflow=workflow;this.identity=identity;}
    /** Consulta historia a ambos roles instalados. */
    public Context get(UUID cycle){identity.reader();return workflow.view(cycle);}
    /** AUTHOR registra la ronda inicial de forma explícita. */
    public Context register(UUID cycle,RegisterInitial command){return workflow.register(cycle,command,identity.author());}
    /** APPROVER concluye sin conceder permisos de ejecución. */
    public Context conclude(UUID cycle,UUID round,CompletePlan command){return workflow.conclude(cycle,round,command,identity.approver());}
}
