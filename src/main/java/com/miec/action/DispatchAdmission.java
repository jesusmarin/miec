package com.miec.action;

import java.util.function.Supplier;
import static com.miec.action.ExecutionDispatchRejectedException.Code.*;

/**
 * Frontera confiable de un único envío. El estado pertenece al coordinador, no al
 * adaptador; una excepción posterior a entrar en execute no puede fingir un rechazo previo.
 */
final class DispatchAdmission {
    private final ExecutionAttemptService attempts;
    private final AuthorizedActionExecutionService.ExecutionCommand command;
    private final ExecutionActivityService.Ownership owner;
    private final java.util.UUID operationKey;
    private boolean entered;
    private ExecutionDispatchRejectedException rejection;
    private Runnable health=()->{};

    /** Captura identidad inmutable del intento y de la operación antes de llamar al adaptador. */
    DispatchAdmission(ExecutionAttemptService attempts,AuthorizedActionExecutionService.ExecutionCommand command,
            ExecutionActivityService.Ownership owner,java.util.UUID operationKey) {
        this.attempts=attempts; this.command=command; this.owner=owner; this.operationKey=operationKey;
    }

    /** Ejecuta validación local y transaccional, cierra sus locks y solo entonces entra en el envío. */
    String send(ExecutionRequest request,Runnable localCheck,Supplier<String> effect) {
        if(entered||rejection!=null) throw new IllegalStateException("Una admisión no se reutiliza");
        try { localCheck.run(); }
        catch(RuntimeException denied) { throw reject(PRE_SEND_CONTEXT_REJECTED,denied); }
        checkHealth();
        try { attempts.validateBeforeSend(command,owner,operationKey,request); }
        catch(ExecutionDispatchRejectedException denied) { rejection=denied; throw denied; }
        catch(RuntimeException unavailable) { throw reject(ADMISSION_UNAVAILABLE,unavailable); }
        checkHealth();
        entered=true;
        return effect.get();
    }

    /** Enlaza el supervisor real del intento antes de ejecutar cualquier callback. */
    void supervise(Runnable health) { this.health=java.util.Objects.requireNonNull(health); }

    /** Un adaptador que ignore la interrupción no puede ocultar el fallo retenido por el supervisor. */
    private void checkHealth() {
        try { health.run(); }
        catch(RuntimeException failed) { throw reject(ATTEMPT_HEARTBEAT_FAILED,failed); }
        if(Thread.currentThread().isInterrupted()) throw reject(THREAD_INTERRUPTED,null);
    }

    /** Permite auditar la fase conocida sin confiar en el tipo de excepción lanzado por el adaptador. */
    boolean rejectedBeforeSend() { return rejection!=null&&!entered; }
    /** Rechazo auténtico del núcleo, si la frontera no llegó a admitir el envío. */
    ExecutionDispatchRejectedException rejection() { return rejection; }
    /** Construye un rechazo sin incorporar mensajes o valores externos al código público. */
    private ExecutionDispatchRejectedException reject(ExecutionDispatchRejectedException.Code code,Throwable cause) {
        rejection=new ExecutionDispatchRejectedException(code,owner.executionId(),cause); return rejection;
    }
}
