package com.miec.orchestration;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

/** Contratos versionados de contexto, conclusión operativa y continuidad aprobada entre rondas. */
public final class RoundContracts {
    /** Intenciones cerradas de continuidad, nunca estados o instrucciones ejecutables. */
    public enum ContinuationMode { COLLECT_EVIDENCE, ADJUST_PLAN }
    /** Hecho humano nuevo para una continuidad directa a planificación; no referencia muestras antiguas. */
    public record BaselineInput(@NotNull UUID indicatorId,@NotNull @Digits(integer=15,fraction=5) java.math.BigDecimal value,
        @NotBlank @Size(max=50) String unit,@NotBlank @Size(max=500) String source,@NotNull Instant measuredAt) { }
    /** Solicitud AUTHOR con contexto y reconocimiento de limitaciones; el servidor fija política y destino. */
    public record RequestContinuation(@NotNull @PositiveOrZero Long expectedCycleVersion,@NotNull @PositiveOrZero Long expectedRoundVersion,
        @NotNull UUID decisionId,@NotNull UUID learningId,@NotNull ContinuationMode mode,@NotNull @AssertTrue Boolean acknowledgeLimitations,
        @NotBlank @Size(max=500) String reason,@NotNull @Size(max=20) List<@NotNull @jakarta.validation.Valid BaselineInput> baselines,
        @Size(max=4000) String diagnosis) {
        /** Impide modificar las muestras después de validar la solicitud. */
        public RequestContinuation {if(baselines!=null)baselines=Collections.unmodifiableList(new ArrayList<>(baselines));}
    }
    /** Aprobación independiente con versiones de ciclo, ronda y solicitud. */
    public record ApproveContinuation(@NotNull @PositiveOrZero Long expectedCycleVersion,@NotNull @PositiveOrZero Long expectedRoundVersion,
        @NotNull @PositiveOrZero Long expectedRequestVersion,@NotNull @AssertTrue Boolean acknowledgeLimitations,@NotBlank @Size(max=500) String reason) { }
    /** Recibo recuperable; una solicitud vieja permanece histórica y no puede aprobarse con otro contexto. */
    public record Continuation(UUID requestId,UUID cycleId,UUID roundId,String status,long version,long cycleVersion,long roundVersion,
        String requestedBy,String reason,String policyVersion,Instant requestedAt,RequestContinuation input,String approvedBy,
        String approvalReason,Instant approvedAt,UUID newRoundId) { }
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
