package com.miec.experiment.reservation;

import com.miec.action.*;
import com.miec.action.integration.DestinationEvidenceSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Comprueba un segundo dominio con los mismos servicios productivos de ejecución y verificación. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:reservation_execution;DB_CLOSE_DELAY=-1")
@Import(ReservationExecutionIntegrationTest.Configuration.class)
class ReservationExecutionIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationDestination destination;
    @Autowired private IdempotentActionExecutionService execution;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private DestinationEvidenceVerificationService verification;
    @Autowired private ExecutionReconciliationService reconciliation;

    /** Instala únicamente en este test una fuente y un destinatario con persistencia propia. */
    @TestConfiguration(proxyBeanMethods=false)
    static class Configuration {
        /** Crea inventario y ledger en un archivo separado de la base de MIEC. */
        @Bean ReservationDestination reservationDestination() throws Exception {
            return new ReservationDestination(ReservationContractTest.database());
        }
        /** Traduce el ledger propio al contrato genérico de evidencia, sin registrar ejecutores por solicitud. */
        @Bean DestinationEvidenceSource reservationSource(ReservationDestination destination) {
            return new DestinationEvidenceSource() {
                /** Selecciona el destino configurado en el bootstrap. */
                public String destinationId() { return ReservationDestination.DESTINATION; }
                /** Consulta el resultado confirmado sin producir efectos. */
                public Observation lookup(IdempotentActionExecutor.Context context) {
                    var result = destination.executor(context.request()).lookup(context);
                    return new Observation(context.operation().key(), destinationId(), context.request().fingerprint(),
                            result.status(), result.resultReference());
                }
            };
        }
    }

    /** Una respuesta perdida se verifica y reconcilia sin cambiar el núcleo ni repetir el descuento. */
    @Test void shouldRecoverReservationUsingCommonServices() {
        var fixture = ExecutionRecoveryTestSupport.fixture(jdbc);
        var request = destination.request();
        var adapter = destination.executor(request);
        IdempotentActionExecutor lossy = new IdempotentActionExecutor() {
            /** Conserva el snapshot del adaptador de reservas. */
            public ExecutionRequest request() { return request; }
            /** Consulta normalmente el ledger durable. */
            public Lookup lookup(Context context) { return adapter.lookup(context); }
            /** Simula pérdida posterior al commit del segundo destinatario. */
            public String execute(Context context) {
                adapter.execute(context);
                throw new IllegalStateException("Respuesta perdida después de reservar");
            }
        };
        assertThrows(IllegalStateException.class, () -> execution.execute(fixture.command(), lossy));
        var attempt = attempts.findHistory(fixture.plan(), fixture.action()).getFirst();
        assertEquals(ExecutionRetryPolicy.ExecutionStatus.UNKNOWN, attempt.status());
        var proof = verification.verifyApplied(new DestinationEvidenceVerificationService.Command(UUID.randomUUID(),
                attempt.executionId(), fixture.reviewAuthorization(), "reviewer"));
        reconciliation.decide(new ExecutionReconciliationService.Command(attempt.executionId(), proof.id(),
                ExecutionReconciliationPolicy.Resolution.APPLIED, fixture.reviewAuthorization(), "reviewer", "Reserva verificada"));
        assertEquals(proof.reference(), execution.recoverResult(fixture.command()).orElseThrow().reference());
        assertEquals(97, destination.remaining());
        assertEquals(1, destination.reservations());
        assertEquals(1, attempts.findHistory(fixture.plan(), fixture.action()).size());
    }
}
