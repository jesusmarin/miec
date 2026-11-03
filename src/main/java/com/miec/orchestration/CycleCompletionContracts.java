package com.miec.orchestration;

import com.miec.decision.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

/** Comandos específicos de cierre, nunca una API genérica de cambio de estado. */
public final class CycleCompletionContracts {
    /** Contenedor de contratos no instanciable. */
    private CycleCompletionContracts() { }
    /** AUTHOR admite una evaluación con políticas del servidor y versión del ciclo. */
    public record Evaluate(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID setId,@NotBlank @Size(max=500) String reason) { }
    /** Selección humana explícita, distinta de recomendación, autorización y ejecución. */
    @io.swagger.v3.oas.annotations.media.Schema(name="CycleHumanDecisionCommand")
    public record Decide(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID evaluationId,@NotNull DecisionAction action,
                         @NotNull @AssertTrue Boolean acknowledgeLimitations,@NotBlank @Size(max=500) String reason) { }
    /** Aprendizaje humano que no eleva confianza o suficiencia de la evaluación. */
    public record Learn(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID decisionId,@NotBlank @Size(max=4000) String statement,
                        @NotBlank @Size(max=2000) String conditions,@NotBlank @Size(max=2000) String limitations,@NotBlank @Size(max=500) String reason) { }
    /** Cierre administrativo sin declaración implícita de éxito o causalidad. */
    public record Close(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID learningId,@NotBlank @Size(max=4000) String summary,
                        @NotNull @AssertTrue Boolean acknowledgeLimitations,@NotBlank @Size(max=255) String reason) { }
    /** Recibo de admisión operativa de una conclusión original. */
    public record Admission(UUID evaluationId,UUID eventId,String recordedBy,String reason,Instant recordedAt) { }
    /** Recomendación persistida y decisión humana permanecen separadas. */
    public record HumanDecision(UUID decisionId,UUID evaluationId,DecisionAction selectedAction,String decidedBy,String reason,
                                String policyVersion,Instant decidedAt,DecisionOutcome recommendation) { }
    /** Aprendizaje con condiciones, limitaciones y confianza heredada. */
    public record Learning(UUID learningId,UUID decisionId,String statement,String conditions,String limitations,String recordedBy,
                           ConfidenceLevel confidence,Instant recordedAt) { }
    /** Acta final que conserva responsable, motivo y fecha de cierre. */
    public record Closure(UUID learningId,String summary,String reason,String closedBy,Instant closedAt) { }
    /** Historial consultable incluso después del cierre; requiresReview hace visible una contradicción técnica tardía. */
    public record View(LifecycleViews.CycleView cycle,Admission admission,HumanDecision decision,Learning learning,Closure closure,boolean requiresReview) { }
    /** Resultado operativo con la conclusión y el recibo de transición. */
    public record EvaluationCreated(EvaluationContracts.Saved evaluation,View completion) { }
}
