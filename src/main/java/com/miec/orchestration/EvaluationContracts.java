package com.miec.orchestration;

import com.miec.decision.ClosedSetEvaluation;
import java.util.Objects;

/** Contratos de consulta de conclusiones; no exponen un comando de creación antes de 06.4. */
public final class EvaluationContracts {
    /** Contenedor no instanciable. */
    private EvaluationContracts() { }
    /** Entrada completa y conclusión persistida, no reconstruida a partir de mediciones actuales. */
    public record Saved(String schemaVersion,String recordedBy,ClosedSetEvaluation.Snapshot inputs,ClosedSetEvaluation.Report report) {
        /** Conserva un formato reconocible y contexto coherente para lectura histórica. */
        public Saved {
            if(!"EVALUATION-SNAPSHOT-01".equals(schemaVersion)||recordedBy==null||recordedBy.isBlank())throw new IllegalArgumentException("Snapshot de evaluación inválido");
            Objects.requireNonNull(inputs);Objects.requireNonNull(report);
            if(!inputs.setId().equals(report.setId())||!inputs.cycleId().equals(report.result().cycleId())||!inputs.objectiveId().equals(report.result().objectiveId()))
                throw new IllegalArgumentException("Conclusión de otro contexto");
        }
    }
}
