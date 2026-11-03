package com.miec.orchestration;

import com.miec.domain.EventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.util.*;
import static com.miec.orchestration.RoundContracts.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Transacciones breves para registrar contexto y concluir planes, sin llamadas externas. */
@Service
class RoundWorkflow {
    private final RoundScope scope;
    private final InterventionSupport store;
    private final InterventionPlans plans;
    private final CycleCompletionWorkflow completion;
    private final ClosedSetEvaluationStore evaluations;
    private final PlanActivityQuery activity;
    /** Comparte bloqueos sistema/ciclo/plan y comprobación técnica con el cierre. */
    RoundWorkflow(InterventionSupport store,InterventionPlans plans,CycleCompletionWorkflow completion,
                  ClosedSetEvaluationStore evaluations,PlanActivityQuery activity,RoundScope scope){
        this.scope=scope;
        this.store=store;this.plans=plans;this.completion=completion;this.evaluations=evaluations;this.activity=activity;
    }
    /** Registra solo la ronda inicial explícita, sin asignar históricos ni abrir continuidad. */
    @Transactional(timeout=10)
    public Context register(UUID cycleId,RegisterInitial command,String actor){
        store.lifecycle.validate(command);actor=LifecycleService.actor(actor);
        var cycle=store.lock(cycleId);store.expect(cycle,command.expectedCycleVersion(),"E11_CONTINUANDO");
        if(!rounds(cycleId).isEmpty())throw fail(CONFLICT,"ROUND_EXISTS","El ciclo ya tiene una ronda explícita");
        var view=completion.view(cycleId);
        if(view.admission()==null||view.decision()==null||view.learning()==null)
            throw fail(CONFLICT,"ROUND_CONTEXT_REQUIRED","Se requiere evaluación, decisión y aprendizaje operativos");
        var saved=evaluations.get(view.admission().evaluationId());var input=saved.inputs();var locked=plans.lock(input.planId());
        completion.requireHealthy(input.planId(),cycleId);
        UUID id=UUID.randomUUID();UUID event=store.change(cycle,"E11_CONTINUANDO",EventType.ROUND_REGISTERED,actor,command.reason().trim(),"round:"+id);
        var time=eventTime(event);
        store.jdbc.update("INSERT INTO cycle_round VALUES (?,?,1,NULL,'ACTIVE',?,0,?,?,?,NULL,?)",id,cycleId,cycleId,actor,command.reason().trim(),time,event);
        scope.initialInputs(id,locked.plan(),saved);
        scope.capture(id,locked.plan(),saved,view);
        return view(cycleId);
    }
    /** Conclusión atómica independiente del estado APPROVED y de la calidad de evidencia. */
    @Transactional(timeout=10)
    public Context conclude(UUID cycleId,UUID roundId,CompletePlan command,String actor){
        store.lifecycle.validate(command);actor=LifecycleService.actor(actor);
        var cycle=store.lock(cycleId);store.expect(cycle,command.expectedCycleVersion(),"E11_CONTINUANDO");
        store.jdbc.query("SELECT round_id FROM cycle_round WHERE round_id=? AND cycle_id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),roundId,cycleId);
        var round=rounds(cycleId).stream().filter(r->r.roundId().equals(roundId)).findFirst()
            .orElseThrow(()->fail(NOT_FOUND,"ROUND_NOT_FOUND","La ronda no pertenece al ciclo"));
        if(round.version()!=command.expectedRoundVersion())throw fail(CONFLICT,"ROUND_VERSION_CONFLICT","La versión de ronda cambió");
        if(!"ACTIVE".equals(round.state())||round.conclusion()!=null)throw fail(CONFLICT,"PLAN_CONCLUDED","La ronda ya tiene conclusión");
        var locked=plans.lock(round.planId());
        if(actor.equalsIgnoreCase(locked.plan().createdBy())||actor.equalsIgnoreCase(round.registeredBy()))
            throw fail(FORBIDDEN,"SELF_COMPLETION_FORBIDDEN","La conclusión requiere una identidad distinta del autor");
        if(!locked.plan().fingerprint().equals(round.planFingerprint()))throw fail(CONFLICT,"PLAN_CHANGED","La definición del plan cambió");
        completion.requireHealthy(round.planId(),cycleId);
        UUID event=store.change(cycle,"E11_CONTINUANDO",EventType.PLAN_OPERATIONALLY_COMPLETED,actor,command.reason().trim(),"plan-completion:"+round.planId());
        store.jdbc.update("INSERT INTO plan_completion VALUES (?,?,?,?,?,?,?,'PLAN-COMPLETION-V1',?,?)",round.planId(),roundId,cycleId,
            command.expectedCycleVersion(),command.expectedRoundVersion(),actor,command.reason().trim(),event,eventTime(event));
        if(store.jdbc.update("UPDATE cycle_round SET version=version+1 WHERE round_id=? AND version=?",roundId,command.expectedRoundVersion())!=1)
            throw fail(CONFLICT,"ROUND_VERSION_CONFLICT","La versión de ronda cambió");
        return view(cycleId);
    }
    /** Lee la historia congelada y la actividad actual bajo una instantánea consistente. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Context view(UUID cycle){return new Context(store.lifecycle.getCycle(cycle),rounds(cycle),activity.active(cycle));}
    /** Deserializa el snapshot persistido, sin invocar políticas ni usar el indicador actual. */
    private List<Round> rounds(UUID cycle){return store.jdbc.query("""
        SELECT r.*,b.plan_id,c.plan_fingerprint,c.context_snapshot FROM cycle_round r
        LEFT JOIN round_context c ON c.round_id=r.round_id LEFT JOIN round_plan_binding b ON b.round_id=r.round_id WHERE r.cycle_id=? ORDER BY r.round_number
        """,(rs,row)->new Round(rs.getObject("round_id",UUID.class),cycle,rs.getInt("round_number"),rs.getObject("previous_round_id",UUID.class),
            rs.getString("round_state"),rs.getLong("version"),rs.getString("registered_by"),rs.getString("reason"),rs.getTimestamp("registered_at").toInstant(),
            rs.getTimestamp("concluded_at")==null?null:rs.getTimestamp("concluded_at").toInstant(),
            rs.getObject("plan_id",UUID.class),rs.getString("plan_fingerprint"),rs.getString("context_snapshot")==null?null:plans.json.readValue(rs.getString("context_snapshot"),Snapshot.class),conclusion(rs.getObject("plan_id",UUID.class))),cycle);}
    /** Recupera únicamente un acta existente. */
    private Conclusion conclusion(UUID plan){return store.jdbc.query("SELECT * FROM plan_completion WHERE plan_id=?",(rs,row)->new Conclusion(rs.getString("policy_version"),
        rs.getString("recorded_by"),rs.getString("reason"),rs.getObject("event_id",UUID.class),rs.getTimestamp("completed_at").toInstant()),plan).stream().findFirst().orElse(null);}
    /** Usa la misma fecha de base que el evento de auditoría. */
    private Timestamp eventTime(UUID event){return store.jdbc.queryForObject("SELECT occurred_at FROM cycle_event WHERE event_id=?",(rs,row)->rs.getTimestamp(1),event);}
}
