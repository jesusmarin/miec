package com.miec.domain;

import com.miec.state.CycleState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Representa un acontecimiento ocurrido durante un ciclo MIEC.
 *
 * Los eventos son inmutables para preservar la trazabilidad histórica.
 */
public final class CycleEvent {

    private final UUID id;
    private final UUID cycleId;
    private final EventType eventType;
    private final CycleState previousState;
    private final CycleState newState;
    private final String description;
    private final Instant occurredAt;
    private final String actor;

    public CycleEvent(
            UUID cycleId,
            EventType eventType,
            CycleState previousState,
            CycleState newState,
            String description,
            String actor) {

        this.id = UUID.randomUUID();

        this.cycleId = Objects.requireNonNull(
                cycleId, "El identificador del ciclo es obligatorio");

        this.eventType = Objects.requireNonNull(
                eventType, "El tipo de evento es obligatorio");

        this.previousState = previousState;
        this.newState = newState;

        this.description = requireText(
                description, "La descripción es obligatoria");

        this.actor = requireText(
                actor, "El actor responsable es obligatorio");

        this.occurredAt = Instant.now();

        validateStates();
    }

    private void validateStates() {

        if (eventType == EventType.STATE_TRANSITION) {

            Objects.requireNonNull(
                    previousState,
                    "El estado anterior es obligatorio para una transición");

            Objects.requireNonNull(
                    newState,
                    "El nuevo estado es obligatorio para una transición");

            if (previousState == newState) {
                throw new IllegalArgumentException(
                        "Una transición debe cambiar el estado");
            }

        } else if (previousState != null || newState != null) {

            throw new IllegalArgumentException(
                    "Los eventos que no son transiciones no deben declarar estados");
        }
    }

    private String requireText(String value, String message) {

        Objects.requireNonNull(value, message);

        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public CycleState getPreviousState() {
        return previousState;
    }

    public CycleState getNewState() {
        return newState;
    }

    public String getDescription() {
        return description;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }
}
