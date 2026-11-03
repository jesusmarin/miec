package com.miec.orchestration;

import com.miec.decision.ClosedSetEvaluation.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Reconstruye únicamente muestras y revisiones seleccionadas; se usa bajo locks de plan/run. */
@Component
class EvaluationSnapshotLoader {
    private final InterventionSupport store;
    private final MeasurementQueries queries;
    /** Comparte fuentes de lectura con MVP-05, sin duplicar indicadores o unidades. */
    EvaluationSnapshotLoader(InterventionSupport store,MeasurementQueries queries) {this.store=store;this.queries=queries;}
    /** Carga y valida el snapshot sin seleccionar otras mediciones o interpretar texto como reglas. */
    Snapshot load(InterventionViews.Plan plan,PlanExecutionContracts.Run run,MeasurementContracts.PostContext context,Instant finalizedAt) {
        var set=Objects.requireNonNull(context.set());var base=queries.context(plan.cycleId());var pairs=new ArrayList<Pair>();
        var criteria=base.objectives().stream().filter(o->o.objectiveId().equals(plan.objectiveId())).flatMap(o->o.criteria().stream()).toList();
        if(criteria.isEmpty()||!new HashSet<>(criteria.stream().map(MeasurementContracts.Indicator::indicatorId).toList())
                .equals(new HashSet<>(set.pairs().stream().map(MeasurementContracts.MeasurementPair::indicatorId).toList())))
            throw fail(CONFLICT,"EVALUATION_SET_INCOMPLETE","El conjunto no corresponde a todos los indicadores del objetivo");
        for(var selected:set.pairs()) {
            var baseline=base.baselines().stream().filter(b->b.baselineId().equals(selected.baselineId())&&b.indicatorId().equals(selected.indicatorId())).findFirst().orElseThrow(()->invalid());
            var post=context.measurements().stream().filter(p->p.measurementId().equals(selected.measurementId())&&p.indicatorId().equals(selected.indicatorId())&&p.runId().equals(run.runId())).findFirst().orElseThrow(()->invalid());
            var criterion=queries.indicator(plan.cycleId(),selected.indicatorId());
            MeasurementQueries.requireUnit(criterion,baseline.unit());MeasurementQueries.requireUnit(criterion,post.unit());
            var version=store.jdbc.queryForObject("SELECT version_id FROM indicator WHERE indicator_id=?",String.class,criterion.indicatorId());
            if(criterion.operator()!=post.operator()||criterion.target().compareTo(post.target())!=0||!Objects.equals(version,post.criterionVersion()))
                throw fail(CONFLICT,"CRITERION_CHANGED","El criterio actual difiere del capturado");
            var a=sample(context,baseline.baselineId(),baseline.evidenceId(),baseline.value(),baseline.unit(),baseline.source(),baseline.recordedBy(),baseline.measuredAt(),baseline.recordedAt(),null,selected.baselineReviewId());
            var b=sample(context,post.measurementId(),post.evidenceId(),post.value(),post.unit(),post.source(),post.recordedBy(),post.measuredAt(),post.recordedAt(),post.method(),selected.postReviewId());
            pairs.add(new Pair(selected.indicatorId(),post.criterionVersion(),post.operator().name(),post.target(),a,b));
        }
        return new Snapshot(set.setId(),run.runId(),plan.planId(),plan.cycleId(),plan.objectiveId(),set.policyVersion(),set.method(),set.baselineFrom(),set.baselineTo(),
                set.postFrom(),set.postTo(),run.createdAt(),finalizedAt,set.sealedAt(),pairs);
    }
    /** Verifica evidencia y revisión referenciadas, incluida su procedencia original. */
    private Sample sample(MeasurementContracts.PostContext context,UUID id,UUID evidence,java.math.BigDecimal value,String unit,String source,String author,
                          Instant observed,Instant received,String method,UUID reviewId) {
        var proofs=store.jdbc.query("SELECT * FROM evidence WHERE evidence_id=? AND cycle_id=?",(rs,row)-> {
            if(!Objects.equals(source,rs.getString("source_reference"))||!Objects.equals(author,rs.getString("provenance"))
                    ||!observed.equals(rs.getTimestamp("captured_at").toInstant())||!received.equals(rs.getTimestamp("created_at").toInstant()))throw invalid();
            return Map.entry(rs.getString("source_type"),rs.getString("quality_status"));
        },evidence,context.cycleId());
        if(proofs.size()!=1)throw invalid();Review review=null;
        if(reviewId!=null) {
            var r=context.reviews().stream().filter(item->item.reviewId().equals(reviewId)&&item.evidenceId().equals(evidence)).findFirst().orElseThrow(()->invalid());
            review=new Review(r.reviewId(),r.verdict().name(),r.reviewedBy(),r.method(),r.procedureReference(),r.limitations(),r.reviewedAt());
        }
        return new Sample(id,evidence,value,unit,source,proofs.getFirst().getKey(),author,observed,received,proofs.getFirst().getValue(),method,review);
    }
    /** Error saneado de un conjunto inconsistente, sin valores, SQL ni detalles internos. */
    private static LifecycleException invalid() {return fail(CONFLICT,"EVALUATION_INPUT_INVALID","No se puede reconstruir el contexto seleccionado");}
}
