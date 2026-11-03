package com.miec.api;

import com.miec.orchestration.EvaluationApplicationService;
import com.miec.orchestration.EvaluationContracts.Saved;
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
@Tag(name="Evaluaciones",description="06.3: solo consulta de snapshots persistidos. La creación operativa y E08 → E09 corresponden a 06.4.")
@SecurityRequirement(name="miecBasic")
@ApiResponse(responseCode="400",description="Identificador inválido")
@ApiResponse(responseCode="401",description="Se requiere Basic")
@ApiResponse(responseCode="403",description="Rol insuficiente")
@ApiResponse(responseCode="404",description="Evaluación o conjunto inexistentes")
@ApiResponse(responseCode="409",description="La evaluación histórica no dispone de snapshot MVP-06")
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
}
