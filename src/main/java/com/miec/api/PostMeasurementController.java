package com.miec.api;

import com.miec.orchestration.MeasurementApplicationService;
import com.miec.orchestration.MeasurementContracts.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** API de hechos posteriores y revisión humana; no recibe identidad, nivel EV ni resultados de evaluación. */
@RestController
@RequestMapping("/api/cycles/{cycleId}/plans/{planId}/measurements")
@Tag(name="Mediciones posteriores",description="POST-MEASURE-01: registro, revisión humana y conjunto fijo; no acredita éxito ni cambia E08 a E09.")
@SecurityRequirement(name="miecBasic")
@ApiResponse(responseCode="400",description="Entrada, referencias, unidad, método, ventana o precisión inválidos")
@ApiResponse(responseCode="401",description="Se requiere autenticación Basic")
@ApiResponse(responseCode="403",description="Rol insuficiente o autorevisión prohibida")
@ApiResponse(responseCode="404",description="Ciclo o plan inexistentes")
@ApiResponse(responseCode="409",description="Versión/estado incompatibles, ejecución incierta, revisión existente o conjunto cerrado")
@ApiResponse(responseCode="500",description="Fallo interno saneado; no se confirmó la escritura")
public class PostMeasurementController {
    private final MeasurementApplicationService service;
    /** Reutiliza la fachada de mediciones con autorización también fuera de HTTP. */
    public PostMeasurementController(MeasurementApplicationService service) {this.service=service;}
    /** Lee hechos y revisiones sin restringir el estado histórico del ciclo. */
    @Operation(summary="Consultar mediciones posteriores y conjunto",description="AUTHOR o APPROVER. Incluye cycleVersion, evidencia original UNVERIFIED, revisiones separadas y set opcional. No selecciona automáticamente muestras.")
    @GetMapping
    public PostContext get(@PathVariable UUID cycleId,@PathVariable UUID planId) {return service.posts(cycleId,planId);}
    /** Guarda medición y evidencia con auditoría y versión en una sola transacción. */
    @Operation(summary="Registrar medición posterior",description="AUTHOR. E08 y run APPLIED sin contradicciones. measurement reutiliza Measure: expectedVersion del ciclo, indicatorId, value decimal (15 enteros/5 decimales), unit, source, measuredAt y reason. Añade runId y method. Fecha UTC con precisión máxima de microsegundos, entre finalización y recepción. Devuelve 200 con el contexto actualizado; no evalúa.")
    @PostMapping(consumes="application/json")
    public PostContext record(@PathVariable UUID cycleId,@PathVariable UUID planId,@Valid @RequestBody PostMeasure command) {return service.recordPost(cycleId,planId,command);}
    /** Conserva una revisión humana atribuida sin modificar la evidencia original. */
    @Operation(summary="Revisar evidencia de una medición",description="APPROVER distinto del autor. expectedVersion del ciclo; runId/evidenceId del contexto. Verdict ACCEPTED_WITH_LIMITATIONS o REJECTED, método, referencia de procedimiento, limitaciones y motivo obligatorios. Una revisión por evidencia/run, sin nivel EV automático ni verificación instrumental implícita.")
    @PostMapping(value="/reviews",consumes="application/json")
    public PostContext review(@PathVariable UUID cycleId,@PathVariable UUID planId,@Valid @RequestBody ReviewMeasure command) {return service.reviewPost(cycleId,planId,command);}
    /** Cierra muestras y revisiones elegidas sin inferir evidencia suficiente o mejora. */
    @Operation(summary="Cerrar conjunto explícito para comparación futura",description="APPROVER. expectedVersion del ciclo, runId, method, ventanas inclusivas baselineFrom/To y postFrom/To, pairs con indicatorId/baselineId/measurementId y reason. Exactamente un par por indicador. EXPLICIT_PAIR_V1 no agrega valores. Conserva revisión ausente o rechazada como limitación; bloquea nuevos datos/revisiones del run. Permanece en E08; no evalúa ni decide.")
    @PostMapping(value="/seal",consumes="application/json")
    public PostContext seal(@PathVariable UUID cycleId,@PathVariable UUID planId,@Valid @RequestBody SealPost command) {return service.sealPost(cycleId,planId,command);}
}
