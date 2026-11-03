package com.miec.orchestration;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contrato público de coordinación; ningún comando recibe ejecutores, secretos o identidades del cliente. */
public final class PlanExecutionContracts {
    /** Contenedor no instanciable. */
    private PlanExecutionContracts() { }

    /** Reserva desde E06 sobre versiones observadas; las creaciones no se reenvían ciegamente. */
    public record Start(@NotNull @PositiveOrZero Long expectedCycleVersion,
                        @NotNull @PositiveOrZero Long expectedPlanVersion,
                        @NotBlank @Size(max=500) String reason) { }

    /** Control explícito de una coordinación ya existente; expectedVersion pertenece al run. */
    public record Control(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=500) String reason) { }

    /** Finalización técnica explícita; ambas versiones evitan confirmar una revisión obsoleta. */
    public record Finish(@NotNull @PositiveOrZero Long expectedVersion,
                         @NotNull @PositiveOrZero Long expectedCycleVersion,
                         @NotBlank @Size(max=500) String reason) { }

    /** Solicita permiso de solo consulta/reconciliación para una acción incierta del run. */
    public record RequestReconciliation(@NotNull @PositiveOrZero Long expectedVersion,
                                        @NotNull UUID actionId,
                                        @NotNull @Min(30) @Max(3600) Long validForSeconds,
                                        @NotBlank @Size(max=500) String reason) { }

    /** Revisión explícita de una acción; la fuente y los hechos nunca los proporciona el cliente. */
    public record Reconcile(@NotNull @PositiveOrZero Long expectedVersion,
                            @NotNull UUID actionId,@NotNull UUID authorizationId,
                            @NotBlank @Size(max=500) String reason) { }

    /** Paso estable; observedOutcome es una proyección actual de IR-009, no una modificación de su historia. */
    public record Step(int ordinal,UUID actionId,UUID authorizationId,UUID operationKey,String state,
                       UUID executionId,String resultReference,String observedOutcome) { }

    /**
     * Estado consultable sin permiso EXECUTE_ACTION vigente. APPLIED describe efectos
     * técnicos; la finalización explícita puede mover a E08, sin evaluar objetivos
     * ni acreditar calidad de mediciones.
     */
    public record Run(UUID runId,UUID planId,UUID cycleId,String state,String cycleState,long version,
                      long generation,Instant leaseExpiresAt,String createdBy,String updatedBy,
                      Instant createdAt,Instant updatedAt,boolean requiresReview,List<Step> steps,
                      List<com.miec.action.ExecutionIncidentService.Incident> incidents) {
        /** Evita alterar el snapshot devuelto por el servidor. */
        public Run { steps=List.copyOf(steps);incidents=List.copyOf(incidents); }
    }
}
