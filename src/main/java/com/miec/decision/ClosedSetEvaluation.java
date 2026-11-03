package com.miec.decision;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Entradas tipadas e inmutables del evaluador; no depende de persistencia, Spring o reloj. */
public final class ClosedSetEvaluation {
    public static final String POLICY="EVALUATION-POLICY-V1";
    public static final String EVIDENCE_POLICY="SOURCE-QUALITY-V1";
    /** Contenedor de contratos no instanciable. */
    private ClosedSetEvaluation() { }
    /** Revisión seleccionada al cerrar el conjunto, sin equivalencia implícita con un nivel EV. */
    public record Review(UUID id,String verdict,String reviewer,String method,String reference,String limitations,Instant reviewedAt) {
        /** Exige identidad y el vocabulario cerrado de revisión humana existente. */
        public Review {
            Objects.requireNonNull(id);Objects.requireNonNull(reviewedAt);
            if(!List.of("ACCEPTED_WITH_LIMITATIONS","REJECTED").contains(verdict)) throw new IllegalArgumentException("Revisión desconocida");
            reviewer=text(reviewer);method=text(method);reference=text(reference);limitations=text(limitations);
        }
    }
    /** Copia completa de la muestra; method puede faltar solo en la línea base histórica. */
    public record Sample(UUID id,UUID evidenceId,BigDecimal value,String unit,String source,String sourceType,String author,
                         Instant observedAt,Instant receivedAt,String quality,String method,Review review) {
        /** Conserva tiempos y procedencia sin interpretar una declaración como autenticación instrumental. */
        public Sample {
            Objects.requireNonNull(id);Objects.requireNonNull(evidenceId);Objects.requireNonNull(value);
            Objects.requireNonNull(observedAt);Objects.requireNonNull(receivedAt);
            unit=text(unit);source=text(source);sourceType=text(sourceType);author=text(author);quality=text(quality);
            if(observedAt.isAfter(receivedAt)) throw new IllegalArgumentException("Fecha de muestra inválida");
            if(review!=null&&(review.reviewer().equalsIgnoreCase(author)||review.reviewedAt().isBefore(receivedAt)))
                throw new IllegalArgumentException("Procedencia de revisión incompatible");
        }
    }
    /** Criterio capturado y muestras comparables de un indicador. */
    public record Pair(UUID indicatorId,String criterionVersion,String operator,BigDecimal target,Sample baseline,Sample post) {
        /** Valida comparabilidad dimensional; no convierte ni reinterpreta unidades. */
        public Pair {
            Objects.requireNonNull(indicatorId);Objects.requireNonNull(target);Objects.requireNonNull(baseline);Objects.requireNonNull(post);
            criterionVersion=text(criterionVersion);
            if(!List.of("GT","GTE","LT","LTE","EQ").contains(operator)||!baseline.unit().equals(post.unit()))
                throw new IllegalArgumentException("Criterio o unidad incompatible");
        }
    }
    /** Snapshot del conjunto y contexto original, ordenado canónicamente por indicador. */
    public record Snapshot(UUID setId,UUID runId,UUID planId,UUID cycleId,UUID objectiveId,String setPolicy,String method,
                           Instant baselineFrom,Instant baselineTo,Instant postFrom,Instant postTo,Instant runStartedAt,
                           Instant finalizedAt,Instant sealedAt,List<Pair> pairs) {
        /** Rechaza ventanas, muestras, revisiones o identidades ambiguas antes de clasificar. */
        public Snapshot {
            Objects.requireNonNull(setId);Objects.requireNonNull(runId);Objects.requireNonNull(planId);Objects.requireNonNull(cycleId);Objects.requireNonNull(objectiveId);
            for(var time:List.of(baselineFrom,baselineTo,postFrom,postTo,runStartedAt,finalizedAt,sealedAt)) Objects.requireNonNull(time);
            method=text(method);
            if(!"EXPLICIT_PAIR_V1".equals(setPolicy)||baselineFrom.isAfter(baselineTo)||baselineTo.isAfter(runStartedAt)
                    ||runStartedAt.isAfter(finalizedAt)||postFrom.isBefore(finalizedAt)||postFrom.isAfter(postTo)||postTo.isAfter(sealedAt))
                throw new IllegalArgumentException("Ventanas o política incompatibles");
            pairs=pairs.stream().sorted(Comparator.comparing(p->p.indicatorId().toString())).toList();
            var indicators=new HashSet<UUID>();var ids=new HashSet<UUID>();var proofs=new HashSet<UUID>();
            for(var pair:pairs) {
                if(!indicators.add(pair.indicatorId())||!ids.add(pair.baseline().id())||!ids.add(pair.post().id())
                        ||!proofs.add(pair.baseline().evidenceId())||!proofs.add(pair.post().evidenceId())) throw new IllegalArgumentException("Muestras duplicadas");
                if(!inside(pair.baseline().observedAt(),baselineFrom,baselineTo)||!inside(pair.post().observedAt(),postFrom,postTo)
                        ||pair.baseline().receivedAt().isAfter(runStartedAt)||pair.post().receivedAt().isAfter(sealedAt)
                        ||!method.equals(pair.post().method())) throw new IllegalArgumentException("Muestra fuera del contexto cerrado");
                for(var sample:List.of(pair.baseline(),pair.post())) if(sample.review()!=null
                        &&(!method.equals(sample.review().method())||sample.review().reviewedAt().isAfter(sealedAt)))
                    throw new IllegalArgumentException("Revisión fuera del conjunto");
            }
        }
    }
    /** Diferencia y cumplimiento separados; favorable no demuestra causalidad. */
    public record Comparison(UUID indicatorId,BigDecimal baseline,BigDecimal post,BigDecimal target,String operator,
                             boolean baselineMeetsTarget,boolean postMeetsTarget,BigDecimal delta,String change) { }
    /** Conclusión recuperable con versiones explícitas y métricas por indicador. */
    public record Report(UUID setId,String policyVersion,String evidencePolicyVersion,String inputFingerprint,
                         EvaluationResult result,List<Comparison> comparisons) {
        /** Evita modificar las métricas devueltas. */
        public Report {comparisons=List.copyOf(comparisons);}
    }
    /** Huella de las entradas completas, sin hora de evaluación o IDs aleatorios. */
    public static String fingerprint(Snapshot snapshot) {return digest(snapshot,false);}
    /** Versión compuesta de criterios, sin sustituir las versiones individuales capturadas. */
    public static String criteriaVersion(Snapshot snapshot) {return "CRITERIA-SHA256:"+digest(snapshot,true);}
    /** Codificación no ambigua: cada campo UTF-8 lleva su longitud; null difiere de vacío. */
    private static String digest(Snapshot s,boolean criteriaOnly) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            field(out,criteriaOnly?"CRITERIA-SNAPSHOT-01":"EVALUATION-INPUT-01");
            if(!criteriaOnly){field(out,POLICY);field(out,EVIDENCE_POLICY);}
            if(!criteriaOnly) for(var value:new Object[]{s.setId(),s.runId(),s.planId(),s.cycleId(),s.objectiveId(),s.setPolicy(),s.method(),s.baselineFrom(),s.baselineTo(),s.postFrom(),s.postTo(),s.runStartedAt(),s.finalizedAt(),s.sealedAt()}) field(out,value);
            out.writeInt(s.pairs().size());
            for(var p:s.pairs()) {
                for(var value:new Object[]{p.indicatorId(),p.criterionVersion(),p.operator(),p.target(),p.post().unit()}) field(out,value);
                if(!criteriaOnly) for(var sample:List.of(p.baseline(),p.post())) {
                    for(var value:new Object[]{sample.id(),sample.evidenceId(),sample.value(),sample.unit(),sample.source(),sample.sourceType(),sample.author(),sample.observedAt(),sample.receivedAt(),sample.quality(),sample.method()}) field(out,value);
                    var r=sample.review();out.writeBoolean(r!=null);
                    if(r!=null) for(var value:new Object[]{r.id(),r.verdict(),r.reviewer(),r.method(),r.reference(),r.limitations(),r.reviewedAt()}) field(out,value);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException|NoSuchAlgorithmException impossible) {throw new IllegalStateException("No se pudo calcular huella canónica",impossible);}
    }
    /** Escribe sin depender de locale, escala decimal o separadores presentes en el texto. */
    private static void field(DataOutputStream out,Object value) throws IOException {
        if(value==null){out.writeInt(-1);return;}
        String text=value instanceof BigDecimal decimal?decimal.stripTrailingZeros().toPlainString():value.toString();
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    /** Fechas inclusivas para las ventanas declaradas. */
    private static boolean inside(Instant value,Instant from,Instant to) {return !value.isBefore(from)&&!value.isAfter(to);}
    /** Valida metadatos obligatorios sin inferir su significado de negocio. */
    private static String text(String value) {if(value==null||value.isBlank())throw new IllegalArgumentException("Metadato requerido");return value.trim();}
}
