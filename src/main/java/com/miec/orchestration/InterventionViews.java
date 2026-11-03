package com.miec.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Representaciones de preparación, planes y autorización sin entidades modificables. */
public final class InterventionViews {
    /** Evita instancias del contenedor. */
    private InterventionViews() { }
    /** Resultado del paso de preparación y su recurso auditable. */
    public record Preparation(LifecycleViews.CycleView cycle, UUID resourceId) { }
    /** Acción identificada por el servidor, compatible con el JSON v01 existente. */
    public record PlanAction(UUID actionId, String actionType, String description) { }
    /** Contenido revisable de un plan y su versión de concurrencia. */
    public record Plan(UUID planId, UUID cycleId, UUID objectiveId, UUID diagnosisId, String createdBy,
                       String status, long version, List<PlanAction> actions, String expectedOutcomes,
                       String rollbackStrategy, String fingerprint, Instant createdAt) {
        /** Impide modificar la definición desde la colección devuelta. */
        public Plan { actions = List.copyOf(actions); }
    }
    /** Decisión humana que permanece aunque la autorización expire o se revoque. */
    public record Decision(String decision, String decidedBy, String reason, Instant decidedAt) { }
    /** Objetivo y criterio que el aprobador puede leer antes de decidir. */
    public record ReviewObjective(UUID objectiveId,String name,String successCriteria) { }
    /** Fuente manual y limitaciones visibles; no equivale a una evaluación automática del resultado. */
    public record ReviewDiagnosis(UUID diagnosisId,String summary,String createdBy,UUID evidenceId,
                                   String observation,String sourceReference,String qualityStatus,String limitations) { }
    /** Contexto exacto referenciado por un plan y huella de su contenido revisable. */
    public record ReviewContext(UUID planId,UUID cycleId,String planFingerprint,ReviewObjective objective,ReviewDiagnosis diagnosis,MeasurementContracts.Context measurements) { }
    /** Estado de solicitud; APPROVED_PENDING_PLAN todavía no permite ejecutar. */
    public record Authorization(UUID authorizationId, UUID planId, UUID actionId, String scope, String status,
                                String requestedBy, String approvedBy, Instant issuedAt, Instant expiresAt,
                                Instant revokedAt, long version, Decision decision) { }
}
