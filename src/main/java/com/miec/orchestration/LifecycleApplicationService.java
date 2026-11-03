package com.miec.orchestration;

import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.miec.orchestration.LifecycleCommands.*;
import static com.miec.orchestration.LifecycleViews.*;

/** Fachada autenticada del MVP-01; los servicios internos conservan su contrato transaccional. */
@Service
public class LifecycleApplicationService {
    private final LifecycleService lifecycle;
    private final InterventionIdentity identity;

    /** Reutiliza persistencia y resolución de identidad sin envolver efectos externos. */
    LifecycleApplicationService(LifecycleService lifecycle, InterventionIdentity identity) {
        this.lifecycle = lifecycle;
        this.identity = identity;
    }

    /** Registra un sistema solo con rol AUTHOR; ownerReference es metadato, no una autorización. */
    public SystemView createSystem(CreateSystem command) {
        identity.author();
        return lifecycle.createSystem(command);
    }

    /** Permite consultar sistemas a autores y aprobadores autenticados. */
    public SystemView getSystem(UUID systemId) {
        identity.reader();
        return lifecycle.getSystem(systemId);
    }

    /** Crea el ciclo atribuyendo su evento exclusivamente a la identidad del servidor. */
    public CycleView createCycle(UUID systemId, CreateCycle command) {
        return lifecycle.createCycle(systemId, command, identity.author());
    }

    /** Devuelve estado y versión después de comprobar el rol lector. */
    public CycleView getCycle(UUID cycleId) {
        identity.reader();
        return lifecycle.getCycle(cycleId);
    }

    /** Inicia con autor autenticado; ninguna cabecera de actor modifica la auditoría. */
    public CycleView startCycle(UUID cycleId, StartCycle command) {
        return lifecycle.startCycle(cycleId, command, identity.author());
    }

    /** Consulta historia paginada sin alterar sus identidades ni versiones. */
    public HistoryPage history(UUID cycleId, int limit, UUID after) {
        identity.reader();
        return lifecycle.history(cycleId, limit, after);
    }
}
