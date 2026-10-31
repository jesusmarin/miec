package com.miec.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Representa una autorización humana dentro de un ciclo MIEC.
 *
 * Registra quién autoriza o rechaza una acción,
 * cuándo se toma la decisión y sobre qué elemento recae.
 */
public final class Approval {

    private final UUID id;
    private final UUID cycleId;
    private final String subject;
    private final String description;
    private final String requestedBy;
    private final String decidedBy;
    private final ApprovalStatus status;
    private final String justification;
    private final Instant requestedAt;
    private final Instant decidedAt;

    private Approval(
            UUID id,
            UUID cycleId,
            String subject,
            String description,
            String requestedBy,
            String decidedBy,
            ApprovalStatus status,
            String justification,
            Instant requestedAt,
            Instant decidedAt
    ) {
        this.id = id;
        this.cycleId = cycleId;
        this.subject = subject;
        this.description = description;
        this.requestedBy = requestedBy;
        this.decidedBy = decidedBy;
        this.status = status;
        this.justification = justification;
        this.requestedAt = requestedAt;
        this.decidedAt = decidedAt;
    }

    /**
     * Crea una solicitud de autorización pendiente.
     */
    public static Approval request(
            UUID cycleId,
            String subject,
            String description,
            String requestedBy
    ) {
        return new Approval(
                UUID.randomUUID(),
                requireNotNull(cycleId, "El identificador del ciclo es obligatorio."),
                requireText(subject, "El asunto de la autorización es obligatorio."),
                requireText(description, "La descripción es obligatoria."),
                requireText(requestedBy, "El solicitante es obligatorio."),
                null,
                ApprovalStatus.PENDING,
                null,
                Instant.now(),
                null
        );
    }

    /**
     * Registra la aprobación de una solicitud pendiente.
     */
    public Approval approve(String decidedBy, String justification) {
        return decide(
                decidedBy,
                justification,
                ApprovalStatus.APPROVED
        );
    }

    /**
     * Registra el rechazo de una solicitud pendiente.
     */
    public Approval reject(String decidedBy, String justification) {
        return decide(
                decidedBy,
                justification,
                ApprovalStatus.REJECTED
        );
    }

    private Approval decide(
            String decidedBy,
            String justification,
            ApprovalStatus newStatus
    ) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "La solicitud ya tiene una decisión registrada."
            );
        }

        String validDecidedBy = requireText(
                decidedBy,
                "El responsable de la decisión es obligatorio."
        );

        String validJustification = requireText(
                justification,
                "La justificación de la decisión es obligatoria."
        );

        return new Approval(
                id,
                cycleId,
                subject,
                description,
                requestedBy,
                validDecidedBy,
                newStatus,
                validJustification,
                requestedAt,
                Instant.now()
        );
    }

    private static <T> T requireNotNull(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
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

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getJustification() {
        return justification;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }

    public boolean isApproved() {
        return status == ApprovalStatus.APPROVED;
    }

    public boolean isRejected() {
        return status == ApprovalStatus.REJECTED;
    }
}