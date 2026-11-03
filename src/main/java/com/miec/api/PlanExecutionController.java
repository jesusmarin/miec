package com.miec.api;

import com.miec.orchestration.PlanExecutionApplicationService;
import com.miec.orchestration.PlanRecoveryApplicationService;
import com.miec.orchestration.InterventionCommands;
import com.miec.orchestration.InterventionViews;
import com.miec.orchestration.PlanExecutionContracts.*;
import com.miec.execution.ExecutableActions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/** API de coordinación del plan; nunca recibe un ejecutor, URL, token de propiedad o cuerpo nuevo de acción. */
@RestController
@RequestMapping("/api")
@Tag(name="Ejecución del plan",description="IR-009 desde E06, recuperación explícita y finalización técnica a E08. APPLIED no acredita mejora.")
@SecurityRequirement(name="miecBasic")
@ApiResponse(responseCode="400",description="Comando o relación ciclo/plan inválidos")
@ApiResponse(responseCode="401",description="Se requiere autenticación")
@ApiResponse(responseCode="403",description="Rol insuficiente")
@ApiResponse(responseCode="404",description="Plan o coordinación inexistentes")
@ApiResponse(responseCode="409",description="Versión, etapa, permiso, propiedad o definición incompatible")
@ApiResponse(responseCode="500",description="Fallo interno; EXECUTION_AUDIT_UNAVAILABLE incluye solo fields.incidentId como correlación")
public class PlanExecutionController {
    private final PlanExecutionApplicationService service;
    private final PlanRecoveryApplicationService recovery;
    /** Recibe la fachada autenticada y no accede directamente a tablas o adaptadores. */
    public PlanExecutionController(PlanExecutionApplicationService service,PlanRecoveryApplicationService recovery) { this.service=service;this.recovery=recovery; }

    /** Crea la coordinación y ejecuta secuencialmente fuera de las transacciones del ciclo. */
    @Operation(summary="Iniciar plan aprobado",description="AUTHOR. Responde 201 con recurso durable, incluso si queda RECOVERY_REQUIRED. Ante timeout consultar Location; no reenviar ciegamente.")
    @PostMapping(value="/cycles/{cycleId}/plans/{planId}/execution",consumes="application/json")
    public ResponseEntity<Run> start(@PathVariable UUID cycleId,@PathVariable UUID planId,@Valid @RequestBody Start command) {
        var run=service.start(cycleId,planId,command);
        return ResponseEntity.created(URI.create("/api/plans/"+planId+"/execution")).body(run);
    }

    /** Consulta sin efectos y sin exigir que el permiso de ejecución siga vigente. */
    @Operation(summary="Consultar coordinación y resultados IR-009",description="AUTHOR o APPROVER. requiresReview advierte vencimiento o contradicciones; incidents conserva códigos, fases e IDs, sin mensajes internos ni secretos.")
    @GetMapping("/plans/{planId}/execution")
    public Run get(@PathVariable UUID planId) { return service.get(planId); }

    /** Reanuda únicamente trabajo pendiente que no tiene marca de despacho incierto. */
    @Operation(summary="Reanudar reserva vencida sin despacho incierto",description="AUTHOR. expectedVersion es la versión del run, no la del ciclo. Se revalidan contenido, adaptadores y permisos pendientes.")
    @PostMapping(value="/plans/{planId}/execution/resume",consumes="application/json")
    public Run resume(@PathVariable UUID planId,@Valid @RequestBody Control command) { return service.resume(planId,command); }

    /** Importa hechos locales, sin consultas externas ni renovación de permisos. */
    @Operation(summary="Recuperar hechos ya persistidos",description="APPROVER. No envía acciones ni convierte UNKNOWN en éxito. Permite reparar una actualización del ciclo perdida después del commit de IR-009, aunque el permiso de ejecución haya vencido.")
    @PostMapping(value="/plans/{planId}/execution/recover",consumes="application/json")
    public Run recover(@PathVariable UUID planId,@Valid @RequestBody Control command) { return service.recover(planId,command); }

