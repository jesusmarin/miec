package com.miec.orchestration;

import org.springframework.stereotype.Component;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static com.miec.orchestration.InterventionViews.*;
import static com.miec.orchestration.InterventionSupport.fail;
import static com.miec.orchestration.LifecycleException.Kind.*;

/** Consulta solicitudes y su decisión humana sin confundir aprobación con activación del plan. */
@Component
class InterventionAuthorizations {
    private final InterventionSupport store;
    /** Reutiliza la conexión de la transacción del flujo. */
    InterventionAuthorizations(InterventionSupport store) { this.store=store; }

    /** Recupera una solicitud y conserva la decisión aunque su estado vigente sea revocado. */
    Authorization get(UUID id) {
        if(id==null) throw fail(BAD_REQUEST,"INVALID_ID","La autorización es obligatoria");
        var rows=store.jdbc.query("""
                SELECT a.*,d.decision,d.decided_by,d.reason,d.decided_at
                FROM authorizations a LEFT JOIN authorization_decision d ON d.authorization_id=a.authorization_id
                WHERE a.authorization_id=?
                """,this::map,id);
        if(rows.isEmpty()) throw fail(NOT_FOUND,"AUTHORIZATION_NOT_FOUND","La autorización no existe");
        return rows.getFirst();
    }

    /** Mantiene ausencia de datos históricos como null en lugar de atribuir decisiones ficticias. */
    private Authorization map(ResultSet rs,int row) throws SQLException {
        String decision=rs.getString("decision");
        return new Authorization(rs.getObject("authorization_id",UUID.class),rs.getObject("plan_id",UUID.class),
                rs.getObject("action_id",UUID.class),rs.getString("scope"),rs.getString("status"),rs.getString("requested_by"),
                rs.getString("approved_by"),instant(rs.getTimestamp("issued_at")),instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("revoked_at")),rs.getLong("version"),decision==null?null:
                new Decision(decision,rs.getString("decided_by"),rs.getString("reason"),instant(rs.getTimestamp("decided_at"))));
    }

    /** Convierte fechas opcionales sin sustituirlas por la hora actual. */
    private static Instant instant(Timestamp time) { return time==null?null:time.toInstant(); }
}
