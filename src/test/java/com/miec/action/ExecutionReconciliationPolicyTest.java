package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static com.miec.action.ExecutionEvidenceService.Outcome.*;
import static com.miec.action.ExecutionReconciliationPolicy.Resolution.*;

/** Criterios de aceptación de reconciliación sin persistencia. */
class ExecutionReconciliationPolicyTest {
    private final ExecutionReconciliationPolicy policy=new ExecutionReconciliationPolicy();
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");

    /** Solo estados inciertos son elegibles; nunca se reabren terminales confirmados. */
    @ParameterizedTest @ValueSource(strings={"PENDING","COMPLETED","FAILED"})
    @DisplayName("Se rechazan estados pendientes o terminales confirmados")
    void shouldRejectIneligibleStates(String state) {
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->policy.validate(
                state,null,now,APPLIED,true,EFFECT_APPLIED,false));
    }

    /** El vencimiento exacto es elegible; un lease activo no lo es, aunque exista evidencia. */
    @Test @DisplayName("Solo lease vencido o histórico admite revisión de IN_PROGRESS")
    void shouldRequireExpiredLease() {
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->policy.validate(
                "IN_PROGRESS",now.plusSeconds(1),now,NO_EFFECT,true,NO_EFFECT_CONFIRMED,false));
        assertDoesNotThrow(()->policy.validate("IN_PROGRESS",now,now,NO_EFFECT,true,NO_EFFECT_CONFIRMED,false));
        assertDoesNotThrow(()->policy.validate("IN_PROGRESS",null,now,NO_EFFECT,true,NO_EFFECT_CONFIRMED,false));
    }

    /** Un reporte, timeout, evidencia opuesta o contradicción no demuestra ausencia. */
    @Test @DisplayName("Ausencia de efectos requiere verificación coincidente y sin contradicciones")
    void shouldRejectInsufficientEvidence() {
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->policy.validate("UNKNOWN",null,now,NO_EFFECT,false,NO_EFFECT_CONFIRMED,false));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->policy.validate("UNKNOWN",null,now,NO_EFFECT,true,EFFECT_UNKNOWN,false));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->policy.validate("UNKNOWN",null,now,NO_EFFECT,true,EFFECT_APPLIED,false));
        assertThrows(ExecutionAttemptService.AttemptDeniedException.class,()->policy.validate("UNKNOWN",null,now,NO_EFFECT,true,NO_EFFECT_CONFIRMED,true));
        assertDoesNotThrow(()->policy.validate("UNKNOWN",null,now,APPLIED,true,EFFECT_APPLIED,false));
    }
}
