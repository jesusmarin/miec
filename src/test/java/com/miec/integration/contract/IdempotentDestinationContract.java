package com.miec.integration.contract;

import com.miec.action.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pruebas heredables de cumplimiento; el arnés observa efectos reales del destinatario. */
public abstract class IdempotentDestinationContract {
    /** Recursos aislados y fábrica de adaptadores; effects se consulta al final de cada escenario. */
    protected interface Target extends AutoCloseable {
        /** Solicitud válida del dominio del destinatario. */
        ExecutionRequest request();
        /** Crea un adaptador para enviar exactamente el snapshot recibido. */
        IdempotentActionExecutor executor(ExecutionRequest request);
        /** Cuenta efectos reales; puede detener el proceso propio para inspeccionar el archivo. */
        int effects() throws Exception;
        /** Libera exclusivamente los recursos del escenario. */
        @Override void close() throws Exception;
    }

    /** Prepara un destino sin efectos, sin depender de entidades de un dominio concreto. */
    protected abstract Target openTarget() throws Exception;

    /** Crea dos contextos que pueden conservar clave e identidad lógica entre solicitudes. */
    protected static IdempotentActionExecutor.Context context(ExecutionRequest request, UUID key) {
        return new IdempotentActionExecutor.Context(new ExecutionOperationRegistry.Operation(key, key, key,
                request.destinationId(), request.fingerprint(), request),
                new ExecutionActivityService.Ownership(UUID.randomUUID(), "contract-worker", 1), request);
    }

    /** Una consulta de una clave nueva no ejecuta el efecto ni fabrica una referencia. */
    @Test public void shouldLookupWithoutProducingEffect() throws Exception {
        try (var target = openTarget()) {
            var executor = target.executor(target.request());
            var result = executor.lookup(context(target.request(), UUID.randomUUID()));
            assertEquals(IdempotentActionExecutor.Status.NOT_FOUND, result.status());
            assertNull(result.resultReference());
            assertEquals(0, target.effects());
        }
    }

    /** Repetir la clave, incluso desde otra adquisición, conserva referencia y un solo efecto. */
    @Test public void shouldReplaySameKeyAndContent() throws Exception {
        try (var target = openTarget()) {
            var request = target.request();
            var executor = target.executor(request);
            UUID key = UUID.randomUUID();
            String reference = executor.execute(context(request, key));
            assertNotNull(reference);
            assertFalse(reference.isBlank());
            assertEquals(reference, executor.execute(context(request, key)));
            var found = executor.lookup(context(request, key));
            assertEquals(IdempotentActionExecutor.Status.APPLIED, found.status());
            assertEquals(reference, found.resultReference());
            assertEquals(1, target.effects());
        }
    }

    /** Una variación de bytes con la misma clave se rechaza y conserva el resultado original. */
    @Test public void shouldRejectChangedContentForExistingKey() throws Exception {
        try (var target = openTarget()) {
            var request = target.request();
            UUID key = UUID.randomUUID();
            var executor = target.executor(request);
            String reference = executor.execute(context(request, key));
            var changed = new ExecutionRequest(request.destinationId(), request.mediaType(), request.body() + " ");
            assertThrows(RuntimeException.class, () -> target.executor(changed).execute(context(changed, key)));
            assertEquals(reference, executor.execute(context(request, key)));
            assertEquals(1, target.effects());
        }
    }

    /** Ocho envíos simultáneos regresan la misma referencia y producen un único efecto real. */
    @Test public void shouldDeduplicateConcurrentRequests() throws Exception {
        try (var target = openTarget(); var workers = Executors.newFixedThreadPool(8)) {
            var request = target.request();
            var executor = target.executor(request);
            UUID key = UUID.randomUUID();
            var ready = new CyclicBarrier(8);
            Callable<String> send = () -> { ready.await(10, TimeUnit.SECONDS); return executor.execute(context(request, key)); };
            var results = new java.util.ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++) results.add(workers.submit(send));
            String reference = results.getFirst().get(20, TimeUnit.SECONDS);
            assertNotNull(reference);
            for (var result : results) assertEquals(reference, result.get(20, TimeUnit.SECONDS));
            assertEquals(1, target.effects());
        }
    }
}
