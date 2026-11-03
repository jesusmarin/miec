package com.miec.orchestration;

import com.miec.domain.EventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/**
 * Mediciones posteriores append-only, separadas de evaluación. Bloquea sistema,
 * ciclo, plan y run, y confirma datos/evidencia/evento/versión atómicamente.
 * Ningún método realiza llamadas externas, ejecuta acciones ni asigna niveles EV.
 */
@Service
class PostMeasurementWorkflow {
    private final InterventionSupport store;
    private final InterventionPlans plans;
    private final PlanExecutionStore runs;
    private final MeasurementQueries queries;

    /** Comparte indicadores, unidades, identidad de plan y proyección conservadora IR-009. */
    PostMeasurementWorkflow(InterventionSupport store,InterventionPlans plans,PlanExecutionStore runs,MeasurementQueries queries) {
        this.store=store;this.plans=plans;this.runs=runs;this.queries=queries;
    }

    /** Contexto local bloqueado, con fecha de finalización técnica y objetivo aprobado. */
    private record Eligible(InterventionPlans.Locked locked,PlanExecutionContracts.Run run,Instant finalizedAt) { }

    /** Registra un hecho y evidencia UNVERIFIED sin emitir conclusión ni abandonar E08. */
    @Transactional(timeout=10)
    public PostContext record(UUID cycleId,UUID planId,PostMeasure command,String actor) {
        store.lifecycle.validate(command);var measure=command.measurement();
        var context=eligible(cycleId,planId,command.runId(),measure.expectedVersion());
        var indicator=indicator(context,measure.indicatorId());MeasurementQueries.requireUnit(indicator,measure.unit());
        precise(measure.measuredAt());var now=store.lifecycle.now();
        if(measure.measuredAt().isBefore(context.finalizedAt())||measure.measuredAt().isAfter(now))
            throw fail(BAD_REQUEST,"POST_MEASUREMENT_DATE_INVALID","La fecha debe estar entre la finalización técnica y recepción");
        String version=store.jdbc.queryForObject("SELECT version_id FROM indicator WHERE indicator_id=?",String.class,indicator.indicatorId());
        if(version==null||version.isBlank()) throw fail(CONFLICT,"CRITERION_VERSION_REQUIRED","El criterio requiere versión persistida");
        UUID id=UUID.randomUUID(),proof=UUID.randomUUID();
        store.jdbc.update("""
                INSERT INTO evidence(evidence_id,cycle_id,source_type,source_reference,description,captured_at,provenance,quality_status,limitations,created_at)
                VALUES (?,?,'HUMAN_REPORT',?,?,?,?,'UNVERIFIED','Reporte manual posterior; no acredita éxito',?)
                """,proof,cycleId,measure.source().trim(),"post-measurement:"+id,stamp(measure.measuredAt()),actor,stamp(now));
        store.jdbc.update("""
                INSERT INTO post_measurement(measurement_id,run_id,plan_id,cycle_id,indicator_id,observed_value,unit,measurement_method,
                    source_reference,measured_at,recorded_at,recorded_by,evidence_id,criterion_operator,criterion_target,criterion_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,command.runId(),planId,cycleId,indicator.indicatorId(),measure.value(),indicator.unit(),command.method().trim(),measure.source().trim(),
                stamp(measure.measuredAt()),stamp(now),actor,proof,indicator.operator().name(),indicator.target(),version);
        store.change(context.locked().cycle(),"E08_VERIFICANDO",EventType.POST_MEASUREMENT_RECORDED,actor,measure.reason().trim(),"post-measurement:"+id);
        return queries.posts(cycleId,planId);
    }

    /** Registra una atestación humana sin elevar la calidad cruda ni asignar suficiencia EV. */
    @Transactional(timeout=10)
    public PostContext review(UUID cycleId,UUID planId,ReviewMeasure command,String actor) {
        store.lifecycle.validate(command);var context=eligible(cycleId,planId,command.runId(),command.expectedVersion());
        var posts=queries.posts(cycleId,planId);String author=null;
        var post=posts.measurements().stream().filter(p->p.evidenceId().equals(command.evidenceId())).findFirst();
        if(post.isPresent()) {
            author=post.get().recordedBy();
            if(!post.get().method().equals(command.method().trim())) throw fail(BAD_REQUEST,"METHOD_MISMATCH","La revisión debe identificar el método registrado");
        } else {
            var base=queries.context(cycleId).baselines().stream().filter(b->command.evidenceId().equals(b.evidenceId())).findFirst()
                    .orElseThrow(()->fail(BAD_REQUEST,"INVALID_MEASUREMENT_EVIDENCE","La evidencia no pertenece a una medición de este contexto"));
            MeasurementQueries.requireUnit(indicator(context,base.indicatorId()),base.unit());
            if(base.measuredAt().isAfter(context.run().createdAt())) throw fail(BAD_REQUEST,"BASELINE_DATE_INVALID","La línea base debe preceder la ejecución");
            author=base.recordedBy();
        }
        if(author==null||author.isBlank()) throw fail(CONFLICT,"MEASUREMENT_PROVENANCE_REQUIRED","La medición no identifica a su autor");
        if(author.equalsIgnoreCase(actor)) throw fail(FORBIDDEN,"SELF_REVIEW_FORBIDDEN","Autor y revisor deben ser identidades distintas");
        if(posts.reviews().stream().anyMatch(r->r.evidenceId().equals(command.evidenceId())))
            throw fail(CONFLICT,"MEASUREMENT_ALREADY_REVIEWED","La evidencia ya tiene una revisión inmutable en este run");
        UUID id=UUID.randomUUID();
        store.jdbc.update("""
                INSERT INTO measurement_review(review_id,run_id,cycle_id,evidence_id,verdict,reviewed_by,measurement_method,procedure_reference,limitations,reviewed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,id,command.runId(),cycleId,command.evidenceId(),command.verdict().name(),actor,command.method().trim(),
                command.procedureReference().trim(),command.limitations().trim(),stamp(store.lifecycle.now()));
        store.change(context.locked().cycle(),"E08_VERIFICANDO",EventType.MEASUREMENT_REVIEW_RECORDED,actor,command.reason().trim(),"measurement-review:"+id);
        return queries.posts(cycleId,planId);
    }

    /**
     * Fija muestras y revisiones en una transacción. Ausencia o rechazo de revisión
     * permanecen explícitos: cerrar no concede suficiencia ni produce evaluación.
     */
    @Transactional(timeout=10)
    public PostContext seal(UUID cycleId,UUID planId,SealPost command,String actor) {
        store.lifecycle.validate(command);var context=eligible(cycleId,planId,command.runId(),command.expectedVersion());
        for(var date:List.of(command.baselineFrom(),command.baselineTo(),command.postFrom(),command.postTo())) precise(date);
        if(command.baselineFrom().isAfter(command.baselineTo())||command.baselineTo().isAfter(context.run().createdAt())
                ||command.postFrom().isBefore(context.finalizedAt())||command.postFrom().isAfter(command.postTo())||command.postTo().isAfter(store.lifecycle.now()))
            throw fail(BAD_REQUEST,"MEASUREMENT_WINDOWS_INVALID","Las ventanas deben distinguir línea base previa y resultado posterior a finalización");
        var criteria=queries.context(cycleId).objectives().stream().filter(o->o.objectiveId().equals(context.locked().plan().objectiveId())).flatMap(o->o.criteria().stream()).toList();
        var selected=new HashSet<UUID>();
        for(var pair:command.pairs()) if(!selected.add(pair.indicatorId())) throw fail(BAD_REQUEST,"DUPLICATE_MEASUREMENT_PAIR","Solo se permite un par por indicador");
        if(criteria.isEmpty()||!selected.equals(criteria.stream().map(Indicator::indicatorId).collect(java.util.stream.Collectors.toSet())))
            throw fail(BAD_REQUEST,"INCOMPLETE_MEASUREMENT_SET","Debe seleccionar exactamente un par por indicador del objetivo aprobado");
        var posts=queries.posts(cycleId,planId);var baselines=queries.context(cycleId).baselines();
        var pairs=new ArrayList<MeasurementPair>();
        for(var pair:command.pairs()) {
            var criterion=indicator(context,pair.indicatorId());
            var base=baselines.stream().filter(b->b.baselineId().equals(pair.baselineId())&&b.indicatorId().equals(pair.indicatorId())).findFirst()
                    .orElseThrow(()->fail(BAD_REQUEST,"INVALID_BASELINE_SELECTION","La línea base no corresponde al indicador/ciclo"));
            var post=posts.measurements().stream().filter(p->p.measurementId().equals(pair.measurementId())&&p.indicatorId().equals(pair.indicatorId())).findFirst()
                    .orElseThrow(()->fail(BAD_REQUEST,"INVALID_POST_SELECTION","El resultado no corresponde al run/indicador"));
            MeasurementQueries.requireUnit(criterion,base.unit());MeasurementQueries.requireUnit(criterion,post.unit());
            var version=store.jdbc.queryForObject("SELECT version_id FROM indicator WHERE indicator_id=?",String.class,criterion.indicatorId());
            if(criterion.operator()!=post.operator()||criterion.target().compareTo(post.target())!=0||!post.criterionVersion().equals(version))
                throw fail(CONFLICT,"CRITERION_CHANGED","El criterio cambió desde el registro del resultado");
            if(!inside(base.measuredAt(),command.baselineFrom(),command.baselineTo())||!inside(post.measuredAt(),command.postFrom(),command.postTo()))
                throw fail(BAD_REQUEST,"MEASUREMENT_OUTSIDE_WINDOW","Las muestras seleccionadas deben pertenecer a las ventanas declaradas");
            if(!post.method().equals(command.method().trim())) throw fail(BAD_REQUEST,"METHOD_MISMATCH","El método del resultado no coincide con el conjunto");
            var baselineReview=reviewFor(posts,base.evidenceId(),command.method());var postReview=reviewFor(posts,post.evidenceId(),command.method());
            pairs.add(new MeasurementPair(pair.indicatorId(),base.baselineId(),post.measurementId(),baselineReview,postReview));
        }
        UUID id=UUID.randomUUID();var now=store.lifecycle.now();
        store.jdbc.update("""
                INSERT INTO measurement_set(set_id,run_id,plan_id,cycle_id,policy_version,measurement_method,baseline_from,baseline_to,post_from,post_to,sealed_by,sealed_at)
                VALUES (?,?,?,?,'EXPLICIT_PAIR_V1',?,?,?,?,?,?,?)
                """,id,command.runId(),planId,cycleId,command.method().trim(),stamp(command.baselineFrom()),stamp(command.baselineTo()),stamp(command.postFrom()),stamp(command.postTo()),actor,stamp(now));
        for(var pair:pairs) store.jdbc.update("INSERT INTO measurement_pair VALUES (?,?,?,?,?,?,?,?)",id,command.runId(),cycleId,pair.indicatorId(),pair.baselineId(),pair.measurementId(),pair.baselineReviewId(),pair.postReviewId());
        store.change(context.locked().cycle(),"E08_VERIFICANDO",EventType.MEASUREMENT_SET_SEALED,actor,command.reason().trim(),"measurement-set:"+id);
        return queries.posts(cycleId,planId);
    }

    /** Conserva la revisión presente o su ausencia; cualquier declaración de método contradictoria bloquea el conjunto. */
    private static UUID reviewFor(PostContext context,UUID evidence,String method) {
        var review=context.reviews().stream().filter(r->r.evidenceId().equals(evidence)).findFirst();
        if(review.isEmpty()) return null;
        if(!review.get().method().equals(method.trim())) throw fail(BAD_REQUEST,"METHOD_MISMATCH","La revisión identifica un método incompatible");
        return review.get().reviewId();
    }

    /** Ventanas inclusivas, sin escoger otra muestra cuando la solicitada queda fuera. */
    private static boolean inside(Instant value,Instant from,Instant to) {return !value.isBefore(from)&&!value.isAfter(to);}

    /** Exige el mismo ciclo, run finalizado y plan vigente, sin evidencia técnica contradictoria. */
    private Eligible eligible(UUID cycleId,UUID planId,UUID runId,long version) {
        var locked=plans.lock(planId);
        if(!cycleId.equals(locked.cycle().cycleId())) throw fail(BAD_REQUEST,"PLAN_CYCLE_MISMATCH","El plan no pertenece al ciclo");
        store.expect(locked.cycle(),version,"E08_VERIFICANDO");
        var ids=store.jdbc.query("SELECT run_id FROM plan_execution WHERE plan_id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),planId);
        if(ids.size()!=1||!ids.getFirst().equals(runId)) throw fail(BAD_REQUEST,"RUN_CONTEXT_MISMATCH","La ejecución no pertenece al plan y ciclo");
        var run=runs.view(planId);
        if(!"APPROVED".equals(locked.plan().status())||!"APPLIED".equals(run.state())||run.requiresReview()
                ||run.steps().isEmpty()||run.steps().stream().anyMatch(s->!"APPLIED".equals(s.state())||!"APPLIED".equals(s.observedOutcome())))
            throw fail(CONFLICT,"EXECUTION_NOT_CONFIRMED","La ejecución debe conservar resultados aplicados sin contradicciones");
        var dates=store.jdbc.query("SELECT occurred_at FROM cycle_event WHERE cycle_id=? AND event_type='PLAN_EXECUTION_FINALIZED' AND payload_reference=?",
                (rs,row)->rs.getTimestamp(1).toInstant(),cycleId,"plan-execution:"+runId);
        if(dates.size()!=1) throw fail(CONFLICT,"EXECUTION_NOT_FINALIZED","La ejecución no tiene finalización técnica única");
        if(queries.posts(cycleId,planId).set()!=null) throw fail(CONFLICT,"MEASUREMENT_SET_SEALED","El conjunto del run está cerrado; no admite nuevos datos o revisiones");
        return new Eligible(locked,run,dates.getFirst());
    }

    /** Reutiliza criterios del ciclo y restringe el indicador al objetivo aprobado por el plan. */
    private Indicator indicator(Eligible context,UUID id) {
        var indicator=queries.indicator(context.locked().cycle().cycleId(),id);
        boolean belongs=queries.context(context.locked().cycle().cycleId()).objectives().stream()
                .filter(o->o.objectiveId().equals(context.locked().plan().objectiveId())).flatMap(o->o.criteria().stream()).anyMatch(i->i.indicatorId().equals(id));
        if(!belongs) throw fail(BAD_REQUEST,"INDICATOR_PLAN_MISMATCH","El indicador no pertenece al objetivo del plan");
        return indicator;
    }

    /** Evita que la persistencia redondee una fecha distinta de la que fue validada. */
    private static void precise(Instant value) {
        if(value.getNano()%1000!=0) throw fail(BAD_REQUEST,"DATE_PRECISION_INVALID","Las fechas admiten precisión máxima de microsegundos");
    }

    /** Convierte instantes sin usar el reloj de la JVM ni interpretar zonas locales. */
    private static Timestamp stamp(Instant value) { return Timestamp.from(value); }
}
