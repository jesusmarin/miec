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

    /** Paso estable; observedOutcome es una proyección actual de IR-009, no una modificación de su historia. */
    public record Step(int ordinal,UUID actionId,UUID authorizationId,UUID operationKey,String state,
                       UUID executionId,String resultReference,String observedOutcome) { }

    /**
     * Estado consultable sin permiso EXECUTE_ACTION vigente. APPLIED describe efectos
     * técnicos; no mueve a E08, no evalúa objetivos ni acredita calidad de mediciones.
     */
    public record Run(UUID runId,UUID planId,UUID cycleId,String state,String cycleState,long version,
                      long generation,Instant leaseExpiresAt,String createdBy,String updatedBy,
                      Instant createdAt,Instant updatedAt,boolean requiresReview,List<Step> steps) {
        /** Evita alterar el snapshot devuelto por el servidor. */
        public Run { steps=List.copyOf(steps); }
    }
}
