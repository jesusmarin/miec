package com.miec.api;

import com.miec.orchestration.LifecycleApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
import static com.miec.orchestration.LifecycleCommands.*;
import static com.miec.orchestration.LifecycleViews.*;

/** API autenticada del MVP; delega identidad, reglas y transacciones en la capa de aplicación. */
@io.swagger.v3.oas.annotations.tags.Tag(name="Sistemas y ciclos",description="Basic obligatorio. AUTHOR registra e inicia; AUTHOR y APPROVER consultan. X-MIEC-Actor se ignora.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="miecBasic")
@RestController
@RequestMapping("/api")
public class LifecycleController {
    private final LifecycleApplicationService service;

    /** Recibe los casos de uso sin acceder directamente a tablas o entidades. */
    public LifecycleController(LifecycleApplicationService service) { this.service = service; }

    /** Registra un sistema y publica la ubicación de su representación persistida. */
    @io.swagger.v3.oas.annotations.Operation(summary="Registra un sistema y publica la ubicación de su representación persistida.")
    @PostMapping(value="/systems",consumes="application/json")
    public ResponseEntity<SystemView> createSystem(@Valid @RequestBody CreateSystem command) {
        var created = service.createSystem(command);
        return ResponseEntity.created(URI.create("/api/systems/" + created.systemId())).body(created);
    }

    /** Obtiene el sistema o devuelve un rechazo explícito de recurso inexistente. */
    @io.swagger.v3.oas.annotations.Operation(summary="Obtiene el sistema o devuelve un rechazo explícito de recurso inexistente.")
    @GetMapping("/systems/{systemId}")
    public SystemView getSystem(@PathVariable UUID systemId) { return service.getSystem(systemId); }

    /** Crea un ciclo A0 inactivo y atribuye su evento al autor autenticado. */
    @io.swagger.v3.oas.annotations.Operation(summary="Crea un ciclo A0 inactivo con identidad autenticada.")
    @PostMapping(value="/systems/{systemId}/cycles",consumes="application/json")
    public ResponseEntity<CycleView> createCycle(@PathVariable UUID systemId,
            @Valid @RequestBody CreateCycle command) {
        var created = service.createCycle(systemId, command);
        return ResponseEntity.created(URI.create("/api/cycles/" + created.cycleId())).body(created);
    }

    /** Obtiene el estado actual y la versión necesaria para comandos posteriores. */
    @io.swagger.v3.oas.annotations.Operation(summary="Obtiene el estado actual y la versión necesaria para comandos posteriores.")
    @GetMapping("/cycles/{cycleId}")
    public CycleView getCycle(@PathVariable UUID cycleId) { return service.getCycle(cycleId); }

    /** Inicia el ciclo con la versión observada por el cliente y un motivo de auditoría. */
    @io.swagger.v3.oas.annotations.Operation(summary="Inicia el ciclo con la versión observada por el cliente y un motivo de auditoría.")
    @PostMapping(value="/cycles/{cycleId}/start",consumes="application/json")
    public CycleView startCycle(@PathVariable UUID cycleId, @Valid @RequestBody StartCycle command) {
        return service.startCycle(cycleId, command);
    }

    /** Página cronológica de transiciones; un cursor ajeno se rechaza explícitamente. */
    @io.swagger.v3.oas.annotations.Operation(summary="Página cronológica de transiciones; un cursor ajeno se rechaza explícitamente.")
    @GetMapping("/cycles/{cycleId}/history")
    public HistoryPage history(@PathVariable UUID cycleId, @RequestParam(defaultValue="50") int limit,
                               @RequestParam(required=false) UUID after) {
        return service.history(cycleId, limit, after);
    }
}
