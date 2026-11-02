package com.miec.experiment.reservation;

import com.miec.action.*;
import com.miec.integration.contract.IdempotentDestinationContract;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Reutiliza sin cambios las mismas pruebas de contrato de PetClinic sobre el dominio reservas. */
class ReservationContractTest extends IdempotentDestinationContract {
    /** Prepara archivos retenidos bajo target; no elimina directorios ni utiliza datos del usuario. */
    static String database() throws Exception {
        var directory = Files.createTempDirectory(Files.createDirectories(Path.of("target", "reservation-contract")), "run-").toAbsolutePath();
        return "jdbc:h2:file:" + directory.resolve("ledger").toString().replace('\\', '/') + ";WRITE_DELAY=0";
    }

    /** Adapta el conteo de efectos del segundo dominio al arnés común. */
    @Override protected Target openTarget() throws Exception {
        var destination = new ReservationDestination(database());
        return new Target() {
            /** Solicitud de reserva independiente del dominio PetClinic. */
            public ExecutionRequest request() { return destination.request(); }
            /** Implementa el mismo puerto de ejecución, sin modificar el coordinador. */
            public IdempotentActionExecutor executor(ExecutionRequest request) { return destination.executor(request); }
            /** Contrasta filas del ledger con el descuento real, para detectar efectos sin resultado. */
            public int effects() {
                int count = destination.reservations();
                assertEquals(count * 3, 100 - destination.remaining());
                return count;
            }
            /** Las conexiones JDBC se cierran al terminar cada operación. */
            public void close() { }
        };
    }

    /** Un fallo posterior al efecto revierte tanto el inventario como el ledger; la clave queda reutilizable. */
    @Test void shouldRollbackEffectAndResultAtomically() throws Exception {
        var destination = new ReservationDestination(database());
        var request = destination.request();
        var context = context(request, UUID.randomUUID());
        destination.failAfterEffect();
        assertThrows(IllegalStateException.class, () -> destination.executor(request).execute(context));
        assertEquals(100, destination.remaining());
        assertEquals(0, destination.reservations());
        assertEquals(IdempotentActionExecutor.Status.NOT_FOUND, destination.executor(request).lookup(context).status());
        destination.executor(request).execute(context);
        assertEquals(97, destination.remaining());
        assertEquals(1, destination.reservations());
    }

    /** Reconstruir el destinatario desde su archivo conserva clave, efecto y referencia. */
    @Test void shouldReopenLedgerWithoutRepeatingEffect() throws Exception {
        String database = database();
        var first = new ReservationDestination(database);
        var request = first.request();
        var context = context(request, UUID.randomUUID());
        String reference = first.executor(request).execute(context);
        var reopened = new ReservationDestination(database);
        assertEquals(reference, reopened.executor(request).lookup(context).resultReference());
        assertEquals(reference, reopened.executor(request).execute(context));
        assertEquals(97, reopened.remaining());
        assertEquals(1, reopened.reservations());
    }
}
