package com.miec.orchestration;

import com.miec.action.*;
import com.miec.execution.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.miec.orchestration.PlanExecutionContracts.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/**
 * Fachada de ejecución síncrona del MVP. No es transaccional: cada reserva/progreso
 * confirma por separado, y el destinatario se invoca únicamente a través de IR-009.
 * Un timeout del cliente no prueba que la ejecución se haya detenido.
 */
@Service
public class PlanExecutionApplicationService {
    private final InterventionIdentity identity;
    private final PlanExecutionStore store;
    private final ExecutableActionCatalog catalog;
    private final IdempotentActionExecutionService execution;
    private final ExecutionActivityService activity;
    private final ExecutionIncidentService incidents;
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(task->{
        var thread=new Thread(task,"miec-plan-coordination-heartbeat"); thread.setDaemon(true); return thread;
    });

    /** Reutiliza identidad, coordinación y el punto único de ejecución protegido de IR-009. */
    PlanExecutionApplicationService(InterventionIdentity identity,PlanExecutionStore store,ExecutableActionCatalog catalog,
            IdempotentActionExecutionService execution,ExecutionActivityService activity,ExecutionIncidentService incidents) {
        this.identity=identity; this.store=store; this.catalog=catalog; this.execution=execution; this.activity=activity;this.incidents=incidents;
    }

    /** Inicia una única coordinación por plan; rechaza una transacción exterior antes de reservar. */
    public Run start(UUID cycleId,UUID planId,Start command) {
        String actor=identity.author(); requireNoTransaction();
        return execute(store.reserve(cycleId,planId,command,actor));
    }

    /** Reanuda solo reservas vencidas que no alcanzaron un despacho incierto. */
    public Run resume(UUID planId,Control command) {
        String actor=identity.author(); requireNoTransaction();
        return execute(store.resume(planId,command,actor));
    }

    /** Consulta progreso y hechos técnicos sin exigir permiso EXECUTE_ACTION vigente. */
    public Run get(UUID planId) { identity.reader(); return store.view(planId); }

    /** Publica exclusivamente metadatos de adaptadores configurados, sin URLs ni claves. */
    public List<ExecutableActions.Descriptor> catalog() { identity.reader(); return catalog.descriptors(); }

    /**
     * Repara la proyección de coordinación usando hechos de IR-009 ya almacenados.
     * No es una autorización de reconciliación externa ni invoca al destinatario.
     */
    public Run recover(UUID planId,Control command) { return store.recover(planId,command,identity.approver()); }

    /** Confirma E08 únicamente con hechos locales completos, bajo identidad APPROVER y control de versiones. */
    public Run finish(UUID planId,Finish command) { return store.finish(planId,command,identity.approver()); }

    /** Ejecuta secuencialmente; un fallo registrado deja un recurso consultable y bloquea los pasos siguientes. */
    private Run execute(PlanExecutionStore.Claim claim) {
        try(var supervision=new Supervision(claim)) {
            while(true) {
                supervision.check();
                var dispatch=store.beginNext(claim);
                if(dispatch==null) return store.view(claim.planId());
                var current=store.view(claim.planId());
                var bound=catalog.resolve(dispatch.action().execution());
                var guarded=guard(claim,bound,supervision);
                var command=new AuthorizedActionExecutionService.ExecutionCommand(dispatch.action().actionId(),claim.planId(),current.cycleId(),
                        dispatch.authorizationId(),dispatch.action().actionType(),claim.actor());
                execution.execute(command,guarded);
                supervision.check();
                store.applied(claim,dispatch.action().actionId());
            }
        } catch(RuntimeException failure) {
            // Si se perdió propiedad, pause no puede mutar el run. La marca durable
            // y la proyección requieren revisión; nunca se roba o reenvía el despacho.
            RuntimeException pauseFailure=null;
            try { store.pause(claim); }
            catch(RuntimeException auditFailure) { failure.addSuppressed(auditFailure);pauseFailure=auditFailure; }
            var incident=incidents.captureCoordination(claim.runId(),claim.actor(),failure);
            var unavailable=findAuditFailure(failure);
            if(unavailable!=null) throw new ExecutionIncidentAuditException(unavailable.incidentId(),failure);
            if(pauseFailure!=null&&!(pauseFailure instanceof LifecycleException rejected&&"COORDINATOR_LOST".equals(rejected.code())))
                throw new ExecutionIncidentAuditException(incident,failure);
            return store.view(claim.planId());
        }
    }

