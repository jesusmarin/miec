package com.miec.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Proyecciones inmutables de la API; no expone entidades JPA modificables. */
public final class LifecycleViews {
    /** Evita instancias del contenedor de proyecciones. */
    private LifecycleViews() { }

    /** Sistema registrado y sus fechas persistidas. */
    public record SystemView(UUID systemId, String name, String domain, String description,
                             String ownerReference, String status, Instant createdAt, Instant updatedAt) { }

    /** Ciclo y versión que el cliente debe presentar al solicitar su inicio. */
    public record CycleView(UUID cycleId, UUID systemId, Integer cycleNumber, String state,
                            String autonomyLevel, String configurationVersionId, Instant startedAt,
                            Instant createdAt, Instant updatedAt, long version) { }

    /** Transición persistida, ligada al evento del mismo ciclo y a la identidad declarada del operador. */
    public record TransitionView(UUID transitionId, UUID cycleId, String fromState, String toState,
                                 UUID eventId, String actorId, String reason, String rule,
                                 String status, Instant occurredAt) { }

    /** Página de historial ordenada por fecha e identificador; el cursor pertenece al mismo ciclo. */
    public record HistoryPage(List<TransitionView> items, UUID nextCursor) {
        /** Impide modificar el historial desde la colección entregada al consumidor. */
        public HistoryPage { items = List.copyOf(items); }
    }
}
