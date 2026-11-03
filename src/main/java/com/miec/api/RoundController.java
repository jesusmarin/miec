package com.miec.api;

import com.miec.orchestration.RoundApplicationService;
import com.miec.orchestration.RoundContracts.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Contexto histórico de rondas; no ofrece cambios arbitrarios de estado ni continuidad. */
@RestController @RequestMapping("/api/cycles/{cycleId}/rounds")
@Tag(name="Rondas",description="ROUND-CONTEXT-V1 y PLAN-COMPLETION-V1; continuidad pendiente")
@SecurityRequirement(name="miecBasic")
@ApiResponse(responseCode="400",description="Comando inválido")
@ApiResponse(responseCode="401",description="Identidad requerida")
@ApiResponse(responseCode="403",description="Rol o separación de funciones inválidos")
@ApiResponse(responseCode="404",description="Ciclo o ronda inexistente")
@ApiResponse(responseCode="409",description="Versión, etapa, ronda o ejecución incompatible")
public class RoundController {
    private final RoundApplicationService service;
    /** Usa la fachada con control de identidad. */
    public RoundController(RoundApplicationService service){this.service=service;}
    /** Devuelve versiones actuales separadas del snapshot histórico. */
    @GetMapping @Operation(summary="Consultar rondas y planes lógicamente activos",description="AUTHOR o APPROVER. No crea rondas para históricos ni recalcula evaluaciones.")
    public Context get(@PathVariable UUID cycleId){return service.get(cycleId);}
    /** Registra explícitamente el contexto inicial sin avanzar desde E11. */
    @PostMapping(value="/initial",consumes="application/json")
    @Operation(summary="Registrar ronda inicial en E11",description="AUTHOR. expectedCycleVersion y reason. Exige evaluación admitida, decisión, aprendizaje y ausencia de incertidumbre. Audita e incrementa versión del ciclo; no abre una segunda ronda.")
    public Context register(@PathVariable UUID cycleId,@Valid @RequestBody RegisterInitial command){return service.register(cycleId,command);}
    /** Concluye el plan conservando su aprobación y la ronda activa. */
    @PostMapping(value="/{roundId}/plan-completion",consumes="application/json")
    @Operation(summary="Concluir operativamente el plan de la ronda",description="APPROVER distinto del autor. expectedCycleVersion, expectedRoundVersion y reason. Incrementa ambas versiones con auditoría. No cambia APPROVED, suficiencia EV, confianza o estado E11; no habilita continuidad.")
    public Context conclude(@PathVariable UUID cycleId,@PathVariable UUID roundId,@Valid @RequestBody CompletePlan command){return service.conclude(cycleId,roundId,command);}
}
