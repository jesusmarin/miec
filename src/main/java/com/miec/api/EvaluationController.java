package com.miec.api;

import com.miec.orchestration.EvaluationApplicationService;
import com.miec.orchestration.EvaluationContracts.Saved;
import com.miec.orchestration.CycleCompletionContracts;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Consulta de conclusiones inmutables; no recibe muestras, EV, políticas o instrucciones del cliente. */
@RestController
@RequestMapping("/api")
@Tag(name="Evaluaciones",description="Conclusión inmutable y admisión operativa E08 → E09; no acredita éxito ni modifica F01.")
@SecurityRequirement(name="miecBasic")
@ApiResponse(responseCode="400",description="Identificador inválido")
@ApiResponse(responseCode="401",description="Se requiere Basic")
@ApiResponse(responseCode="403",description="Rol insuficiente")
@ApiResponse(responseCode="404",description="Evaluación o conjunto inexistentes")
@ApiResponse(responseCode="409",description="Snapshot histórico no disponible, versión/etapa incompatible o contexto no confirmado")
public class EvaluationController {
    private final EvaluationApplicationService service;
    /** Recibe una fachada que autoriza también llamadas fuera de HTTP. */
    public EvaluationController(EvaluationApplicationService service){this.service=service;}
    /** Lee entradas y resultado originales sin recomputarlos a partir de datos actuales. */
    @Operation(summary="Consultar evaluación persistida",description="AUTHOR o APPROVER. Incluye snapshot completo, versiones, huella, suficiencia, limitaciones y métricas. RS-05 con diferencias numéricas no acredita éxito ni causalidad. No cambia el ciclo.")
    @GetMapping("/evaluations/{evaluationId}")
    public Saved get(@PathVariable UUID evaluationId){return service.get(evaluationId);}
    /** La lista vacía acredita ausencia de conclusiones registradas, no una evaluación implícita. */
    @Operation(summary="Consultar evaluaciones de un conjunto cerrado",description="AUTHOR o APPROVER. Devuelve conclusiones históricas por conjunto y política, sin crear, modificar ni asignar un nivel EV al consultar.")
    @GetMapping("/measurement-sets/{setId}/evaluations")
    public List<Saved> forSet(@PathVariable UUID setId){return service.forSet(setId);}
    /** Confirma conclusión, recibo, evento y transición antes de responder con su recurso durable. */
    @Operation(summary="Registrar evaluación y avanzar a E09",description="AUTHOR. Versión del ciclo, set cerrado y motivo. Políticas del servidor; sin EV o clasificación del cliente. La insuficiencia conserva RS-05. Ante timeout consultar el recurso; una repetición con versión antigua devuelve 409.")
    @ApiResponse(responseCode="201",description="Evaluación admitida y transición E08 → E09 confirmadas")
    @PostMapping(value="/cycles/{cycleId}/plans/{planId}/evaluations",consumes="application/json")
    public org.springframework.http.ResponseEntity<CycleCompletionContracts.EvaluationCreated> create(@PathVariable UUID cycleId,@PathVariable UUID planId,@Valid @RequestBody CycleCompletionContracts.Evaluate command){
        var created=service.create(cycleId,planId,command);
        return org.springframework.http.ResponseEntity.created(java.net.URI.create("/api/evaluations/"+created.evaluation().report().result().id())).body(created);
    }
}
