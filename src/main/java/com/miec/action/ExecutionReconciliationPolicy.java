package com.miec.action;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Objects;

/** Reglas conservadoras de resolución; no actualiza estados ni autoriza ejecuciones. */
@Component
public class ExecutionReconciliationPolicy {
    /** Decisiones definitivas respaldadas por evidencia, no estados de ejecución. */
    public enum Resolution { APPLIED, NO_EFFECT }

    /**
     * Exige incertidumbre, fin de la vigencia y evidencia verificada sin contradicción.
     * Los indicadores de evidencia se calculan en el servicio bajo bloqueo; no
     * constituyen un contrato de verificación ofrecido a clientes externos.
     */
    public void validate(String status, Instant lease, Instant now, Resolution resolution,
            boolean verified, ExecutionEvidenceService.Outcome evidenceOutcome, boolean conflictingEvidence) {
        Objects.requireNonNull(now); Objects.requireNonNull(resolution); Objects.requireNonNull(evidenceOutcome);
        if (!("UNKNOWN".equals(status) || "IN_PROGRESS".equals(status))
                || ("IN_PROGRESS".equals(status) && lease!=null && lease.isAfter(now))) {
            throw ExecutionRecoveryAccess.denied("El intento no es reconciliable");
        }
        var expected=resolution==Resolution.APPLIED?ExecutionEvidenceService.Outcome.EFFECT_APPLIED:
                ExecutionEvidenceService.Outcome.NO_EFFECT_CONFIRMED;
        if(!verified || evidenceOutcome!=expected || conflictingEvidence) {
            throw ExecutionRecoveryAccess.denied("Evidencia insuficiente o contradictoria");
        }
    }
}
