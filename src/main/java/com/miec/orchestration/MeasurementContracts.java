package com.miec.orchestration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos numéricos del MVP v01; no infieren unidades ni resultados de una descripción. */
public final class MeasurementContracts {
    /** Contenedor no instanciable. */
    private MeasurementContracts() { }
    /** Operadores exactos sobre valores decimales expresados en la misma unidad. */
    public enum Comparison { GT, GTE, LT, LTE, EQ }
    /** Condición verificable; el umbral no representa una medición de resultado. */
    public record Criterion(@NotBlank @Size(max=255) String name,@NotBlank @Size(max=50) String unit,
            @NotNull Comparison operator,@NotNull @Digits(integer=15,fraction=5) BigDecimal target) { }
    /** Inicialización atómica; todos los indicadores requieren línea base para cerrar observación. */
    public record Initialize(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=255) String objective,
            @NotBlank @Size(max=4000) String successCriteria,@NotBlank @Size(max=500) String reason,
            @NotNull @Size(min=1,max=20) List<@NotNull @Valid Criterion> criteria) {
        /** Copia defensiva que conserva nulls para que los rechace Bean Validation. */
        public Initialize { if(criteria!=null) criteria=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(criteria)); }
    }
    /** Medición humana con fecha del hecho y fuente obligatorias, sin selección de identidad. */
    public record Measure(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID indicatorId,
            @NotNull @Digits(integer=15,fraction=5) BigDecimal value,@NotBlank @Size(max=50) String unit,
            @NotBlank @Size(max=500) String source,@NotNull Instant measuredAt,
            @NotBlank @Size(max=500) String reason) { }
    /** Solicita cerrar el conjunto completo de líneas base. */
    public record Complete(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=500) String reason) { }
    /** Indicador consultable con su condición numérica. */
    public record Indicator(UUID indicatorId,String name,String unit,Comparison operator,BigDecimal target) { }
    /** Evidencia manual inmutable por API; recepción y hecho son fechas diferentes. */
    public record Baseline(UUID baselineId,UUID indicatorId,BigDecimal value,String unit,String source,
            Instant measuredAt,Instant recordedAt,String recordedBy,String qualityStatus,UUID evidenceId) { }
    /** Objetivo directamente consultable sin depender de un plan posterior. */
    public record Objective(UUID objectiveId,String name,String successCriteria,List<Indicator> criteria) { }
    /** Fotografía transaccional del objetivo y todas sus líneas base, incluidas las históricas. */
    public record Context(UUID cycleId,String mode,List<Objective> objectives,List<Baseline> baselines) { }
}
