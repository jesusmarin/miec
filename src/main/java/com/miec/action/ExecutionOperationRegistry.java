package com.miec.action;

import com.miec.authorization.AuthorizationPersistenceService;
import com.miec.authorization.AuthorizationValidityPolicy.AuthorizationUseRequest;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

/** Vincula una operación lógica a destinatario y solicitud inmutables entre intentos. */
@Service
public class ExecutionOperationRegistry {
    /** Identidad persistida que debe usar el destinatario para deduplicar. */
    public record Operation(UUID key,UUID planId,UUID actionId,String destination,String fingerprint,ExecutionRequest request) { }
    private final ExecutionRecoveryAccess access;
    private final AuthorizationPersistenceService authorizations;
    private final PlannedActionJsonV01 json;
    private final PlannedActionPolicy policy;

    /** Configura las validaciones previas a reservar una clave de operación. */
    public ExecutionOperationRegistry(ExecutionRecoveryAccess access,AuthorizationPersistenceService authorizations,
            PlannedActionJsonV01 json,PlannedActionPolicy policy) {
        this.access=access;this.authorizations=authorizations;this.json=json;this.policy=policy;
    }

    /**
     * Reserva o recupera una clave estable bajo bloqueo del plan. MIEC calcula
     * el SHA-256 del snapshot inmutable que entregará al adaptador.
     * Un cambio de solicitud o destino requiere otra acción, no reutilizar su clave.
     */
    public Operation prepare(AuthorizedActionExecutionService.ExecutionCommand command,ExecutionRequest request) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(request);
        String target=request.destinationId();
        String hash=request.fingerprint();
        return Objects.requireNonNull(access.transaction.execute(tx->{
            var plans=access.jdbc.query("SELECT plan_id,cycle_id,actions FROM intervention_plan WHERE plan_id=? FOR UPDATE",
                    (rs,row)->json.parse(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3)),command.planId());
            if(plans.isEmpty()||!policy.evaluate(plans.getFirst(),new PlannedActionPolicy.ActionRequest(
                    command.planId(),command.cycleId(),command.actionId(),command.actionType())).allowed()) {
                throw ExecutionRecoveryAccess.denied("Acción no planificada");
            }
            var auth=access.jdbc.query("SELECT authorization_id FROM authorizations WHERE authorization_id=? FOR UPDATE",
                    (rs,row)->rs.getObject(1,UUID.class),command.authorizationId());
            if(auth.isEmpty()||!authorizations.evaluate(command.authorizationId(),new AuthorizationUseRequest(
                    command.planId(),command.actionId(),"EXECUTE_ACTION",access.activity.databaseNow())).allowed()) {
                throw ExecutionRecoveryAccess.denied("Autorización de ejecución inválida");
            }
            var existing=access.jdbc.query("SELECT * FROM execution_operation WHERE plan_id=? AND planned_action_id=?",
                    (rs,row)->new Operation(rs.getObject("operation_key",UUID.class),command.planId(),command.actionId(),
                            rs.getString("destination_id"),rs.getString("request_fingerprint"),readRequest(rs)),command.planId(),command.actionId());
            if(!existing.isEmpty()) {
                var old=existing.getFirst();
                if(old.request()==null || !old.request().equals(request) || !old.destination().equals(target)||!old.fingerprint().equals(hash)) {
                    throw ExecutionRecoveryAccess.denied("La operación ya tiene otro destino o contenido");
                }
                return old;
            }
            UUID key=operationKey(command.planId(),command.actionId());
            access.jdbc.update("INSERT INTO execution_operation(operation_key,plan_id,planned_action_id,destination_id,request_fingerprint,created_at,request_format,request_media_type,request_body) VALUES (?,?,?,?,?,?,'UTF8-V1',?,?)",
                    key,command.planId(),command.actionId(),target,hash,Timestamp.from(access.activity.databaseNow()),request.mediaType(),request.body());
            return new Operation(key,command.planId(),command.actionId(),target,hash,request);
        }));
    }

    /** Identidad lógica determinista compartida con coordinación; calcularla no reserva ni ejecuta nada. */
    public static UUID operationKey(UUID planId,UUID actionId) {
        return UUID.nameUUIDFromBytes(("MIEC:"+Objects.requireNonNull(planId)+":"+Objects.requireNonNull(actionId)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Consulta una operación existente sin reservar claves. Revalida el permiso
     * EXECUTE_ACTION del solicitante; la recuperación no invoca al destinatario.
     */
    public java.util.Optional<Operation> findAuthorized(AuthorizedActionExecutionService.ExecutionCommand command) {
        Objects.requireNonNull(command);
        return Objects.requireNonNull(access.transaction.execute(tx->{
            var plans=access.jdbc.query("SELECT plan_id FROM intervention_plan WHERE plan_id=? FOR UPDATE",
                    (rs,row)->rs.getObject(1,UUID.class),command.planId());
            if(plans.isEmpty()) throw ExecutionRecoveryAccess.denied("Plan inexistente");
            var auth=access.jdbc.query("SELECT authorization_id FROM authorizations WHERE authorization_id=? FOR UPDATE",
                    (rs,row)->rs.getObject(1,UUID.class),command.authorizationId());
            if(auth.isEmpty()||!authorizations.evaluate(command.authorizationId(),new AuthorizationUseRequest(
                    command.planId(),command.actionId(),"EXECUTE_ACTION",access.activity.databaseNow())).allowed()) {
                throw ExecutionRecoveryAccess.denied("Autorización de consulta inválida");
            }
            return access.jdbc.query("SELECT * FROM execution_operation WHERE plan_id=? AND planned_action_id=?",
                    (rs,row)->new Operation(rs.getObject("operation_key",UUID.class),command.planId(),command.actionId(),
                            rs.getString("destination_id"),rs.getString("request_fingerprint"),readRequest(rs)),command.planId(),command.actionId()).stream().findFirst();
        }));
    }
    /** Reconstruye el snapshot exacto; las solicitudes históricas ausentes permanecen desconocidas. */
    private static ExecutionRequest readRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        if(rs.getString("request_format")==null) return null;
        return new ExecutionRequest(rs.getString("destination_id"),rs.getString("request_media_type"),rs.getString("request_body"));
    }

}
