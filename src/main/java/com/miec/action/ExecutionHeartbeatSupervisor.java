package com.miec.action;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.*;

/** Supervisa actividad; una renovación fallida solicita interrupción cooperativa. */
@Component
public class ExecutionHeartbeatSupervisor implements AutoCloseable {
    private final ExecutionActivityService activity;
    private final ScheduledThreadPoolExecutor scheduler;

    /** Crea hilos daemon y elimina inmediatamente las tareas canceladas de la cola. */
    public ExecutionHeartbeatSupervisor(ExecutionActivityService activity) {
        this.activity = Objects.requireNonNull(activity);
        this.scheduler = new ScheduledThreadPoolExecutor(2, task -> {
            Thread thread = new Thread(task, "miec-execution-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
    }

    /** Inicia renovaciones del propietario mientras el hilo llamador ejecuta el efecto. */
    public Supervision supervise(ExecutionActivityService.Ownership owner) {
        var supervision = new Supervision(Objects.requireNonNull(owner), Thread.currentThread());
        long delay = activity.heartbeatInterval().toMillis();
        supervision.future = scheduler.scheduleWithFixedDelay(supervision::renew, delay, delay, TimeUnit.MILLISECONDS);
        return supervision;
    }

    /** Cancela tareas al destruir el contexto; nunca transfiere intentos. */
    @Override @PreDestroy
    public void close() { scheduler.shutdownNow(); }

    /** Recurso de una ejecución; serializa cancelación y heartbeat para evitar interrupciones tardías. */
    public final class Supervision implements AutoCloseable {
        private final ExecutionActivityService.Ownership owner;
        private final Thread worker;
        private ScheduledFuture<?> future;
        private boolean closed;
        private RuntimeException failure;

        /** Conserva las credenciales de esta adquisición y el hilo cooperativo. */
        private Supervision(ExecutionActivityService.Ownership owner, Thread worker) {
            this.owner = owner;
            this.worker = worker;
        }

        /** Renueva bajo exclusión con close; el primer fallo detiene futuras renovaciones. */
        private synchronized void renew() {
            if (closed || failure != null) return;
            try { activity.heartbeat(owner); }
            catch (RuntimeException error) {
                failure = error;
                worker.interrupt();
            }
        }

        /** Propaga la pérdida de supervisión antes de declarar éxito. */
        public synchronized void checkHealthy() {
            if (failure != null) throw failure;
        }

        /** Conserva el fallo de renovación cuando el ejecutor responde a la interrupción con otro error. */
        public synchronized void attachFailure(Throwable primary) {
            if (failure != null && failure != primary) primary.addSuppressed(failure);
        }

        /** Detiene renovaciones y espera cualquier renovación en curso mediante el mismo monitor. */
        @Override
        public synchronized void close() {
            closed = true;
            if (future != null) future.cancel(false);
        }
    }
}
