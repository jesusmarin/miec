package com.miec.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad JPA que representa una evidencia asociada a un ciclo (MIEC-F03 ENT-09).
 *
 * <p>Esta entidad registra fuentes, mediciones, documentos o registros que respaldan
 * una evaluación. La evidencia puede corresponder a resultados de pruebas, diferencias
 * de código, versiones de herramientas, registros de ejecución o resultados de validación.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El identificador del ciclo es obligatorio.</li>
 *   <li>El tipo de fuente es obligatorio.</li>
 *   <li>El instante de captura es obligatorio.</li>
 *   <li>El estado de calidad es obligatorio.</li>
 * </ul>
 *
 * @see <a href="MIEC-F03 §7.9">MIEC-F03 - Evidencia</a>
 */
@Entity
@Table(
        name = "evidence",
        indexes = {
                @Index(name = "idx_evidence_cycle", columnList = "cycle_id")
        }
)
public class EvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "evidence_id", nullable = false, updatable = false)
    private UUID evidenceId;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_reference", length = 500)
    private String sourceReference;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "observed_period", length = 100)
    private String observedPeriod;

    @Column(name = "provenance", columnDefinition = "TEXT")
    private String provenance;

    @Column(name = "integrity_reference", length = 500)
    private String integrityReference;

    @Column(name = "quality_status", nullable = false, length = 50)
    private String qualityStatus;

    @Column(name = "limitations", columnDefinition = "TEXT")
    private String limitations;

    @Column(name = "access_classification", length = 50)
    private String accessClassification;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Constructor protegido para JPA.
     */
    protected EvidenceEntity() {
    }

    /**
     * Constructor público para crear una nueva evidencia.
     *
     * @param cycleId      identificador del ciclo asociado
     * @param sourceType   tipo de fuente (ej: "TEST_RESULT", "CODE_DIFF", "MEASUREMENT")
     * @param capturedAt   instante en que se capturó la evidencia
     * @param qualityStatus estado de calidad (ej: "VERIFIED", "ACCEPTABLE_WITH_LIMITATIONS")
     * @throws NullPointerException si cycleId, sourceType, capturedAt o qualityStatus son nulos
     */
    public EvidenceEntity(UUID cycleId, String sourceType, Instant capturedAt, String qualityStatus) {
        this.cycleId = Objects.requireNonNull(cycleId, "El identificador del ciclo es obligatorio");
        this.sourceType = Objects.requireNonNull(sourceType, "El tipo de fuente es obligatorio");
        this.capturedAt = Objects.requireNonNull(capturedAt, "El instante de captura es obligatorio");
        this.qualityStatus = Objects.requireNonNull(qualityStatus, "El estado de calidad es obligatorio");
        this.createdAt = Instant.now();
    }

    // Getters

    public UUID getEvidenceId() {
        return evidenceId;
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public String getObservedPeriod() {
        return observedPeriod;
    }

    public String getProvenance() {
        return provenance;
    }

    public String getIntegrityReference() {
        return integrityReference;
    }

    public String getQualityStatus() {
        return qualityStatus;
    }

    public String getLimitations() {
        return limitations;
    }

    public String getAccessClassification() {
        return accessClassification;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // Setters para campos opcionales

    public EvidenceEntity sourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
        return this;
    }

    public EvidenceEntity description(String description) {
        this.description = description;
        return this;
    }

    public EvidenceEntity observedPeriod(String observedPeriod) {
        this.observedPeriod = observedPeriod;
        return this;
    }

    public EvidenceEntity provenance(String provenance) {
        this.provenance = provenance;
        return this;
    }

    public EvidenceEntity integrityReference(String integrityReference) {
        this.integrityReference = integrityReference;
        return this;
    }

    public EvidenceEntity limitations(String limitations) {
        this.limitations = limitations;
        return this;
    }

    public EvidenceEntity accessClassification(String accessClassification) {
        this.accessClassification = accessClassification;
        return this;
    }
}
