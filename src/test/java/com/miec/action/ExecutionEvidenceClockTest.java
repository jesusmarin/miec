package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

/** Desfases controlados sin modificar el reloj del equipo ni tolerar fechas futuras. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:evidence_clock;DB_CLOSE_DELAY=-1")
class ExecutionEvidenceClockTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionEvidenceService evidence;
    @MockitoSpyBean private ExecutionActivityService activity;

    /** Construye un intento iniciado antes de sustituir el reloj de recepción. */
    private ExecutionAttemptService.AttemptSnapshot started() {
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        return attempts.startAuthorizedAttempt(attempts.createAttempt(f.attemptCommand()).executionId(),"PROCESS_EXECUTION");
    }

    /** Igualdad y retrasos se conservan; cualquier adelanto de al menos un microsegundo se rechaza. */
    @ParameterizedTest @ValueSource(longs={-60000000,-1,0,1,300000000})
    @DisplayName("La fecha declarada respeta el reloj receptor con precisión de microsegundos")
    void shouldValidateControlledClockSkew(long skewMicros) {
        var s=started();var now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        doReturn(now).when(activity).databaseNow();
        var occurred=now.plus(skewMicros,ChronoUnit.MICROS);
        if(skewMicros>0) {
            assertThrows(IllegalArgumentException.class,()->evidence.report(UUID.randomUUID(),s.ownership(),
                    ExecutionEvidenceService.Outcome.EFFECT_UNKNOWN,"clock-test",occurred));
            assertTrue(evidence.findByAttempt(s.executionId()).isEmpty());
        } else {
            var report=evidence.report(UUID.randomUUID(),s.ownership(),ExecutionEvidenceService.Outcome.EFFECT_UNKNOWN,"clock-test",occurred);
            assertEquals(occurred,report.occurredAt());assertEquals(now,report.receivedAt());
        }
    }

    /** Un rechazo temporal no consume el ID; el mismo evento puede recibirse cuando alcanza el reloj receptor. */
    @Test @DisplayName("Reenvío después de rechazo por desfase conserva el mismo evento")
    void shouldAcceptReplayAfterClockCatchesUp() {
        var s=started();var now=Instant.now().truncatedTo(ChronoUnit.MICROS);var id=UUID.randomUUID();
        doReturn(now).when(activity).databaseNow();
        var occurred=now.plusSeconds(1);
        assertThrows(IllegalArgumentException.class,()->evidence.report(id,s.ownership(),ExecutionEvidenceService.Outcome.EFFECT_UNKNOWN,"ref",occurred));
        doReturn(now.plusSeconds(2)).when(activity).databaseNow();
        var accepted=evidence.report(id,s.ownership(),ExecutionEvidenceService.Outcome.EFFECT_UNKNOWN,"ref",occurred);
        assertEquals(accepted,evidence.report(id,s.ownership(),ExecutionEvidenceService.Outcome.EFFECT_UNKNOWN,"ref",occurred));
        assertEquals(1,evidence.findByAttempt(s.executionId()).size());
    }
}
