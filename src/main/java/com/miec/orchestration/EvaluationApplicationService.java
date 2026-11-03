package com.miec.orchestration;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/** Fachada autenticada de consulta; la creación operativa permanece reservada para 06.4. */
@Service
public class EvaluationApplicationService {
    private final ClosedSetEvaluationStore store;
    private final InterventionIdentity identity;
    private final CycleCompletionWorkflow completion;
    /** Reutiliza permisos lectores y snapshots persistidos, sin revaluar al consultar. */
    EvaluationApplicationService(ClosedSetEvaluationStore store,InterventionIdentity identity,CycleCompletionWorkflow completion){this.store=store;this.identity=identity;this.completion=completion;}
    /** Recupera la conclusión original con cualquiera de los roles lectores configurados. */
    public EvaluationContracts.Saved get(UUID id){identity.reader();return store.get(id);}
    /** Enumera conclusiones del conjunto sin crear una evaluación si no existe. */
    public List<EvaluationContracts.Saved> forSet(UUID id){identity.reader();return store.forSet(id);}
    /** AUTHOR registra hechos con políticas del servidor; no selecciona una clasificación o suficiencia. */
    public CycleCompletionContracts.EvaluationCreated create(UUID cycle,UUID plan,CycleCompletionContracts.Evaluate command){return completion.evaluate(cycle,plan,command,identity.author());}
}
