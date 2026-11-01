
package com.miec.objective;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad JPA que representa un objetivo de MIEC.
 *
 * <p>Corresponde a MIEC-F03 ENT-03 y a la tabla
 * {@code objective}, definida en Flyway V001
 * y reforzada mediante V002.
 *
 * <p>Un objetivo establece el resultado que se pretende
 * alcanzar en un sistema mediante los ciclos de evolución
 * continua.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Identificar el objetivo y su sistema.</li>
 *   <li>Conservar su descripción y criterios de éxito.</li>
 *   <li>Registrar prioridad, vigencia y estado.</li>
 *   <li>Identificar la versión y el creador.</li>
 *   <li>Registrar su fecha de creación.</li>
 * </ul>
 *
 * <p>La asociación entre objetivos y ciclos se representa
 * mediante la tabla {@code cycle_objective}.
 *
 * <p>Esta entidad no determina automáticamente que un
 * objetivo se haya cumplido. Dicha evaluación corresponde
 * al dominio MIEC y debe sustentarse en evidencias.
 *
 * @see jakarta.persistence.Entity
 */
@Entity
@Table(name = "objective")
public class ObjectiveEntity {

    /**
     * Identificador único del objetivo.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "objective_id",
            nullable = false,
            updatable = false
    )
    private UUID objectiveId;

    /**
     * Identificador del sistema propietario del objetivo.
     */
    @Column(
            name = "system_id",
            nullable = false,
            updatable = false
    )
    private UUID systemId;

    /**
     * Nombre descriptivo del objetivo.
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Descripción detallada del resultado esperado.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Criterios para determinar el cumplimiento del objetivo.
     */
    @Column(name = "success_criteria", columnDefinition = "TEXT")
    private String successCriteria;

    /**
     * Prioridad relativa del objetivo.
     *
     * <p>Su valor inicial es cero.
     */
    @Column(name = "priority")
    private Integer priority;

    /**
     * Estado actual del objetivo.
     *
     * <p>Se inicializa como ACTIVE.
     */
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    /**
     * Inicio opcional del período de vigencia.
     */
    @Column(name = "valid_from")
    private Instant validFrom;

    /**
     * Final opcional del período de vigencia.
     */
    @Column(name = "valid_until")
    private Instant validUntil;

    /**
     * Identificador funcional de la versión del objetivo.
     *
     * <p>No representa una versión JPA de concurrencia.
     */
    @Column(name = "version_id", length = 100)
    private String versionId;

    /**
     * Referencia a quien creó el objetivo.
     */
    @Column(name = "created_by", length = 255)
    private String createdBy;

    /**
     * Instante de creación del registro.
     */
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    /**
     * Constructor protegido requerido por JPA.
     */
    protected ObjectiveEntity() {
    }

    /**
     * Construye un objetivo asociado a un sistema.
     *
     * <p>El objetivo se inicializa como ACTIVE
     * y con prioridad cero.
     *
     * @param systemId identificador obligatorio del sistema
     * @param name nombre obligatorio del objetivo
     * @throws NullPointerException si alguno es nulo
     * @throws IllegalArgumentException si el nombre está vacío
     *         o supera la longitud permitida
     */
    public ObjectiveEntity(UUID systemId, String name) {
        this.systemId = Objects.requireNonNull(
                systemId,
                "El identificador del sistema es obligatorio"
        );
        this.name = requireText(name, "name", 255);
        this.priority = 0;
        this.status = "ACTIVE";
    }

