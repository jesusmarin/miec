package com.miec.orchestration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** Entradas de MVP-02; ninguna admite identidades, roles o aprobaciones aportados por el cliente. */
public final class InterventionCommands {
    /** Evita instancias del contenedor. */
    private InterventionCommands() { }

    /** Completa inicialización con un objetivo y criterio de éxito declarados por el autor. */
    public record Initialize(@NotNull @PositiveOrZero Long expectedVersion,
                             @NotBlank @Size(max=255) String objective,
                             @NotBlank @Size(max=4000) String successCriteria,
                             @NotBlank @Size(max=500) String reason) { }

    /** Observación humana; su persistencia no la convierte en evidencia verificada. */
    public record Observe(@NotNull @PositiveOrZero Long expectedVersion,
                          @NotBlank @Size(max=4000) String description,
                          @Size(max=500) String sourceReference,
                          @NotBlank @Size(max=500) String reason) { }

    /** Diagnóstico manual sustentado en una observación del mismo ciclo. */
    public record Diagnose(@NotNull @PositiveOrZero Long expectedVersion, @NotNull UUID evidenceId,
                           @NotBlank @Size(max=4000) String summary,
                           @NotBlank @Size(max=500) String reason) { }

    /** Catálogo inicial limitado; describir una acción no la ejecuta ni habilita un comando arbitrario. */
    public record Action(@NotBlank @Pattern(regexp="PROCESS_EXECUTION") String actionType,
                         @NotBlank @Size(max=500) String description,
                         @Valid com.miec.execution.ExecutableActions.Spec execution) {
        /** Compatibilidad de código con planes descriptivos históricos; no les atribuye ejecutabilidad. */
        public Action(String actionType,String description) { this(actionType,description,null); }
    }

    /** Plan inmutable por API, con hasta veinte acciones identificadas por el servidor. */
    public record ProposePlan(@NotNull @PositiveOrZero Long expectedVersion, @NotNull UUID objectiveId,
                              @NotBlank @Size(max=4000) String expectedOutcomes,
                              @NotBlank @Size(max=4000) String rollbackStrategy,
                              @NotNull @Size(min=1,max=20) List<@NotNull @Valid Action> actions) {
        /** Captura una colección que el llamador no puede modificar después de validarla. */
        public ProposePlan { if (actions != null) actions = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(actions)); }
    }

    /** Solicita EXECUTE_ACTION para una acción concreta; la vigencia empieza al solicitar. */
    public record RequestAuthorization(@NotNull @PositiveOrZero Long expectedPlanVersion,
                                       @NotNull UUID actionId, @NotNull @Min(60) @Max(86400) Long validForSeconds) { }

    /** Decisión explícita sobre una versión de solicitud pendiente. */
    public record Decide(@NotNull @PositiveOrZero Long expectedVersion,
                         @NotBlank @Size(max=500) String reason) { }
}
