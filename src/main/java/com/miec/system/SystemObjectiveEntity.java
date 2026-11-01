
package com.miec.system;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad JPA que representa un sistema objetivo de MIEC.
 *
 * <p>Corresponde a MIEC-F03 ENT-01 y a la tabla
 * {@code system_objective}, creada por Flyway V001.
 *
 * <p>Un sistema objetivo representa el sistema que será
 * observado, evaluado o transformado mediante ciclos
 * de evolución continua.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Identificar el sistema de manera única.</li>
 *   <li>Conservar su nombre y contexto.</li>
 *   <li>Registrar restricciones de operación en JSON.</li>
 *   <li>Mantener su estado y fechas de auditoría.</li>
 * </ul>
 *
 * <p>Esta clase representa persistencia. Las reglas
 * de evolución pertenecen a la capa de dominio.
 *
 * @see jakarta.persistence.Entity
 */
@Entity
@Table(name = "system_objective")
public class SystemObjectiveEntity {

    /**
     * Identificador único del sistema.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "system_id",
            nullable = false,
            updatable = false
    )
    private UUID systemId;

    /**
     * Nombre descriptivo y obligatorio.
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Dominio funcional del sistema.
     */
    @Column(name = "domain", length = 100)
    private String domain;

    /**
     * Descripción general del sistema.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Referencia al responsable del sistema.
     */
    @Column(name = "owner_reference", length = 255)
    private String ownerReference;

    /**
     * Restricciones de operación almacenadas como JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "constraints", columnDefinition = "JSON")
    private Map<String, Object> constraints;

    /**
     * Estado actual del sistema.
     */
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    /**
     * Instante de creación.
     */
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    /**
     * Instante de última actualización.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Constructor requerido por JPA.
     */
    protected SystemObjectiveEntity() {
    }

    /**
     * Crea un sistema objetivo en estado activo.
     *
     * @param name nombre obligatorio del sistema
     * @throws NullPointerException si el nombre es nulo
     * @throws IllegalArgumentException si el nombre está vacío
     *         o supera su longitud permitida
     */
    public SystemObjectiveEntity(String name) {
        this.name = requireText(name, "name", 255);
        this.status = "ACTIVE";
    }

    /**
     * Valida un texto obligatorio.
     *
     * @param value valor recibido
     * @param field nombre del campo
     * @param maxLength longitud máxima
     * @return texto normalizado
     */
    private static String requireText(
            String value,
            String field,
            int maxLength
    ) {
        Objects.requireNonNull(value, field + " es obligatorio");

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
     * Valida y normaliza un texto opcional.
     *
     * @param value valor opcional
     * @param field nombre del campo
     * @param maxLength longitud máxima
     * @return texto normalizado o null
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
     * Inicializa las fechas antes de insertar el registro.
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();

        if (status == null) {
            status = "ACTIVE";
        }

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;
    }

    /**
     * Actualiza la fecha de modificación antes de
     * actualizar el registro.
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Obtiene el identificador persistente.
     *
     * @return UUID asignado por JPA, o null antes de persistir
     */
    public UUID getSystemId() {
        return systemId;
    }

    /**
     * Obtiene el nombre del sistema.
     *
     * @return nombre obligatorio
     */
    public String getName() {
        return name;
    }

    /**
     * Obtiene el dominio funcional.
     *
     * @return dominio o null
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Obtiene la descripción.
     *
     * @return descripción o null
     */
    public String getDescription() {
        return description;
    }

    /**
     * Obtiene la referencia del responsable.
     *
     * @return referencia o null
     */
    public String getOwnerReference() {
        return ownerReference;
    }

    /**
     * Obtiene una copia de las restricciones.
     *
     * @return mapa no modificable de restricciones, o null
     */
    public Map<String, Object> getConstraints() {
        if (constraints == null) {
            return null;
        }

        return Collections.unmodifiableMap(
                new LinkedHashMap<>(constraints)
        );
    }

    /**
     * Obtiene el estado actual.
     *
     * @return estado del sistema
     */
    public String getStatus() {
        return status;
    }

    /**
     * Obtiene el instante de creación.
     *
     * @return fecha de creación o null antes de persistir
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Obtiene el instante de actualización.
     *
     * @return fecha de actualización o null antes de persistir
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Modifica el nombre del sistema.
     *
     * @param name nuevo nombre obligatorio
     * @return esta entidad
     */
    public SystemObjectiveEntity name(String name) {
        this.name = requireText(name, "name", 255);
        return this;
    }

    /**
     * Establece el dominio funcional.
     *
     * @param domain dominio opcional
     * @return esta entidad
     */
    public SystemObjectiveEntity domain(String domain) {
        this.domain = optionalText(domain, "domain", 100);
        return this;
    }

    /**
     * Establece la descripción del sistema.
     *
     * @param description descripción opcional
     * @return esta entidad
     */
    public SystemObjectiveEntity description(
            String description
    ) {
        this.description = description;
        return this;
    }

    /**
     * Establece la referencia del responsable.
     *
     * @param ownerReference identificador o referencia
     * @return esta entidad
     */
    public SystemObjectiveEntity ownerReference(
            String ownerReference
    ) {
        this.ownerReference = optionalText(
                ownerReference,
                "ownerReference",
                255
        );
        return this;
    }

    /**
     * Establece las restricciones de operación.
     *
     * <p>Se realiza una copia para evitar modificaciones
     * externas del mapa recibido.
     *
     * @param constraints restricciones serializables como JSON
     * @return esta entidad
     */
    public SystemObjectiveEntity constraints(
            Map<String, Object> constraints
    ) {
        this.constraints = constraints == null
                ? null
                : new LinkedHashMap<>(constraints);

        return this;
    }

    /**
     * Cambia el estado del sistema.
     *
     * <p>Los estados permitidos y sus transiciones
     * deberán validarse en la capa de dominio MIEC.
     *
     * @param status nuevo estado obligatorio
     * @return esta entidad
     */
    public SystemObjectiveEntity status(String status) {
        this.status = requireText(status, "status", 50);
        return this;
    }
}