    /**
     * Normaliza y valida texto obligatorio.
     *
     * @param value valor recibido
     * @param field nombre del atributo
     * @param maxLength longitud máxima permitida
     * @return valor normalizado
     * @throws NullPointerException si el valor es nulo
     * @throws IllegalArgumentException si está vacío
     *         o supera la longitud máxima
     */
    private static String requireText(
            String value,
            String field,
            int maxLength
    ) {
        Objects.requireNonNull(
                value,
                field + " es obligatorio"
        );

        String normalized = value.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " no puede estar vacío"
            );
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " supera " + maxLength + " caracteres"
            );
        }

        return normalized;
    }

    /**
     * Normaliza texto opcional.
     *
     * @param value valor recibido
     * @param field nombre del atributo
     * @param maxLength longitud máxima permitida
     * @return valor normalizado o null
     * @throws IllegalArgumentException si supera el máximo
     */
    private static String optionalText(
            String value,
            String field,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " supera " + maxLength + " caracteres"
            );
        }

        return normalized;
    }

    /**
     * Inicializa los valores obligatorios y la fecha
     * de creación antes de insertar el registro.
     */
    @PrePersist
    void onCreate() {
        Objects.requireNonNull(
                systemId,
                "El sistema es obligatorio"
        );

        name = requireText(name, "name", 255);

        if (priority == null) {
            priority = 0;
        }

        if (status == null) {
            status = "ACTIVE";
        }

        validateValidityPeriod();

        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Comprueba la coherencia del período de vigencia.
     *
     * <p>Cuando ambas fechas están presentes, el inicio
     * no puede ser posterior a la finalización.
     *
     * @throws IllegalArgumentException si las fechas
     *         presentan un orden incorrecto
     */
    private void validateValidityPeriod() {
        if (validFrom != null
                && validUntil != null
                && validFrom.isAfter(validUntil)) {
            throw new IllegalArgumentException(
                    "La vigencia inicial no puede ser "
                            + "posterior a la vigencia final"
            );
        }
    }

    /**
     * Obtiene el identificador del objetivo.
     *
     * @return UUID del objetivo o null antes de persistir
     */
    public UUID getObjectiveId() {
        return objectiveId;
    }

    /**
     * Obtiene el identificador del sistema.
     *
     * @return UUID del sistema
     */
    public UUID getSystemId() {
        return systemId;
    }

    /**
     * Obtiene el nombre del objetivo.
     *
     * @return nombre del objetivo
     */
    public String getName() {
        return name;
    }

    /**
     * Obtiene su descripción.
     *
     * @return descripción o null
     */
    public String getDescription() {
        return description;
    }

    /**
     * Obtiene los criterios de éxito.
     *
     * @return criterios o null
     */
    public String getSuccessCriteria() {
        return successCriteria;
    }

    /**
     * Obtiene la prioridad.
     *
     * @return prioridad actual o null si fue cargada así
     */
    public Integer getPriority() {
        return priority;
    }

    /**
     * Obtiene el estado persistido.
     *
     * @return estado del objetivo
     */
    public String getStatus() {
        return status;
    }

    /**
     * Obtiene el inicio de vigencia.
     *
     * @return instante de inicio o null
     */
    public Instant getValidFrom() {
        return validFrom;
    }

    /**
     * Obtiene el final de vigencia.
     *
     * @return instante final o null
     */
    public Instant getValidUntil() {
        return validUntil;
    }

    /**
     * Obtiene la referencia de versión.
     *
     * @return identificador de versión o null
     */
    public String getVersionId() {
        return versionId;
    }

    /**
     * Obtiene la referencia del creador.
     *
     * @return identificador del creador o null
     */
    public String getCreatedBy() {
        return createdBy;
    }

    /**
     * Obtiene la fecha de creación.
     *
     * @return instante de creación o null antes de insertar
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Modifica el nombre del objetivo.
     *
     * @param name nuevo nombre obligatorio
     * @return esta entidad
     */
    public ObjectiveEntity name(String name) {
        this.name = requireText(name, "name", 255);
        return this;
    }

    /**
     * Modifica la descripción del objetivo.
     *
     * @param description descripción opcional
     * @return esta entidad
     */
    public ObjectiveEntity description(String description) {
        this.description = description;
        return this;
    }

    /**
     * Establece los criterios de éxito.
     *
     * <p>Estos criterios podrán utilizarse posteriormente
     * en la evaluación del cumplimiento del objetivo.
     *
     * @param successCriteria criterios opcionales
     * @return esta entidad
     */
    public ObjectiveEntity successCriteria(
            String successCriteria
    ) {
        this.successCriteria = successCriteria;
        return this;
    }

    /**
     * Establece la prioridad del objetivo.
     *
     * <p>Su interpretación y escala corresponden
     * a las políticas del dominio MIEC.
     *
     * @param priority prioridad, o null para usar cero
     * @return esta entidad
     */
    public ObjectiveEntity priority(Integer priority) {
        this.priority = priority == null ? 0 : priority;
        return this;
    }

    /**
     * Establece el estado del objetivo.
     *
     * <p>Las transiciones y estados permitidos deben
     * validarse mediante las reglas del dominio MIEC.
     *
     * @param status estado obligatorio
     * @return esta entidad
     */
    public ObjectiveEntity status(String status) {
        this.status = requireText(status, "status", 50);
        return this;
    }

    /**
     * Establece el inicio del período de vigencia.
     *
     * @param validFrom instante inicial o null
     * @return esta entidad
     * @throws IllegalArgumentException si entra en conflicto
     *         con la fecha final existente
     */
    public ObjectiveEntity validFrom(Instant validFrom) {
        Instant previous = this.validFrom;
        this.validFrom = validFrom;

        try {
            validateValidityPeriod();
        } catch (IllegalArgumentException exception) {
            this.validFrom = previous;
            throw exception;
        }

        return this;
    }

    /**
     * Establece el fin del período de vigencia.
     *
     * @param validUntil instante final o null
     * @return esta entidad
     * @throws IllegalArgumentException si entra en conflicto
     *         con la fecha inicial existente
     */
    public ObjectiveEntity validUntil(Instant validUntil) {
        Instant previous = this.validUntil;
        this.validUntil = validUntil;

        try {
            validateValidityPeriod();
        } catch (IllegalArgumentException exception) {
            this.validUntil = previous;
            throw exception;
        }

        return this;
    }

    /**
     * Establece el identificador funcional de versión.
     *
     * @param versionId referencia opcional
     * @return esta entidad
     */
    public ObjectiveEntity versionId(String versionId) {
        this.versionId = optionalText(
                versionId,
                "versionId",
                100
        );
        return this;
    }

    /**
     * Establece la referencia de quien creó el objetivo.
     *
     * @param createdBy referencia opcional del creador
     * @return esta entidad
     */
    public ObjectiveEntity createdBy(String createdBy) {
        this.createdBy = optionalText(
                createdBy,
                "createdBy",
                255
        );
        return this;
    }
}

