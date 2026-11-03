package com.miec.api;

import com.miec.orchestration.InterventionApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
import static com.miec.orchestration.InterventionCommands.*;
import static com.miec.orchestration.InterventionViews.*;

/** Rutas de intervención autenticadas; las mutaciones solo aceptan JSON explícito. */
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="miecBasic")
@io.swagger.v3.oas.annotations.tags.Tag(name="Intervención y autorización",description="Requiere Basic: AUTHOR registra, APPROVER decide y ambos consultan.")
@RestController
@RequestMapping("/api")
public class InterventionController {
    private final InterventionApplicationService service;
    /** Recibe la fachada que exige identidad y rol incluso para invocaciones no HTTP. */
    public InterventionController(InterventionApplicationService service) { this.service=service; }
    /** Completa E01 a E02 con objetivo y criterio de éxito. */
    @io.swagger.v3.oas.annotations.Operation(summary="Completa E01 a E02 con objetivo y criterio de éxito.")
    @PostMapping(value="/cycles/{cycleId}/initialize",consumes="application/json")
    public Preparation initialize(@PathVariable UUID cycleId,@Valid @RequestBody Initialize command) { return service.initialize(cycleId,command); }
    /** Registra observación y pasa de E02 a E03. */
    @io.swagger.v3.oas.annotations.Operation(summary="Registra observación y pasa de E02 a E03.")
    @PostMapping(value="/cycles/{cycleId}/observations",consumes="application/json")
    public Preparation observe(@PathVariable UUID cycleId,@Valid @RequestBody Observe command) { return service.observe(cycleId,command); }
    /** Registra diagnóstico y pasa de E03 a E04. */
    @io.swagger.v3.oas.annotations.Operation(summary="Registra diagnóstico y pasa de E03 a E04.")
    @PostMapping(value="/cycles/{cycleId}/diagnosis",consumes="application/json")
    public Preparation diagnose(@PathVariable UUID cycleId,@Valid @RequestBody Diagnose command) { return service.diagnose(cycleId,command); }
    /** Propone una intervención sin ejecutarla. */
    @io.swagger.v3.oas.annotations.Operation(summary="Propone una intervención sin ejecutarla.")
    @PostMapping(value="/cycles/{cycleId}/plans",consumes="application/json")
    public ResponseEntity<Plan> propose(@PathVariable UUID cycleId,@Valid @RequestBody ProposePlan command) {
        var plan=service.propose(cycleId,command);
        return ResponseEntity.created(URI.create("/api/plans/"+plan.planId())).body(plan);
    }
    /** Devuelve la definición que debe evaluar el aprobador. */
    @io.swagger.v3.oas.annotations.Operation(summary="Devuelve la definición que debe evaluar el aprobador.")
    @GetMapping("/plans/{planId}")
    public Plan getPlan(@PathVariable UUID planId) { return service.getPlan(planId); }
    /** Devuelve el objetivo, criterio y diagnóstico que sustentan el plan a revisar. */
    @io.swagger.v3.oas.annotations.Operation(summary="Devuelve el objetivo, criterio y diagnóstico que sustentan el plan a revisar.")
    @GetMapping("/plans/{planId}/context")
    public ReviewContext reviewContext(@PathVariable UUID planId) { return service.reviewContext(planId); }
    /** Solicita autorización para una acción específica del plan. */
    @io.swagger.v3.oas.annotations.Operation(summary="Solicita autorización para una acción específica del plan.")
    @PostMapping(value="/plans/{planId}/authorization-requests",consumes="application/json")
    public ResponseEntity<Authorization> request(@PathVariable UUID planId,@Valid @RequestBody RequestAuthorization command) {
        var request=service.request(planId,command);
        return ResponseEntity.created(URI.create("/api/authorizations/"+request.authorizationId())).body(request);
    }
    /** Consulta solicitud y decisión sin renovar la vigencia. */
    @io.swagger.v3.oas.annotations.Operation(summary="Consulta solicitud y decisión sin renovar la vigencia.")
    @GetMapping("/authorizations/{authorizationId}")
    public Authorization getAuthorization(@PathVariable UUID authorizationId) { return service.getAuthorization(authorizationId); }
    /** Registra aprobación humana; la activación requiere todas las acciones del plan. */
    @io.swagger.v3.oas.annotations.Operation(summary="Registra aprobación humana; la activación requiere todas las acciones del plan.")
    @PostMapping(value="/authorizations/{authorizationId}/approve",consumes="application/json")
    public Authorization approve(@PathVariable UUID authorizationId,@Valid @RequestBody Decide command) { return service.approve(authorizationId,command); }
    /** Rechaza una solicitud pendiente y suspende el ciclo sin borrar historia. */
    @io.swagger.v3.oas.annotations.Operation(summary="Rechaza una solicitud pendiente y suspende el ciclo sin borrar historia.")
    @PostMapping(value="/authorizations/{authorizationId}/reject",consumes="application/json")
    public Authorization reject(@PathVariable UUID authorizationId,@Valid @RequestBody Decide command) { return service.reject(authorizationId,command); }
}
