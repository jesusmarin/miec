package com.miec.decision;

import java.util.*;

/** F02-MVP-V1: intenciones sin efectos externos, con prioridad explícita y restricciones no compensables. */
public final class MvpAlternativePolicy implements AlternativeGenerator,AlternativeEvaluator {
    public static final String VERSION="F02-MVP-V1";
    /** Genera todas las acciones, marcando como inadmisibles las que carecen de ruta o fundamento en este MVP. */
    @Override public List<AlternativeAssessment> generateAlternatives(EvaluationResult result) {
        Objects.requireNonNull(result);
        var preferred=switch(result.classification()) {
            case FAVORABLE->DecisionAction.CLOSE_CYCLE;
            case PARTIAL,UNFAVORABLE->DecisionAction.ADJUST_PLAN;
            case INCONCLUSIVE->DecisionAction.COLLECT_EVIDENCE;
            default->DecisionAction.REQUEST_HUMAN_REVIEW;
        };
        var order=new LinkedHashSet<DecisionAction>();order.add(preferred);Collections.addAll(order,DecisionAction.values());
        return order.stream().map(action->{
            boolean admissible=switch(action){
                case COLLECT_EVIDENCE,REQUEST_HUMAN_REVIEW->true;
                case CLOSE_CYCLE->result.classification()!=ResultClassification.RISK_INADMISSIBLE;
                case ADJUST_PLAN->result.evidenceAssessment().allowsEvaluation()&&result.classification()!=ResultClassification.RISK_INADMISSIBLE;
                default->false;
            };
            return new AlternativeAssessment(action,admissible,admissible?"Intención administrativa bajo "+VERSION:"Sin ruta operativa o fundamento suficiente bajo "+VERSION,
                    admissible?"Conservar una decisión humana trazable; no declara mejora ni autoriza efectos":null,
                    result.evidenceAssessment().limitations(),null,false);
        }).toList();
    }
    /** Selecciona solo entre alternativas admisibles, manteniendo la prioridad explícita del generador. */
    @Override public AlternativeAssessment evaluateAlternatives(List<AlternativeAssessment> alternatives) {
        Objects.requireNonNull(alternatives);if(alternatives.isEmpty())throw new IllegalArgumentException("Se requieren alternativas");
        return alternatives.stream().filter(AlternativeAssessment::admissible).findFirst().orElse(null);
    }
}
