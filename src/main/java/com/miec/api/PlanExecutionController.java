package com.miec.api;

import com.miec.orchestration.PlanExecutionApplicationService;
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
@Tag(name="Ejecución del plan",description="IR-009 desde E06. APPLIED es un hecho técnico; el incremento 4 completará E08.")
@SecurityRequirement(name="miecBasic")
@ApiResponse(responseCode="400",description="Comando o relación ciclo/plan inválidos")
@ApiResponse(responseCode="401",description="Se requiere autenticación")
@ApiResponse(responseCode="403",description="Rol insuficiente")
@ApiResponse(responseCode="404",description="Plan o coordinación inexistentes")
@ApiResponse(responseCode="409",description="Versión, etapa, permiso, propiedad o definición incompatible")
@ApiResponse(responseCode="500",description="Fallo interno; EXECUTION_AUDIT_UNAVAILABLE incluye solo fields.incidentId como correlación")
public class PlanExecutionController {
    private final PlanExecutionApplicationService service;
    /** Recibe la fachada autenticada y no accede directamente a tablas o adaptadores. */
    public PlanExecutionController(PlanExecutionApplicationService service) { this.service=service; }

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

    /** Enumera identidades y huellas públicas, nunca URLs ni credenciales. */
    @Operation(summary="Consultar catálogo ejecutable instalado",description="AUTHOR o APPROVER. Vacío si no se instaló ningún adaptador. Proponer un plan no invoca al destinatario.")
    @GetMapping("/execution-adapters")
    public List<ExecutableActions.Descriptor> catalog() { return service.catalog(); }
}
