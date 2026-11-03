package com.miec.orchestration;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

/** ROUND-CONTEXT-V1: contexto explícito sin habilitar aún continuidad. */
public final class RoundContracts {
    /** Contenedor de contratos. */
    private RoundContracts() { }
    /** AUTHOR identifica únicamente la ronda inicial en E11. */
    public record RegisterInitial(@NotNull @PositiveOrZero Long expectedCycleVersion,
                                  @NotBlank @Size(max=500) String reason) { }
    /** APPROVER concluye el plan con control de ambas versiones. */
    public record CompletePlan(@NotNull @PositiveOrZero Long expectedCycleVersion,
                               @NotNull @PositiveOrZero Long expectedRoundVersion,
                               @NotBlank @Size(max=500) String reason) { }
    /** Copia histórica de hechos y políticas originales; no recalcula criterios. */
    @io.swagger.v3.oas.annotations.media.Schema(name="RoundHistoricalSnapshotV1")
    public record Snapshot(String schemaVersion,EvaluationContracts.Saved evaluation,
                           CycleCompletionContracts.HumanDecision decision,CycleCompletionContracts.Learning learning) {
        /** Rechaza contextos mezclados y entradas cuya huella ya no coincide. */
        public Snapshot {
            Objects.requireNonNull(evaluation);Objects.requireNonNull(decision);Objects.requireNonNull(learning);
            if(!"ROUND-CONTEXT-V1".equals(schemaVersion)
                ||!evaluation.report().result().id().equals(decision.evaluationId())
                ||!decision.decisionId().equals(learning.decisionId())
                ||!com.miec.decision.ClosedSetEvaluation.fingerprint(evaluation.inputs()).equals(evaluation.report().inputFingerprint()))
                throw new IllegalArgumentException("Contexto de ronda incoherente");
        }
    }
    /** Acta operativa independiente de la aprobación y del éxito del ciclo. */
    public record Conclusion(String policyVersion,String recordedBy,String reason,UUID eventId,Instant completedAt) { }
    /** La versión cambia al agregar conclusión; el snapshot permanece inmutable. */
    public record Round(UUID roundId,UUID cycleId,int number,UUID previousRoundId,String state,long version,
                        String registeredBy,String reason,Instant registeredAt,Instant concludedAt,UUID planId,String planFingerprint,
                        Snapshot snapshot,Conclusion conclusion) { }
    /** Estado actual separado de la historia congelada. */
    @io.swagger.v3.oas.annotations.media.Schema(name="RoundContextV1")
    public record Context(LifecycleViews.CycleView cycle,List<Round> rounds,List<UUID> activePlanIds) { }
}
