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
    @io.swagger.v3.oas.annotations.media.Schema(name="MeasurementComparisonOperator",enumAsRef=true)
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

    /** Reutiliza el contrato numérico de medición; expectedVersion anidado pertenece al ciclo E08. */
    public record PostMeasure(@NotNull UUID runId,@NotNull @Valid Measure measurement,
                              @NotBlank @Size(max=500) String method) { }
    /** Revisión humana del procedimiento; no es un nivel EV ni una conclusión del objetivo. */
    public enum ReviewVerdict { ACCEPTED_WITH_LIMITATIONS, REJECTED }
    /** Revisión append-only de evidencia base o posterior, atribuida al principal APPROVER. */
    public record ReviewMeasure(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID runId,@NotNull UUID evidenceId,
                                @NotNull ReviewVerdict verdict,@NotBlank @Size(max=500) String method,
                                @NotBlank @Size(max=500) String procedureReference,@NotBlank @Size(max=2000) String limitations,
                                @NotBlank @Size(max=500) String reason) { }
    /** Selección explícita por indicador; nunca escoge automáticamente la última observación. */
    public record PairSelection(@NotNull UUID indicatorId,@NotNull UUID baselineId,@NotNull UUID measurementId) { }
    /** Congela un par por indicador, sus ventanas y revisiones, sin evaluar ni abandonar E08. */
    public record SealPost(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID runId,
                           @NotNull Instant baselineFrom,@NotNull Instant baselineTo,@NotNull Instant postFrom,@NotNull Instant postTo,
                           @NotBlank @Size(max=500) String method,@NotNull @Size(min=1,max=20) List<@NotNull @Valid PairSelection> pairs,
                           @NotBlank @Size(max=500) String reason) {
        /** Conserva validación de elementos y evita cambios del comando durante la transacción. */
        public SealPost { if(pairs!=null) pairs=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(pairs)); }
    }
    /** Hecho inmutable, con criterio capturado y evidencia original siempre UNVERIFIED. */
    public record PostMeasurement(UUID measurementId,UUID runId,UUID planId,UUID cycleId,UUID indicatorId,
                                   BigDecimal value,String unit,String method,String source,Instant measuredAt,Instant recordedAt,
                                   String recordedBy,UUID evidenceId,String qualityStatus,Comparison operator,BigDecimal target,String criterionVersion) { }
    /** Atestación humana separada de la evidencia cruda y de su suficiencia futura. */
    public record MeasurementReview(UUID reviewId,UUID runId,UUID evidenceId,ReviewVerdict verdict,String reviewedBy,
                                    String method,String procedureReference,String limitations,Instant reviewedAt) { }
    /** Referencias congeladas a muestras y revisiones presentes en el instante de cierre. */
    public record MeasurementPair(UUID indicatorId,UUID baselineId,UUID measurementId,UUID baselineReviewId,UUID postReviewId) { }
    /** Conjunto fijo para una evaluación futura; no contiene un resultado de evaluación. */
    public record MeasurementSet(UUID setId,UUID runId,String policyVersion,String method,
                                  Instant baselineFrom,Instant baselineTo,Instant postFrom,Instant postTo,
                                  String sealedBy,Instant sealedAt,List<MeasurementPair> pairs) {
        /** Las referencias del conjunto son inmutables en memoria y persistencia. */
        public MeasurementSet { pairs=List.copyOf(pairs); }
    }
    /** Contexto posterior separado de baseline para conservar compatibilidad y significado. */
    public record PostContext(UUID cycleId,UUID planId,long cycleVersion,List<PostMeasurement> measurements,
                               List<MeasurementReview> reviews,MeasurementSet set) {
        /** Evita modificar las colecciones de lectura. */
        public PostContext { measurements=List.copyOf(measurements);reviews=List.copyOf(reviews); }
    }
}
