package com.miec.orchestration;

import com.miec.action.PlannedActionJsonV01;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import static com.miec.orchestration.InterventionViews.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Lee la definición persistida y calcula su identidad; estado y revisión no alteran el contenido autorizado. */
@Component
class InterventionPlans {
    final InterventionSupport store;
    final JsonMapper json;
    private final PlannedActionJsonV01 parser;

    /** Reutiliza el contrato v01 de acciones sin introducir otro formato de ejecución. */
    InterventionPlans(InterventionSupport store,JsonMapper json,PlannedActionJsonV01 parser) {
        this.store=store; this.json=json; this.parser=parser;
    }

    /** Obtiene un plan; se puede solicitar el bloqueo solo después de bloquear su ciclo. */
    Plan get(UUID id,boolean lock) {
        if(id==null) throw fail(BAD_REQUEST,"INVALID_ID","El plan es obligatorio");
        var rows=store.jdbc.query("SELECT * FROM intervention_plan WHERE plan_id=?"+(lock?" FOR UPDATE":""),this::map,id);
        if(rows.isEmpty()) throw fail(NOT_FOUND,"PLAN_NOT_FOUND","El plan no existe");
        return rows.getFirst();
    }

    /** Contexto bloqueado en el orden sistema, ciclo y plan. */
    record Locked(Plan plan,LifecycleViews.CycleView cycle) { }

    /** Bloquea el agregado y rechaza operaciones de escritura sobre planes históricos no gestionados por MVP-02. */
    Locked lock(UUID id) {
        var before=get(id,false);
        var cycle=store.lock(before.cycleId());
        var current=get(id,true);
        if(!cycle.cycleId().equals(current.cycleId())) throw fail(CONFLICT,"PLAN_CHANGED","El plan cambió de ciclo");
        if(current.createdBy()==null||current.diagnosisId()==null) throw fail(CONFLICT,"LEGACY_PLAN_READ_ONLY","El plan histórico es de solo consulta en este flujo");
        return new Locked(current,cycle);
    }

    /** Reconstruye el plan y valida las identidades de acciones antes de utilizarlo. */
    private Plan map(ResultSet rs,int row) throws SQLException {
        UUID id=rs.getObject("plan_id",UUID.class),cycle=rs.getObject("cycle_id",UUID.class);
        String document=rs.getString("actions");
        try { parser.parse(id,cycle,document); }
        catch(IllegalArgumentException invalid) { throw fail(CONFLICT,"INVALID_PLAN","El plan no tiene un contrato de acciones válido"); }
        var actions=new ArrayList<PlanAction>();
        for(var item:json.readTree(document).path("actions")) {
            var description=item.get("description");
            actions.add(new PlanAction(UUID.fromString(item.path("actionId").asString()),item.path("actionType").asString(),
                    description==null||description.isNull()?null:description.asString(),
                    item.path("execution").isMissingNode()||item.path("execution").isNull()?null:
                            json.treeToValue(item.path("execution"),com.miec.execution.ExecutableActions.Definition.class)));
        }
        String objectives=rs.getString("objective_ids");
        var objective=objectives==null?null:json.readTree(objectives);
        UUID objectiveId=objective!=null&&objective.isArray()&&!objective.isEmpty()?UUID.fromString(objective.get(0).asString()):null;
        String hash=fingerprint(rs);
        return new Plan(id,cycle,objectiveId,rs.getObject("diagnosis_id",UUID.class),rs.getString("created_by"),
                rs.getString("status"),rs.getLong("revision"),actions,rs.getString("expected_outcomes"),
                rs.getString("rollback_strategy"),hash,rs.getTimestamp("created_at").toInstant());
    }

    /** Huella versionada de todo contenido relevante, con distinción explícita entre null y texto vacío. */
    private String fingerprint(ResultSet rs) throws SQLException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            var fields=new ArrayList<String>(); fields.add("MVP-PLAN-01");
            for(String column:new String[]{"plan_id","cycle_id","objective_ids","hypothesis_ids","actions",
                    "expected_outcomes","risk_assessment_id","rollback_strategy","authorization_requirements",
                    "version_id","created_by","diagnosis_id"}) fields.add(rs.getString(column));
            for(String field:fields) {
                byte[] bytes=field==null?new byte[0]:field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(field==null?-1:bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 no disponible",impossible); }
    }
}
