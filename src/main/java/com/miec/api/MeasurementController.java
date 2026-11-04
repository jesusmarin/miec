package com.miec.api;

import com.miec.orchestration.MeasurementApplicationService;
import com.miec.orchestration.InterventionViews.Preparation;
import com.miec.orchestration.MeasurementContracts.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * API del objetivo medible: todas las rutas requieren Basic y roles de aplicación.
 * Las líneas base manuales conservan HUMAN_REPORT/UNVERIFIED; registrar o cerrar
 * su observación no acredita suficiencia instrumental ni causalidad.
 */
@RestController
@RequestMapping("/api/cycles/{cycleId}/measurement")
@Tag(name="Objetivos medibles",description="Objetivos y líneas base manuales; no ejecuta acciones ni acredita éxito.")
@SecurityRequirement(name="miecBasic")
@ApiResponse(responseCode="400",description="Contrato, unidad, indicador o fecha inválidos")
@ApiResponse(responseCode="401",description="Credenciales ausentes o inválidas")
@ApiResponse(responseCode="403",description="Rol no permitido")
@ApiResponse(responseCode="404",description="Ciclo inexistente")
@ApiResponse(responseCode="409",description="Versión o etapa incompatible; línea base incompleta")
public class MeasurementController {
    private final MeasurementApplicationService service;
    /** Recibe la fachada con autorización aplicada también fuera de HTTP. */
    public MeasurementController(MeasurementApplicationService service) { this.service=service; }
    /** Crea objetivo e indicadores y completa E01 a E02 atómicamente. */
    @Operation(summary="Inicializar objetivo medible",description="AUTHOR. expectedVersion debe ser la versión vigente del ciclo E01.")
    @PostMapping(value="/initialize",consumes="application/json")
    public Preparation initialize(@PathVariable UUID cycleId,@Valid @RequestBody Initialize command) { return service.initialize(cycleId,command); }
    /** Consulta directamente el objetivo y las mediciones antes de existir un plan. */
    @Operation(summary="Consultar objetivo, criterios y línea base",description="AUTHOR o APPROVER. LEGACY_UNSTRUCTURED identifica los ciclos históricos sin criterios numéricos.")
    @GetMapping
    public Context context(@PathVariable UUID cycleId) { return service.context(cycleId); }
    /** Conserva medición, evidencia y evento manteniendo E02. */
    @Operation(summary="Registrar medición de línea base",description="AUTHOR. Unidad exacta, origen obligatorio y fecha del hecho no posterior a recepción. Reporte HUMAN_REPORT/UNVERIFIED: indicar un instrumento en source no autentica esa fuente ni acredita suficiencia instrumental o causalidad.")
    @PostMapping(value="/baselines",consumes="application/json")
    public Preparation measure(@PathVariable UUID cycleId,@Valid @RequestBody Measure command) { return service.measure(cycleId,command); }
    /** Genera el acta del conjunto de mediciones y pasa E02 a E03. */
    @Operation(summary="Finalizar observación",description="AUTHOR. Cada indicador necesita al menos una medición; resourceId identifica la evidencia que debe citar el diagnóstico.")
    @PostMapping(value="/complete",consumes="application/json")
    public Preparation complete(@PathVariable UUID cycleId,@Valid @RequestBody Complete command) { return service.complete(cycleId,command); }
}
