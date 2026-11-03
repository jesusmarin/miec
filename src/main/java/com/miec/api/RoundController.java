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

/** Contexto histórico y continuidad mediante intenciones cerradas y aprobación independiente. */
@RestController @RequestMapping("/api/cycles/{cycleId}/rounds")
@Tag(name="Rondas",description="ROUND-CONTEXT-V1, PLAN-COMPLETION-V1 y ROUND-CONTINUITY-V1")
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
    /** Registra una solicitud sin abrir todavía la siguiente ronda. */
    @PostMapping(value="/{roundId}/continuations",consumes="application/json")
    @Operation(summary="Solicitar continuidad de ronda",description="AUTHOR. E11, plan concluido, expectedCycleVersion, expectedRoundVersion, decisionId, learningId, mode, acknowledgeLimitations=true, reason y baselines. COLLECT_EVIDENCE exige baselines=[] y diagnosis ausente. ADJUST_PLAN exige una muestra nueva por indicador y diagnosis nuevo. No concede permisos ni cambia F02 o EV.")
    public Continuation request(@PathVariable UUID cycleId,@PathVariable UUID roundId,@Valid @RequestBody RequestContinuation command){return service.request(cycleId,roundId,command);}
    /** Consulta entrada y resolución originales sin renovar vigencia. */
    @GetMapping("/continuations/{requestId}")
    @Operation(summary="Consultar solicitud de continuidad",description="AUTHOR o APPROVER. Una solicitud pendiente con versiones antiguas requiere nueva solicitud; no se aprueba con otro contexto.")
    public Continuation request(@PathVariable UUID cycleId,@PathVariable UUID requestId){return service.request(cycleId,requestId);}
    /** Abre una única ronda y aplica exclusivamente una transición admitida por F01. */
    @PostMapping(value="/continuations/{requestId}/approve",consumes="application/json")
    @Operation(summary="Aprobar continuidad y abrir ronda sucesora",description="APPROVER distinto del autor. expectedCycleVersion, expectedRoundVersion, expectedRequestVersion, acknowledgeLimitations=true y reason. Revalida IR-009. Confirma sucesora, contexto y E11→E02/E04 juntos. No reabre E14 ni hereda autorizaciones.")
    public Context approve(@PathVariable UUID cycleId,@PathVariable UUID requestId,@Valid @RequestBody ApproveContinuation command){return service.approve(cycleId,requestId,command);}
    /** Permite recuperar las muestras de una ronda anterior. */
    @GetMapping("/{roundId}/measurement")
    @Operation(summary="Consultar objetivo y línea base de una ronda",description="AUTHOR o APPROVER. No mezcla muestras de rondas distintas.")
    public com.miec.orchestration.MeasurementContracts.Context measurements(@PathVariable UUID cycleId,@PathVariable UUID roundId){return service.measurements(cycleId,roundId);}
    /** Separa recibos anteriores de los de la ronda actual. */
    @GetMapping("/{roundId}/completion")
    @Operation(summary="Consultar evaluación, decisión y aprendizaje de una ronda",description="AUTHOR o APPROVER. Recupera conclusiones originales; consultar rounds para el snapshot completo inmutable. El estado del ciclo es el actual y requiresReview refleja incertidumbre técnica actual.")
    public com.miec.orchestration.CycleCompletionContracts.View completion(@PathVariable UUID cycleId,@PathVariable UUID roundId){return service.completion(cycleId,roundId);}
}
