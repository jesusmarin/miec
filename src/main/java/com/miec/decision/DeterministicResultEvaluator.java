package com.miec.decision;

import com.miec.evidence.EvidenceSufficiency;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.miec.decision.ClosedSetEvaluation.*;

/** Evaluador productivo puro V1: sin estado, reloj, IO ni generación aleatoria de identidad. */
public final class DeterministicResultEvaluator implements ResultEvaluator {
    private final MeasurementEvidencePolicy evidence=new MeasurementEvidencePolicy();
    /** Implementa el contrato histórico exigiendo además un snapshot numérico explícito. */
    @Override public EvaluationResult evaluate(ResultEvaluationRequest request) {return report(request).result();}
    /** Revalida la política de suficiencia; nunca confía en un nivel EV declarado por otro llamador. */
    public Report report(ResultEvaluationRequest request) {
        Objects.requireNonNull(request);var snapshot=Objects.requireNonNull(request.numericSnapshot(),"Se requiere snapshot numérico");
        if(!snapshot.cycleId().equals(request.cycleId())||!snapshot.objectiveId().equals(request.objectiveId())
                ||!snapshot.setId().toString().equals(request.baselineReference())||!criteriaVersion(snapshot).equals(request.criteriaVersionId()))
            throw new IllegalArgumentException("Solicitud de otro conjunto o criterio");
        var observed=snapshot.pairs().stream().map(p->new ResultEvaluationRequest.ObservedResult(p.indicatorId(),p.post().value().toPlainString(),null)).toList();
        if(!observed.equals(request.observedResults())) throw new IllegalArgumentException("Resultados ajenos al snapshot");
        var assessment=evidence.assess(snapshot,request.evaluatedAt());
        if(!assessment.equals(request.evidenceAssessment())) throw new IllegalArgumentException("La suficiencia no corresponde a SOURCE-QUALITY-V1");
        var comparisons=snapshot.pairs().stream().map(this::compare).toList();
        var classification=classify(comparisons,assessment.sufficiency());
        UUID id=UUID.nameUUIDFromBytes(("MIEC-EVAL-01:"+snapshot.setId()+":"+POLICY+":"+EVIDENCE_POLICY).getBytes(StandardCharsets.UTF_8));
        var result=new EvaluationResult(id,snapshot.cycleId(),snapshot.objectiveId(),classification,assessment,
                assessment.sufficiency()==EvidenceSufficiency.NOT_EVALUABLE?ConfidenceLevel.UNDETERMINED:ConfidenceLevel.LOW,
                "Clasificación "+classification.getCode()+" bajo "+POLICY+"; cumplimiento y variación se informan por separado; sin atribución causal",request.evaluatedAt());
        return new Report(snapshot.setId(),POLICY,EVIDENCE_POLICY,fingerprint(snapshot),result,comparisons);
    }
    /** Construye una solicitud interna consistente sin permitir que el cliente seleccione EV o criterios nuevos. */
    public ResultEvaluationRequest request(Snapshot snapshot,java.time.Instant at) {
        return new ResultEvaluationRequest(snapshot.cycleId(),snapshot.objectiveId(),evidence.assess(snapshot,at),
                snapshot.pairs().stream().map(p->new ResultEvaluationRequest.ObservedResult(p.indicatorId(),p.post().value().toPlainString(),null)).toList(),
                snapshot.setId().toString(),criteriaVersion(snapshot),at,snapshot);
    }
    /** Calcula diferencia exacta y movimiento hacia el criterio, incluso para cero, negativos o EQ. */
    private Comparison compare(Pair pair) {
        var base=pair.baseline().value();var post=pair.post().value();var delta=post.subtract(base);
        int favorable=switch(pair.operator()) {
            case "GT","GTE" -> delta.signum();
            case "LT","LTE" -> -delta.signum();
            case "EQ" -> base.subtract(pair.target()).abs().compareTo(post.subtract(pair.target()).abs());
            default -> throw new IllegalArgumentException("Operador no soportado");
        };
        return new Comparison(pair.indicatorId(),base,post,pair.target(),pair.operator(),meets(base,pair),meets(post,pair),delta,
                favorable>0?"FAVORABLE":favorable<0?"UNFAVORABLE":"NEUTRAL");
    }
    /** Aplica operadores decimales exactos, sin epsilon ni conversión de unidades. */
    private static boolean meets(BigDecimal value,Pair pair) {
        int order=value.compareTo(pair.target());
        return switch(pair.operator()){case "GT"->order>0;case "GTE"->order>=0;case "LT"->order<0;case "LTE"->order<=0;case "EQ"->order==0;default->throw new IllegalArgumentException("Operador no soportado");};
    }
    /** Tabla numérica independiente; el adaptador V1 solo la llama con suficiencia derivada de su política. */
    static ResultClassification classify(List<Comparison> values,EvidenceSufficiency sufficiency) {
        if(values.isEmpty()||!sufficiency.allowsEvaluation()) return ResultClassification.INCONCLUSIVE;
        if(values.stream().allMatch(Comparison::postMeetsTarget)) return ResultClassification.FAVORABLE;
        if(values.stream().anyMatch(c->c.postMeetsTarget()||"FAVORABLE".equals(c.change()))) return ResultClassification.PARTIAL;
        if(values.stream().anyMatch(c->"UNFAVORABLE".equals(c.change()))) return ResultClassification.UNFAVORABLE;
        return ResultClassification.NEUTRAL;
    }
}
