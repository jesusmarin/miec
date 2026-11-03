package com.miec.decision;

import com.miec.evidence.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.miec.decision.ClosedSetEvaluation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Política numérica y de evidencia sin base de datos, reloj, infraestructura ni mocks. */
@DisplayName("MVP-06.2 — evaluación pura y suficiencia explícita")
class DeterministicResultEvaluatorTest {
    private static final Instant T=Instant.parse("2026-01-01T00:00:00Z");
    private final DeterministicResultEvaluator evaluator=new DeterministicResultEvaluator();
    /** Identidades estables de fixtures sin aleatoriedad. */
    private static UUID id(long value) {return new UUID(0,value);}
    /** Muestra manual con revisión opcional consistente con su recepción. */
    private Sample sample(int index,boolean post,String value,String verdict) {
        var review=verdict==null?null:new Review(id(index*100+50+(post?1:0)),verdict,"reviewer","method-v1","acta","Limitación preservada",T.plusSeconds(25));
        return new Sample(id(index*100+(post?2:1)),id(index*100+(post?4:3)),new BigDecimal(value),"ms","laboratorio","HUMAN_REPORT","author",
                T.plusSeconds(post?21:1),T.plusSeconds(post?22:2),"UNVERIFIED",post?"method-v1":null,review);
    }
    /** Criterio decimal de un indicador diferente por índice. */
    private Pair pair(int index,String operator,String base,String post,String target,String verdict) {
        return new Pair(id(index),"criterion-v1",operator,new BigDecimal(target),sample(index,false,base,verdict),sample(index,true,post,verdict));
    }
    /** Ventanas, contexto y cierre tipados; el constructor fija orden por indicador. */
    private Snapshot snapshot(List<Pair> pairs) {
        return new Snapshot(id(9001),id(9002),id(9003),id(9004),id(9005),"EXPLICIT_PAIR_V1","method-v1",T,T.plusSeconds(9),T.plusSeconds(20),T.plusSeconds(29),T.plusSeconds(10),T.plusSeconds(20),T.plusSeconds(30),pairs);
    }
    /** Evalúa usando exclusivamente el instante explícito del fixture. */
    private Report report(List<Pair> pairs) {var s=snapshot(pairs);return evaluator.report(evaluator.request(s,T.plusSeconds(31)));}

