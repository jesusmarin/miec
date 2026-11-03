package com.miec.orchestration;

import com.miec.domain.EventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.Timestamp;
import java.util.*;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Persistencia transaccional interna con el orden de bloqueo sistema y ciclo del MVP. */
@Service
class MeasurementWorkflow {
    private final InterventionSupport store;
    private final RoundScope scope;
    private final InterventionWorkflow intervention;
    private final MeasurementQueries queries;
    /** Comparte coordinación y validación con el ciclo existente. */
    MeasurementWorkflow(InterventionSupport store,InterventionWorkflow intervention,MeasurementQueries queries,RoundScope scope) { this.scope=scope;this.store=store; this.intervention=intervention; this.queries=queries; }
    /** Crea objetivo e indicadores con E01 a E02 en la misma transacción. */
    @Transactional(timeout=10)
    public InterventionViews.Preparation initialize(UUID cycleId,Initialize command,String actor) {
        store.lifecycle.validate(command);
        var names=new HashSet<String>();
        for(var criterion:command.criteria()) if(!names.add(criterion.name().trim().toLowerCase(Locale.ROOT)))
            throw fail(BAD_REQUEST,"DUPLICATE_INDICATOR","Los nombres de indicadores deben ser únicos");
        var result=intervention.initialize(cycleId,new InterventionCommands.Initialize(command.expectedVersion(),command.objective(),command.successCriteria(),command.reason()),actor);
        for(var criterion:command.criteria()) store.jdbc.update("""
                INSERT INTO indicator(indicator_id,objective_id,name,unit,comparison_operator,acceptance_threshold,version_id)
                VALUES (?,?,?,?,?,?,'MVP-MEASURE-01')
                """,UUID.randomUUID(),result.resourceId(),criterion.name().trim(),criterion.unit().trim(),criterion.operator().name(),criterion.target());
        return result;
    }
    /** Delega una lectura coherente reutilizable por la revisión del plan. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Context context(UUID cycleId) { return queries.context(cycleId); }
    /** Registra una medición inmutable y evidencia no verificada sin abandonar E02. */
    @Transactional(timeout=10)
    public InterventionViews.Preparation measure(UUID cycleId,Measure command,String actor) {
        store.lifecycle.validate(command);
        var cycle=store.lock(cycleId); store.expect(cycle,command.expectedVersion(),"E02_OBSERVANDO");
        var indicator=queries.indicator(cycleId,command.indicatorId());
        MeasurementQueries.requireUnit(indicator,command.unit());
        var now=store.lifecycle.now();
        scope.requireNewObservation(cycleId,command.measuredAt());
        if(command.measuredAt().isAfter(now)) throw fail(BAD_REQUEST,"FUTURE_MEASUREMENT","La fecha del hecho no puede superar la recepción");
        UUID baseline=UUID.randomUUID(),evidence=UUID.randomUUID();
        store.jdbc.update("""
                INSERT INTO evidence(evidence_id,cycle_id,source_type,source_reference,description,captured_at,provenance,quality_status,limitations,created_at)
                VALUES (?,?,'HUMAN_REPORT',?,?,?,?,'UNVERIFIED','Medición manual; no acredita éxito',?)
                """,evidence,cycleId,command.source().trim(),"baseline:"+baseline,Timestamp.from(command.measuredAt()),actor,Timestamp.from(now));
        store.jdbc.update("""
                INSERT INTO baseline(baseline_id,cycle_id,indicator_id,observed_value,quality_status,measured_at,version_id,created_at,source_reference,recorded_by,unit,evidence_id)
                VALUES (?,?,?,?,'UNVERIFIED',?,'MVP-MEASURE-01',?,?,?,?,?)
                """,baseline,cycleId,command.indicatorId(),command.value(),Timestamp.from(command.measuredAt()),Timestamp.from(now),command.source().trim(),actor,indicator.unit(),evidence);
        scope.baseline(cycleId,baseline,command.indicatorId());
        store.change(cycle,cycle.state(),EventType.OBSERVATION_RECORDED,actor,command.reason().trim(),"baseline:"+baseline);
        return new InterventionViews.Preparation(store.lifecycle.getCycle(cycleId),baseline);
    }

    /** Cierra un conjunto completo y fija sus UUID en una evidencia auditable para diagnóstico. */
    @Transactional(timeout=10)
    public InterventionViews.Preparation complete(UUID cycleId,Complete command,String actor) {
        store.lifecycle.validate(command); var cycle=store.lock(cycleId);
        store.expect(cycle,command.expectedVersion(),"E02_OBSERVANDO");
        var context=context(cycleId);
        var indicators=context.objectives().stream().flatMap(o->o.criteria().stream()).toList();
        if(indicators.isEmpty()||indicators.stream().anyMatch(i->context.baselines().stream().noneMatch(b->i.indicatorId().equals(b.indicatorId())&&b.evidenceId()!=null)))
            throw fail(CONFLICT,"INCOMPLETE_BASELINE","Cada indicador requiere al menos una medición");
        UUID evidence=UUID.randomUUID(); var now=Timestamp.from(store.lifecycle.now());
        String references=context.baselines().stream().map(b->b.baselineId().toString()).collect(java.util.stream.Collectors.joining(","));
        store.jdbc.update("""
                INSERT INTO evidence(evidence_id,cycle_id,source_type,source_reference,description,captured_at,provenance,quality_status,limitations,created_at)
                VALUES (?,?,'HUMAN_REPORT','MVP-MEASURE-CLOSURE',?,?,?,'UNVERIFIED','Conjunto de reportes manuales; sin evaluación automática',?)
                """,evidence,cycleId,"baseline-set:"+references,now,actor,now);
        scope.observation(cycleId,evidence);
        store.change(cycle,"E03_DIAGNOSTICANDO",EventType.OBSERVATION_RECORDED,actor,command.reason().trim(),"evidence:"+evidence);
        return new InterventionViews.Preparation(store.lifecycle.getCycle(cycleId),evidence);
    }

}