    /** Finaliza atómicamente el efecto técnico confirmado y habilita la posterior verificación del objetivo. */
    @Operation(summary="Finalizar ejecución hacia E08",description="APPROVER. Exige versiones del run y ciclo, todas las acciones APPLIED sin contradicciones y ausencia de coordinador vigente. No exige renovar EXECUTE_ACTION ni declara mejora. Segunda finalización: 409.")
    @PostMapping(value="/plans/{planId}/execution/finish",consumes="application/json")
    public Run finish(@PathVariable UUID planId,@Valid @RequestBody Finish command) { return service.finish(planId,command); }

    /** Solicita un permiso separado de ejecución para revisar el resultado de una acción incierta. */
    @Operation(summary="Solicitar autorización de reconciliación",description="AUTHOR. Acción del run, motivo y vigencia 30–3600 segundos. Crea PENDING, incrementa versiones de run/ciclo y conserva los permisos de ejecución sin cambios. Consultar y revisar antes de aprobar.")
    @PostMapping(value="/plans/{planId}/execution/reconciliation-requests",consumes="application/json")
    public ResponseEntity<InterventionViews.Authorization> requestReconciliation(@PathVariable UUID planId,@Valid @RequestBody RequestReconciliation command) {
        var permission=recovery.request(planId,command);
        return ResponseEntity.created(URI.create("/api/authorizations/"+permission.authorizationId())).body(permission);
    }

    /** El aprobador debe ser una identidad distinta del solicitante y será el revisor autorizado. */
    @Operation(summary="Aprobar permiso RECONCILE_ACTION",description="APPROVER distinto del solicitante. expectedVersion pertenece a la autorización. No aprueba ejecución ni renueva permisos vencidos. La consulta y decisión posteriores se atribuyen a este aprobador.")
    @PostMapping(value="/execution/reconciliation-authorizations/{authorizationId}/approve",consumes="application/json")
    public InterventionViews.Authorization approveReconciliation(@PathVariable UUID authorizationId,@Valid @RequestBody InterventionCommands.Decide command) {
        return recovery.decide(authorizationId,command,true);
    }

    /** Guarda un rechazo humano durable sin convertirlo en resultado técnico del intento. */
    @Operation(summary="Rechazar permiso RECONCILE_ACTION",description="APPROVER distinto del solicitante. Decisión única y auditada; no elimina el intento ni su evidencia.")
    @PostMapping(value="/execution/reconciliation-authorizations/{authorizationId}/reject",consumes="application/json")
    public InterventionViews.Authorization rejectReconciliation(@PathVariable UUID authorizationId,@Valid @RequestBody InterventionCommands.Decide command) {
        return recovery.decide(authorizationId,command,false);
    }

    /** Consulta al destinatario instalado sin enviar acciones ni aceptar evidencias declaradas por el cliente. */
    @Operation(summary="Consultar y reconciliar efecto aplicado",description="APPROVER identificado por un RECONCILE_ACTION vigente. Solo GET remoto fuera de transacción. Fuente instalada por el servidor; verifica clave, destino, huella y referencia. NOT_FOUND/UNKNOWN o contradicción: 409, sin retry automático. Persiste evidencia y decisión IR-009 en transacciones independientes; una interrupción puede conservar hechos parciales. Reconsultar el run y usar versiones actuales. No pasa a E08: usar finish tras revisar.")
    @PostMapping(value="/plans/{planId}/execution/reconcile",consumes="application/json")
    public Run reconcile(@PathVariable UUID planId,@Valid @RequestBody Reconcile command) { return recovery.reconcile(planId,command); }

    /** Enumera identidades y huellas públicas, nunca URLs ni credenciales. */
    @Operation(summary="Consultar catálogo ejecutable instalado",description="AUTHOR o APPROVER. Vacío si no se instaló ningún adaptador. Proponer un plan no invoca al destinatario.")
    @GetMapping("/execution-adapters")
    public List<ExecutableActions.Descriptor> catalog() { return service.catalog(); }
}
