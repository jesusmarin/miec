package com.miec.orchestration;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Proyecciones de navegacion; no sustituyen los contratos de detalle ni autorizan comandos. */
public final class LifecycleNavigationViews {
    /** Evita instancias del contenedor de vistas. */
    private LifecycleNavigationViews() { }

    /** Ciclo con actividad mas reciente; los empates usan numero de ciclo e identificador descendentes. */
    public record RecentCycle(UUID cycleId, int cycleNumber, String state, long version, Instant lastActivityAt) { }

    /** Resumen global de lectura; los estados ausentes del mapa tienen conteo cero. */
    @Schema(description="Sistema y ciclos agrupados por estado. Sin ciclos: cycleCount=0, mapa vacio y actividad/ciclo reciente nulos. No equivale a ejecuciones activas.")
    public record SystemSummary(UUID systemId, String name, String domain, String status, Instant createdAt,
                                long cycleCount, Map<String, Long> cyclesByState, Instant lastActivityAt, RecentCycle recentCycle) {
        /** Impide modificar los conteos a traves de la vista entregada. */
        public SystemSummary { cyclesByState = Map.copyOf(cyclesByState); }
    }

    /** Pagina de sistemas ordenada por createdAt e ID descendentes, independiente del orden de actividad. */
    public record SystemPage(List<SystemSummary> items, String nextCursor) {
        /** Entrega una coleccion inmutable y un cursor opaco o null al terminar. */
        public SystemPage { items = List.copyOf(items); }
    }

    /** Pagina de ciclos del sistema; conserva los campos y la version del contrato de detalle existente. */
    public record CyclePage(List<LifecycleViews.CycleView> items, String nextCursor) {
        /** Entrega una coleccion inmutable y un cursor asociado al sistema y al filtro. */
        public CyclePage { items = List.copyOf(items); }
    }
}
