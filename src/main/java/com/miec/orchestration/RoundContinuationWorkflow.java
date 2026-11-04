package com.miec.orchestration;

import com.miec.domain.EventType;
import com.miec.decision.DecisionAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.util.*;
import static com.miec.orchestration.RoundContracts.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** ROUND-CONTINUITY-V1: resolución administrativa auditable, sin permisos ni efectos externos. */
@Service
class RoundContinuationWorkflow {
    static final String POLICY="ROUND-CONTINUITY-V1";
    private final InterventionSupport store;
    private final InterventionPlans plans;
    private final RoundScope scope;
    private final RoundWorkflow rounds;
    private final CycleCompletionWorkflow completion;
    private final MeasurementQueries measurements;
    /** Comparte el orden de bloqueo de ciclo/plan y las comprobaciones técnicas del cierre. */
    RoundContinuationWorkflow(InterventionSupport store,InterventionPlans plans,RoundScope scope,RoundWorkflow rounds,
                               CycleCompletionWorkflow completion,MeasurementQueries measurements){
        this.store=store;this.plans=plans;this.scope=scope;this.rounds=rounds;this.completion=completion;this.measurements=measurements;
    }
    /** Congela una solicitud; una nueva modificación del ciclo deja obsoleta la aprobación pendiente. */
    @Transactional(timeout=10)
    public Continuation request(UUID cycle,UUID roundId,RequestContinuation command,String actor){
        store.lifecycle.validate(command);actor=LifecycleService.actor(actor);
        var locked=store.lock(cycle);store.expect(locked,command.expectedCycleVersion(),"E11_CONTINUANDO");
        var round=eligible(cycle,roundId,command.expectedRoundVersion());
        validateIntent(round,command);validateInputs(cycle,round,command);
        UUID id=UUID.randomUUID();UUID event=store.change(locked,"E11_CONTINUANDO",EventType.ROUND_CONTINUATION_REQUESTED,actor,command.reason().trim(),"round-continuation:"+id);
        store.jdbc.update(store.sql.json("""
            INSERT INTO round_continuation(request_id,cycle_id,round_id,decision_id,learning_id,mode,request_status,version,cycle_version,round_version,
                requested_by,reason,policy_version,input_snapshot,requested_at,event_id)
            VALUES (?,?,?,?,?,?,'PENDING',0,?,?,?,?,?,? FORMAT JSON,?,?)
            """),id,cycle,roundId,command.decisionId(),command.learningId(),command.mode().name(),command.expectedCycleVersion()+1,round.version(),actor,
            command.reason().trim(),POLICY,plans.json.writeValueAsString(command),time(event),event);
        return get(cycle,id);
    }
    /** Revalida hechos bajo lock; cierre de ronda, sucesor y F01 se confirman o revierten juntos. */
    @Transactional(timeout=10)
    public Context approve(UUID cycle,UUID requestId,ApproveContinuation command,String actor){
        store.lifecycle.validate(command);actor=LifecycleService.actor(actor);
        var locked=store.lock(cycle);store.expect(locked,command.expectedCycleVersion(),"E11_CONTINUANDO");
        store.jdbc.query("SELECT request_id FROM round_continuation WHERE request_id=? AND cycle_id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),requestId,cycle);
        var request=get(cycle,requestId);
        if(!"PENDING".equals(request.status())||request.version()!=command.expectedRequestVersion())throw fail(CONFLICT,"CONTINUATION_NOT_PENDING","La solicitud cambió o ya fue aprobada");
        if(request.cycleVersion()!=command.expectedCycleVersion()||request.roundVersion()!=command.expectedRoundVersion())
            throw fail(CONFLICT,"CONTINUATION_STALE","Se requiere una solicitud con el contexto vigente");
        var round=eligible(cycle,request.roundId(),command.expectedRoundVersion());
        if(actor.equalsIgnoreCase(request.requestedBy())||actor.equalsIgnoreCase(round.registeredBy())||actor.equalsIgnoreCase(plans.get(round.planId(),false).createdBy()))
            throw fail(FORBIDDEN,"SELF_CONTINUATION_FORBIDDEN","La aprobación exige una identidad distinta del autor");
        validateIntent(round,request.input());validateInputs(cycle,round,request.input());
        UUID next=UUID.randomUUID();String target=request.input().mode()==ContinuationMode.COLLECT_EVIDENCE?"E02_OBSERVANDO":"E04_PLANIFICANDO";
        UUID event=store.change(locked,target,EventType.ROUND_CONTINUED,actor,command.reason().trim(),"round:"+next);var at=time(event);
        if(store.jdbc.update("UPDATE cycle_round SET round_state='CONCLUDED',active_cycle_id=NULL,concluded_at=?,version=version+1 WHERE round_id=? AND version=? AND round_state='ACTIVE'",at,round.roundId(),round.version())!=1)
            throw fail(CONFLICT,"ROUND_VERSION_CONFLICT","La ronda cambió");
        store.jdbc.update("INSERT INTO cycle_round VALUES (?,?,?,?,'ACTIVE',?,0,?,?,?,NULL,?)",next,cycle,round.number()+1,round.roundId(),cycle,request.requestedBy(),request.reason(),at,event);
        if(request.input().mode()==ContinuationMode.ADJUST_PLAN)installPlanningInputs(cycle,request.input(),request.requestedBy(),at);
        if(store.jdbc.update("""
            UPDATE round_continuation SET request_status='APPROVED',version=version+1,approved_by=?,approval_reason=?,approved_at=?,approval_event_id=?,new_round_id=?
            WHERE request_id=? AND version=? AND request_status='PENDING'
            """,actor,command.reason().trim(),at,event,next,requestId,command.expectedRequestVersion())!=1)
            throw fail(CONFLICT,"CONTINUATION_NOT_PENDING","La solicitud cambió");
        return rounds.view(cycle);
    }
    /** Consulta el recibo y su entrada original; no renueva ni aprueba solicitudes. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Continuation get(UUID cycle,UUID id){
        store.lifecycle.getCycle(cycle);
        return store.jdbc.query("SELECT * FROM round_continuation WHERE cycle_id=? AND request_id=?",(rs,row)->new Continuation(id,cycle,rs.getObject("round_id",UUID.class),
            rs.getString("request_status"),rs.getLong("version"),rs.getLong("cycle_version"),rs.getLong("round_version"),rs.getString("requested_by"),rs.getString("reason"),
            rs.getString("policy_version"),rs.getTimestamp("requested_at").toInstant(),plans.json.readValue(rs.getString("input_snapshot"),RequestContinuation.class),
            rs.getString("approved_by"),rs.getString("approval_reason"),rs.getTimestamp("approved_at")==null?null:rs.getTimestamp("approved_at").toInstant(),rs.getObject("new_round_id",UUID.class)),cycle,id)
            .stream().findFirst().orElseThrow(()->fail(NOT_FOUND,"CONTINUATION_NOT_FOUND","La solicitud no pertenece al ciclo"));
    }
    /** Ronda activa concluida operativamente y sin resultados técnicos inciertos en todo el ciclo. */
    private Round eligible(UUID cycle,UUID id,long version){
        scope.requireRound(cycle,id);
        var round=rounds.view(cycle).rounds().stream().filter(r->r.roundId().equals(id)).findFirst().orElseThrow();
        if(!id.equals(scope.current(cycle))||!"ACTIVE".equals(round.state()))throw fail(CONFLICT,"ROUND_NOT_ACTIVE","La ronda no está activa");
        if(round.version()!=version)throw fail(CONFLICT,"ROUND_VERSION_CONFLICT","La ronda cambió");
        if(round.conclusion()==null||round.snapshot()==null)throw fail(CONFLICT,"PLAN_COMPLETION_REQUIRED","Se requiere concluir operativamente el plan");
        var plan=plans.lock(round.planId()).plan();
        if(!plan.fingerprint().equals(round.planFingerprint()))throw fail(CONFLICT,"PLAN_CHANGED","El contenido del plan cambió");
        completion.requireHealthy(plan.planId(),cycle);return round;
    }
    /** Conserva F02 original y registra por separado cualquier resolución humana de continuidad. */
    private void validateIntent(Round round,RequestContinuation input){
        var snapshot=round.snapshot();
        if(!snapshot.decision().decisionId().equals(input.decisionId())||!snapshot.learning().learningId().equals(input.learningId()))
            throw fail(BAD_REQUEST,"CONTINUATION_CONTEXT_MISMATCH","Decisión y aprendizaje deben corresponder a la ronda");
        if(snapshot.decision().selectedAction()==DecisionAction.CLOSE_CYCLE||snapshot.evaluation().report().result().classification().blocksContinuation())
            throw fail(CONFLICT,"CONTINUATION_INADMISSIBLE","La decisión o el riesgo no permiten continuar bajo esta política");
    }
    /** E04 exige muestras nuevas y diagnóstico revisable; E02 no admite entradas ignoradas. */
    private void validateInputs(UUID cycle,Round round,RequestContinuation command){
        if(command.mode()==ContinuationMode.COLLECT_EVIDENCE){
            if(!command.baselines().isEmpty()||command.diagnosis()!=null)throw fail(BAD_REQUEST,"UNEXPECTED_CONTINUATION_INPUT","La observación nueva se registra en E02");return;
        }
        if(command.diagnosis()==null||command.diagnosis().isBlank())throw fail(BAD_REQUEST,"CONTINUATION_DIAGNOSIS_REQUIRED","La planificación directa requiere diagnóstico nuevo");
        var plan=plans.get(round.planId(),false);var criteria=measurements.context(cycle).objectives().stream().filter(o->o.objectiveId().equals(plan.objectiveId())).flatMap(o->o.criteria().stream()).toList();
        var ids=new HashSet<UUID>();
        for(var sample:command.baselines()){
            if(!ids.add(sample.indicatorId()))throw fail(BAD_REQUEST,"DUPLICATE_INDICATOR","Solo se admite una muestra por indicador");
            var criterion=criteria.stream().filter(i->i.indicatorId().equals(sample.indicatorId())).findFirst().orElseThrow(()->fail(BAD_REQUEST,"INVALID_INDICATOR","Indicador ajeno al objetivo"));
            MeasurementQueries.requireUnit(criterion,sample.unit());
            if(sample.measuredAt().isBefore(round.snapshot().evaluation().inputs().finalizedAt()))throw fail(BAD_REQUEST,"BASELINE_PRECEDES_PREVIOUS_EXECUTION","La nueva observacion debe ser posterior a la intervencion anterior");
            if(sample.measuredAt().getNano()%1000!=0||sample.measuredAt().isAfter(store.lifecycle.now()))throw fail(BAD_REQUEST,"CONTINUATION_DATE_INVALID","Fecha futura o precisión incompatible");
        }
        if(criteria.isEmpty()||!ids.equals(new HashSet<>(criteria.stream().map(MeasurementContracts.Indicator::indicatorId).toList())))
            throw fail(BAD_REQUEST,"INCOMPLETE_BASELINE","La planificación requiere una muestra nueva por criterio");
    }
    /** Instala las fuentes aprobadas en la ronda recién abierta, sin copiar evidencias ni permisos anteriores. */
    private void installPlanningInputs(UUID cycle,RequestContinuation input,String author,Timestamp received){
        for(var sample:input.baselines()){
            UUID evidence=UUID.randomUUID(),baseline=UUID.randomUUID();
            store.jdbc.update("""
                INSERT INTO evidence(evidence_id,cycle_id,source_type,source_reference,description,captured_at,provenance,quality_status,limitations,created_at)
                VALUES (?,?,'HUMAN_REPORT',?,?,?,?,'UNVERIFIED','Nueva línea base humana de continuidad',?)
                """,evidence,cycle,sample.source().trim(),"baseline:"+baseline,Timestamp.from(sample.measuredAt()),author,received);
            store.jdbc.update("""
                INSERT INTO baseline(baseline_id,cycle_id,indicator_id,observed_value,quality_status,measured_at,version_id,created_at,source_reference,recorded_by,unit,evidence_id)
                VALUES (?,?,?,?,'UNVERIFIED',?,'MVP-MEASURE-01',?,?,?,?,?)
                """,baseline,cycle,sample.indicatorId(),sample.value(),Timestamp.from(sample.measuredAt()),received,sample.source().trim(),author,sample.unit().trim(),evidence);
            scope.baseline(cycle,baseline,sample.indicatorId());
        }
        UUID evidence=UUID.randomUUID(),diagnosis=UUID.randomUUID();
        store.jdbc.update("""
            INSERT INTO evidence(evidence_id,cycle_id,source_type,source_reference,description,captured_at,provenance,quality_status,limitations,created_at)
            VALUES (?,?,'HUMAN_REPORT','MVP-MEASURE-CLOSURE',?,?,?,'UNVERIFIED','Diagnóstico humano sin suficiencia automática',?)
            """,evidence,cycle,input.diagnosis().trim(),received,author,received);
        scope.observation(cycle,evidence);
        store.jdbc.update("INSERT INTO cycle_diagnosis VALUES (?,?,?,?,?,?)",diagnosis,cycle,evidence,input.diagnosis().trim(),author,received);scope.diagnosis(cycle,diagnosis);
    }
    /** Usa la fecha persistida del evento como recepción y resolución. */
    private Timestamp time(UUID event){return store.jdbc.queryForObject("SELECT occurred_at FROM cycle_event WHERE event_id=?",(rs,row)->rs.getTimestamp(1),event);}
}
