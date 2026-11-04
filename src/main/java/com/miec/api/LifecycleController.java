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

    /** Descubre sistemas con resumen de ciclos y actividad, sin cargar un grafo de entidades. */
    @io.swagger.v3.oas.annotations.Operation(summary="Lista sistemas y su actividad",description="AUTHOR o APPROVER. Orden createdAt/ID descendente; limit 1..100. q busca nombre literalmente sin distinguir mayusculas; status es el estado administrativo exacto. Cursor ligado a filtros; consistencia por pagina, no entre peticiones. Sin ciclos: actividad y ciclo reciente nulos.")
    @GetMapping("/systems")
    public com.miec.orchestration.LifecycleNavigationViews.SystemPage listSystems(
            @io.swagger.v3.oas.annotations.Parameter(schema=@io.swagger.v3.oas.annotations.media.Schema(minimum="1",maximum="100",defaultValue="50")) @RequestParam(defaultValue="50") int limit,
            @io.swagger.v3.oas.annotations.Parameter(description="nextCursor de la pagina anterior; conservar filtros",schema=@io.swagger.v3.oas.annotations.media.Schema(maxLength=512)) @RequestParam(required=false) String after,
            @io.swagger.v3.oas.annotations.Parameter(description="Subcadena literal del nombre; % y _ no son comodines",schema=@io.swagger.v3.oas.annotations.media.Schema(maxLength=255)) @RequestParam(required=false) String q,
            @io.swagger.v3.oas.annotations.Parameter(description="Estado administrativo del sistema; no es el estado F01 de un ciclo",schema=@io.swagger.v3.oas.annotations.media.Schema(maxLength=50)) @RequestParam(required=false) String status) {
        return service.listSystems(limit, after, q, status);
    }

    /** Descubre ciclos del sistema manteniendo estado y version del contrato de detalle. */
    @io.swagger.v3.oas.annotations.Operation(summary="Lista ciclos de un sistema",description="AUTHOR o APPROVER. Orden createdAt/ID descendente; limit 1..100. Sistema sin ciclos: pagina vacia; inexistente: 404. state admite codigos oficiales F01. Cursor ligado al sistema y al filtro; no habilita comandos ni bloquea escritores.")
    @GetMapping("/systems/{systemId}/cycles")
    public com.miec.orchestration.LifecycleNavigationViews.CyclePage listCycles(@PathVariable UUID systemId,
            @io.swagger.v3.oas.annotations.Parameter(schema=@io.swagger.v3.oas.annotations.media.Schema(minimum="1",maximum="100",defaultValue="50")) @RequestParam(defaultValue="50") int limit,
            @io.swagger.v3.oas.annotations.Parameter(description="nextCursor del mismo sistema y filtro",schema=@io.swagger.v3.oas.annotations.media.Schema(maxLength=512)) @RequestParam(required=false) String after,
            @io.swagger.v3.oas.annotations.Parameter(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.miec.state.CycleState.class)) @RequestParam(required=false) String state) {
        return service.listCycles(systemId, limit, after, state);
    }

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
