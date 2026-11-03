package com.miec.api;

import com.miec.orchestration.CycleCompletionApplicationService;
import com.miec.orchestration.CycleCompletionContracts.*;
import com.miec.decision.DecisionOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Comandos de decisión y cierre explícitos; no expone un destino de estado arbitrario. */
@RestController @RequestMapping("/api")
@Tag(name="Decisión y cierre",description="CYCLE-CLOSURE-V1: recomendación, selección humana, aprendizaje y cierre sin declaración implícita de éxito.")
@SecurityRequirement(name="miecBasic")
@ApiResponse(responseCode="400",description="Comando, reconocimiento de limitaciones o referencia inválidos")
@ApiResponse(responseCode="401",description="Se requiere autenticación")
@ApiResponse(responseCode="403",description="Rol insuficiente o decisión por el propio autor")
@ApiResponse(responseCode="404",description="Recurso inexistente")
@ApiResponse(responseCode="409",description="Versión/etapa incompatible, intención inadmisible o incertidumbre técnica")
public class CycleCompletionController {
    private final CycleCompletionApplicationService service;
    /** Usa una fachada que protege también invocaciones no HTTP. */
    public CycleCompletionController(CycleCompletionApplicationService service){this.service=service;}
    /** Consulta recibos históricos; una contradicción técnica tardía se refleja en requiresReview. */
    @Operation(summary="Consultar evaluación operativa, decisión, aprendizaje y cierre",description="AUTHOR o APPROVER. Las etapas no realizadas aparecen ausentes. La conclusión original y las decisiones históricas no se recalculan ni se borran.")
    @GetMapping("/cycles/{cycleId}/completion")
    public View get(@PathVariable UUID cycleId){return service.get(cycleId);}
    /** Consulta una intención estratégica que no es permiso ni orden ejecutable. */
    @Operation(summary="Consultar recomendación F02 antes de decidir",description="AUTHOR o APPROVER. Solo E09 con evaluación admitida; la transición inmediata propuesta es E10. No produce efectos. Tras decidir, consultar la recomendación guardada en completion.")
    @GetMapping("/evaluations/{evaluationId}/recommendation")
    public DecisionOutcome recommendation(@PathVariable UUID evaluationId){return service.recommendation(evaluationId);}
    /** La selección puede diferir de la recomendación, pero debe ser admisible y conservar limitaciones. */
    @Operation(summary="Registrar decisión humana y avanzar a E10",description="APPROVER distinto del autor. expectedVersion del ciclo, evaluationId admitida, action admisible, acknowledgeLimitations=true y motivo. Seleccionar CLOSE_CYCLE es intención administrativa, no cierre inmediato ni éxito; el ciclo pasa E09 → E10. No concede EXECUTE_ACTION.")
    @PostMapping(value="/cycles/{cycleId}/decisions",consumes="application/json")
    public View decide(@PathVariable UUID cycleId,@Valid @RequestBody Decide command){return service.decide(cycleId,command);}
    /** Registra condiciones y limitaciones de lo aprendido, sin modificar la conclusión original. */
    @Operation(summary="Registrar aprendizaje y avanzar a E11",description="AUTHOR. expectedVersion del ciclo, decisionId operativa, statement, conditions, limitations y reason. La confianza se hereda del evaluador, no del cliente. Estado, evento y aprendizaje confirman conjuntamente. No ejecuta la intención estratégica.")
    @PostMapping(value="/cycles/{cycleId}/learning",consumes="application/json")
    public View learn(@PathVariable UUID cycleId,@Valid @RequestBody Learn command){return service.learn(cycleId,command);}
    /** Finaliza sin saltar aprendizaje ni convertir una conclusión inconclusa en éxito. */
    @Operation(summary="Cerrar administrativamente el ciclo en E14",description="APPROVER. expectedVersion del ciclo, learningId, summary, acknowledgeLimitations=true y reason (máximo 255). Exige selección humana CLOSE_CYCLE y todos los intentos técnicos resueltos. Confirma closed_at, estado, versión y acta juntos. RS-05 puede cerrarse sin éxito; otras intenciones requieren continuidad aún no habilitada. Repetir con versión antigua devuelve 409.")
    @PostMapping(value="/cycles/{cycleId}/close",consumes="application/json")
    public View close(@PathVariable UUID cycleId,@Valid @RequestBody Close command){return service.close(cycleId,command);}
}