    /** Encuentra auditoría fallida conservada en causas/suprimidas, sin confiar en mensajes ni perder correlación. */
    private static ExecutionIncidentAuditException findAuditFailure(Throwable failure) {
        var pending=new java.util.ArrayDeque<Throwable>();pending.add(failure);
        var visited=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        while(!pending.isEmpty()) {
            var current=pending.removeFirst();if(!visited.add(current)) continue;
            if(current instanceof ExecutionIncidentAuditException audit) return audit;
            if(current.getCause()!=null) pending.add(current.getCause());
            java.util.Collections.addAll(pending,current.getSuppressed());
        }
        return null;
    }

    /** Añade una comprobación de coordinación antes de cada IO del ejecutor sin sustituir la autorización de IR-009. */
    private IdempotentActionExecutor guard(PlanExecutionStore.Claim claim,IdempotentActionExecutor bound,Supervision supervision) {
        return new IdempotentActionExecutor() {
            /** Mantiene la solicitud exacta del adaptador capturado por el catálogo. */
            @Override public ExecutionRequest request() { return bound.request(); }
            /** Consulta únicamente mientras esta adquisición conserva propiedad y contexto. */
            @Override public Lookup lookup(Context context) { supervision.check(); store.assertOwner(claim); return bound.lookup(context); }
            /** Comprueba contexto local antes de la admisión final IR-009; no deja IO ni locks abiertos. */
            @Override public void beforeSend(Context context) { supervision.check(); store.assertOwner(claim); bound.beforeSend(context); }
            /** La frontera IR-009 ya revalidó permiso/propiedad después del hook y antes de entrar aquí. */
            @Override public String execute(Context context) { return bound.execute(context); }
        };
    }

    /** Supervisor propio de coordinación; los leases de intentos permanecen bajo el supervisor IR-009 existente. */
    private final class Supervision implements AutoCloseable {
        private final AtomicReference<RuntimeException> failure=new AtomicReference<>();
        private final ScheduledFuture<?> scheduled;
        /** Renueva solo esta adquisición; no transfiere identidad HTTP a un hilo de fondo. */
        Supervision(PlanExecutionStore.Claim claim) {
            long interval=activity.heartbeatInterval().toMillis();
            scheduled=heartbeats.scheduleWithFixedDelay(()-> {
                if(failure.get()!=null) return;
                try { store.heartbeat(claim); }
                catch(RuntimeException lost) { failure.compareAndSet(null,lost); }
            },interval,interval,TimeUnit.MILLISECONDS);
        }
        /** La salud fallida impide comenzar o avanzar acciones adicionales. */
        void check() {
            if(failure.get()!=null) throw fail(CONFLICT,"COORDINATOR_HEARTBEAT_FAILED","No se confirmó la vigencia del coordinador");
        }
        /** Detiene renovaciones; no cancela ni presume rollback de una llamada externa ya iniciada. */
        @Override public void close() { scheduled.cancel(false); }
    }

    /** Evita el patrón prohibido de envolver las transacciones independientes de IR-009. */
    private static void requireNoTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw fail(CONFLICT,"OUTER_TRANSACTION_NOT_ALLOWED","La ejecución requiere llamadas fuera de una transacción exterior");
    }

    /** Libera exclusivamente el supervisor creado por esta fachada al cerrar la aplicación. */
    @PreDestroy public void close() { heartbeats.shutdownNow(); }
}