    /** Los cinco operadores distinguen sus límites sin epsilon o diferencia por escala. */
    @Test @DisplayName("GT/GTE/LT/LTE/EQ respetan igualdad decimal exacta")
    void shouldCompareOperatorBoundariesExactly() {
        for(String op:List.of("GT","GTE","LT","LTE","EQ")) {
            var result=report(List.of(pair(1,op,"1.0","1.00000","1",null)));var comparison=result.comparisons().getFirst();
            assertEquals(List.of("GTE","LTE","EQ").contains(op),comparison.postMeetsTarget(),op);
            assertEquals(0,comparison.delta().compareTo(BigDecimal.ZERO));assertEquals("NEUTRAL",comparison.change());
            assertEquals(ResultClassification.INCONCLUSIVE,result.result().classification());
        }
    }
    /** No divide por una base cero o negativa y EQ mide acercamiento a su umbral. */
    @Test @DisplayName("Cero, negativos y EQ producen diferencias sin porcentajes implícitos")
    void shouldHandleZeroNegativeAndEqualityDistance() {
        var improved=report(List.of(pair(1,"EQ","-5","0","0",null))).comparisons().getFirst();
        assertEquals("FAVORABLE",improved.change());assertEquals(0,new BigDecimal("5").compareTo(improved.delta()));assertTrue(improved.postMeetsTarget());
        assertEquals("UNFAVORABLE",report(List.of(pair(1,"LTE","0","1","-1",null))).comparisons().getFirst().change());
    }
    /** La revisión humana no eleva EV; el rechazo sí conserva una imposibilidad explícita de evaluar. */
    @Test @DisplayName("Ausencia o aceptación humana conserva EV-1; rechazo produce EV-0")
    void shouldPreserveConservativeEvidencePolicy() {
        for(String verdict:Arrays.asList(null,"ACCEPTED_WITH_LIMITATIONS","REJECTED")) {
            var result=report(List.of(pair(1,"LTE","150","90","100",verdict))).result();
            assertEquals("REJECTED".equals(verdict)?EvidenceSufficiency.NOT_EVALUABLE:EvidenceSufficiency.INSUFFICIENT,result.evidenceAssessment().sufficiency());
            assertEquals(ResultClassification.INCONCLUSIVE,result.classification());
            if(verdict!=null)assertTrue(result.evidenceAssessment().limitations().contains("Limitación preservada"));
        }
    }
    /** Un consumidor interno tampoco puede forzar EV-3 para eludir la política V1. */
    @Test @DisplayName("Rechaza suficiencia EV inventada por otro llamador")
    void shouldRejectForgedEvidenceAssessment() {
        var s=snapshot(List.of(pair(1,"LTE","150","90","100",null)));var valid=evaluator.request(s,T.plusSeconds(31));var old=valid.evidenceAssessment();
        var forged=new EvidenceAssessment(EvidenceSufficiency.SUFFICIENT,old.evidenceIds(),old.limitations(),old.unresolvedUncertainties(),old.rationale(),old.assessedAt());
        var command=new ResultEvaluationRequest(valid.cycleId(),valid.objectiveId(),forged,valid.observedResults(),valid.baselineReference(),valid.criteriaVersionId(),valid.evaluatedAt(),s);
        assertThrows(IllegalArgumentException.class,()->evaluator.evaluate(command));
    }
    /** El orden de entrada no cambia huella, conclusión, identidad ni métricas ordenadas. */
    @Test @DisplayName("Repetición y orden de indicadores producen el mismo reporte completo")
    void shouldBeDeterministicAndOrderIndependent() {
        var a=pair(1,"LTE","150","90","100",null);var b=pair(2,"GT","1","2","3",null);
        assertEquals(report(List.of(a,b)),report(List.of(b,a)));assertEquals(report(List.of(a,b)),report(List.of(a,b)));
    }
    /** El reloj es una entrada explícita y no altera la huella de las muestras o la identidad de conclusión. */
    @Test @DisplayName("Cambiar metadata temporal no cambia clasificación o huella de entrada")
    void shouldKeepClockOutsideBusinessConclusion() {
        var s=snapshot(List.of(pair(1,"LTE","150","90","100",null)));
        var first=evaluator.report(evaluator.request(s,T.plusSeconds(31)));var later=evaluator.report(evaluator.request(s,T.plusSeconds(60)));
        assertEquals(first.inputFingerprint(),later.inputFingerprint());assertEquals(first.result().id(),later.result().id());
        assertEquals(first.result().classification(),later.result().classification());assertNotEquals(first.result().evaluatedAt(),later.result().evaluatedAt());
    }
    /** Tabla concluyente ensayada con suficiencia acreditada hipotética, sin habilitarla en la fuente humana V1. */
    @Test @DisplayName("Clasificador numérico distingue RS-01/02/03/04 sin ocultar indicadores adversos")
    void shouldClassifyNumericVectorsSeparatelyFromEvidenceAdmission() {
        var pass=new Comparison(id(1),BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,"LTE",true,true,BigDecimal.ZERO,"NEUTRAL");
        var better=new Comparison(id(2),BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ZERO,"LTE",false,false,new BigDecimal("-9"),"FAVORABLE");
        var worse=new Comparison(id(3),BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.ZERO,"LT",false,false,BigDecimal.ONE,"UNFAVORABLE");
        var neutral=new Comparison(id(4),BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,"LTE",false,false,BigDecimal.ZERO,"NEUTRAL");
        assertEquals(ResultClassification.FAVORABLE,DeterministicResultEvaluator.classify(List.of(pass),EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS));
        assertEquals(ResultClassification.PARTIAL,DeterministicResultEvaluator.classify(List.of(pass,worse),EvidenceSufficiency.SUFFICIENT));
        assertEquals(ResultClassification.PARTIAL,DeterministicResultEvaluator.classify(List.of(better,worse),EvidenceSufficiency.SUFFICIENT));
        assertEquals(ResultClassification.UNFAVORABLE,DeterministicResultEvaluator.classify(List.of(worse,neutral),EvidenceSufficiency.SUFFICIENT));
        assertEquals(ResultClassification.NEUTRAL,DeterministicResultEvaluator.classify(List.of(neutral),EvidenceSufficiency.SUFFICIENT));
    }
    /** Cumplir el objetivo no se presenta como mejora si el valor se movió desfavorablemente dentro del umbral. */
    @Test @DisplayName("Cumplimiento y cambio respecto a la línea base son resultados distintos")
    void shouldSeparateThresholdFromImprovement() {
        var comparison=report(List.of(pair(1,"LTE","50","90","100",null))).comparisons().getFirst();
        assertTrue(comparison.baselineMeetsTarget());assertTrue(comparison.postMeetsTarget());assertEquals("UNFAVORABLE",comparison.change());
    }
    /** Sin snapshot no se interpreta el texto histórico como datos numéricos o instrucciones. */
    @Test @DisplayName("Rechaza request histórico sin snapshot y conserva compatibilidad del constructor")
    void shouldRejectUnstructuredInputWithoutParsingText() {
        var s=snapshot(List.of(pair(1,"LTE","150","90","100",null)));var valid=evaluator.request(s,T.plusSeconds(31));
        var legacy=new ResultEvaluationRequest(valid.cycleId(),valid.objectiveId(),valid.evidenceAssessment(),valid.observedResults(),"texto",valid.criteriaVersionId(),valid.evaluatedAt());
        assertNull(legacy.numericSnapshot());assertThrows(NullPointerException.class,()->evaluator.evaluate(legacy));
    }
    /** No concede resultado concluyente a un conjunto vacío ni admite muestras repetidas por indicador. */
    @Test @DisplayName("Conjunto vacío es EV-0 y los indicadores duplicados se rechazan")
    void shouldHandleMissingAndDuplicatePairsConservatively() {
        assertEquals(EvidenceSufficiency.NOT_EVALUABLE,report(List.of()).result().evidenceAssessment().sufficiency());
        var pair=pair(1,"LTE","150","90","100",null);assertThrows(IllegalArgumentException.class,()->snapshot(List.of(pair,pair)));
    }
}
