package com.miec.orchestration;

import com.miec.domain.Cycle;
import com.miec.domain.EventType;
import com.miec.state.CycleState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.util.UUID;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Infraestructura interna; todos los comandos bloquean sistema, ciclo, plan y autorización en ese orden. */
@Component
class InterventionSupport {
    final JdbcTemplate jdbc;
    final LifecycleService lifecycle;
    final DatabaseSql sql;

    /** Reutiliza las reglas de datos y el reloj del MVP-01. */
    InterventionSupport(JdbcTemplate jdbc, LifecycleService lifecycle,DatabaseSql sql) { this.jdbc=jdbc; this.lifecycle=lifecycle;this.sql=sql; }

    /** Bloquea un ciclo existente y su sistema activo; el llamador ya mantiene una transacción. */
    LifecycleViews.CycleView lock(UUID id) {
        var before=lifecycle.getCycle(id);
        lifecycle.lockActiveSystem(before.systemId());
        var rows=jdbc.query("SELECT * FROM cycle WHERE cycle_id=? FOR UPDATE",lifecycle::cycle,id);
        if(rows.isEmpty()) throw fail(NOT_FOUND,"CYCLE_NOT_FOUND","El ciclo no existe");
        var current=rows.getFirst();
        if(!before.systemId().equals(current.systemId())) throw fail(CONFLICT,"CYCLE_CHANGED","Cambió el sistema del ciclo");
        return current;
    }

    /** Exige versión y etapa, sin permitir al cliente seleccionar el siguiente estado. */
    void expect(LifecycleViews.CycleView cycle,long version,String state) {
        if(cycle.version()!=version) throw fail(CONFLICT,"VERSION_CONFLICT","La versión del ciclo cambió");
        if(!state.equals(cycle.state())) throw fail(CONFLICT,"TRANSITION_NOT_ALLOWED","La etapa del ciclo no admite este comando");
    }

    /** Cambia estado y versión con su evento; solo registra transición cuando el estado cambia. */
    UUID change(LifecycleViews.CycleView cycle,String target,EventType type,String actor,String reason,String reference) {
        if(!cycle.state().equals(target)&&!Cycle.isValidTransition(CycleState.valueOf(cycle.state()),CycleState.valueOf(target))) {
            throw fail(CONFLICT,"TRANSITION_NOT_ALLOWED","Transición no admitida por F01");
        }
        var time=lifecycle.now();
        if(jdbc.update("UPDATE cycle SET state=?,version=version+1,updated_at=? WHERE cycle_id=? AND version=? AND closed_at IS NULL",
                target,Timestamp.from(time),cycle.cycleId(),cycle.version())!=1) throw fail(CONFLICT,"VERSION_CONFLICT","El ciclo cambió");
        UUID event=lifecycle.event(cycle.cycleId(),type,LifecycleService.actor(actor),reason,time);
        if(reference!=null) jdbc.update("UPDATE cycle_event SET payload_reference=? WHERE event_id=?",reference,event);
        if(!cycle.state().equals(target)) jdbc.update("""
                INSERT INTO state_transition(transition_id,cycle_id,from_state,to_state,trigger_event_id,transition_rule_id,transition_status,occurred_at,created_at)
                VALUES (?,?,?,?,?,?,'COMPLETED',?,?)
                """,UUID.randomUUID(),cycle.cycleId(),cycle.state(),target,event,"MIEC-F01:"+cycle.state()+"->"+target,Timestamp.from(time),Timestamp.from(time));
        return event;
    }

    /** Rechazo seguro que no expone valores recibidos ni SQL. */
    static LifecycleException fail(LifecycleException.Kind kind,String code,String message) { return new LifecycleException(kind,code,message); }
}
