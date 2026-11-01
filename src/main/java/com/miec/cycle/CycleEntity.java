
package com.miec.cycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad JPA que representa un ciclo de evolución de MIEC.
 *
 * <p>Corresponde a MIEC-F03 ENT-02 y a la tabla {@code cycle},
 * definida en Flyway V001 y reforzada mediante V002.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Identificar un ciclo y su sistema objetivo.</li>
 *   <li>Conservar su relación con un ciclo padre.</li>
 *   <li>Persistir el estado y nivel de autonomía.</li>
 *   <li>Registrar fechas de inicio, cierre y auditoría.</li>
 *   <li>Proteger las actualizaciones concurrentes.</li>
 * </ul>
 *
 * <p>Esta entidad no implementa el motor de transición
 * de estados. Esa responsabilidad corresponde a
 * {@link com.miec.domain.Cycle}.
 *
 * <p>Las restricciones de pertenencia al mismo sistema
 * y de ausencia de autorreferencia son protegidas por
 * V002. Los ciclos jerárquicos indirectos deben validarse
 * en la capa de dominio.
 */
@Entity
@Table(name = "cycle")
public class CycleEntity {

    /**
     * Identificador único del ciclo.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "cycle_id",
            nullable = false,
            updatable = false
    )
    private UUID cycleId;

    /**
     * Sistema objetivo al que pertenece el ciclo.
     */
    @Column(
            name = "system_id",
            nullable = false,
            updatable = false
    )
    private UUID systemId;

    /**
     * Número ordinal del ciclo dentro del sistema.
     */
    @Column(name = "cycle_number")
    private Integer cycleNumber;

    /**
     * Relación jerárquica de solo lectura desde JPA.
     *
     * Las modificaciones deben realizarse exclusivamente
     * mediante CycleHierarchyService.assignParent().
     */
    @Column(
            name = "parent_cycle_id",
            insertable = false,
            updatable = false
    )
    private UUID parentCycleId;

    /**
     * Estado actual del ciclo.
     */
    @Column(name = "state", nullable = false, length = 50)
    private String state;

    /**
     * Nivel de autonomía asignado.
     */
    @Column(
            name = "autonomy_level",
            nullable = false,
            length = 50
    )
    private String autonomyLevel;

    /**
     * Versión de configuración utilizada.
     */
    @Column(
            name = "configuration_version_id",
            length = 100
    )
    private String configurationVersionId;

    /**
     * Instante de inicio del ciclo.
     */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * Instante de cierre del ciclo.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Justificación del cierre.
     */
    @Column(name = "closure_reason", length = 255)
    private String closureReason;

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
     * Versión de concurrencia optimista.
     *
     * <p>Hibernate la utiliza para detectar modificaciones
     * concurrentes del mismo registro.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Constructor requerido por JPA.
     */
    protected CycleEntity() {
    }

    /**
     * Construye un nuevo ciclo para un sistema objetivo.
     *
     * <p>El ciclo se crea en estado E00_INACTIVO,
     * con autonomía A0.
     *
     * @param systemId identificador obligatorio del sistema
     * @throws NullPointerException si systemId es nulo
     */
    public CycleEntity(UUID systemId) {
        this.systemId = Objects.requireNonNull(
                systemId,
                "El identificador del sistema es obligatorio"
        );
        this.state = "E00_INACTIVO";
        this.autonomyLevel = "A0";
    }

    /**
     * Normaliza y valida un texto obligatorio.
     *
     * @param value texto recibido
     * @param field nombre del atributo
     * @param maxLength longitud máxima permitida
     * @return texto normalizado
     * @throws NullPointerException si el texto es nulo
     * @throws IllegalArgumentException si está vacío o supera
     *         la longitud máxima
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
     * Normaliza un texto opcional.
     *
     * @param value texto recibido
     * @param field nombre del atributo
     * @param maxLength longitud máxima permitida
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
     * Inicializa los campos obligatorios de auditoría
     * antes de insertar el registro.
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();

        Objects.requireNonNull(
                systemId,
                "El sistema es obligatorio"
        );

        if (state == null) {
            state = "E00_INACTIVO";
        }

        if (autonomyLevel == null) {
            autonomyLevel = "A0";
        }

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;
    }

    /**
     * Actualiza el instante de modificación.
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Obtiene el identificador del ciclo.
     *
     * @return UUID del ciclo, o null antes de persistir
     */
    public UUID getCycleId() {
        return cycleId;
    }

    /**
     * Obtiene el sistema propietario del ciclo.
     *
     * @return UUID del sistema
     */
    public UUID getSystemId() {
        return systemId;
    }

    /**
     * Obtiene el número ordinal.
     *
     * @return número del ciclo o null
     */
    public Integer getCycleNumber() {
        return cycleNumber;
    }

    /**
     * Obtiene el identificador del ciclo padre.
     *
     * @return UUID del padre o null
     */
    public UUID getParentCycleId() {
        return parentCycleId;
    }

    /**
     * Obtiene el estado persistido.
     *
     * @return estado actual
     */
    public String getState() {
        return state;
    }

    /**
     * Obtiene el nivel de autonomía.
     *
     * @return nivel actual
     */
    public String getAutonomyLevel() {
        return autonomyLevel;
    }

    /**
     * Obtiene la versión de configuración.
     *
     * @return referencia de configuración o null
     */
    public String getConfigurationVersionId() {
        return configurationVersionId;
    }

    /**
     * Obtiene el instante de inicio.
     *
     * @return instante de inicio o null
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Obtiene el instante de cierre.
     *
     * @return instante de cierre o null
     */
    public Instant getClosedAt() {
        return closedAt;
    }

    /**
     * Obtiene la justificación del cierre.
     *
     * @return motivo o null
     */
    public String getClosureReason() {
        return closureReason;
    }

    /**
     * Obtiene la fecha de creación.
     *
     * @return fecha de creación
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Obtiene la fecha de actualización.
     *
     * @return fecha de actualización
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Obtiene la versión de concurrencia optimista.
     *
     * @return versión actual del registro
     */
    public long getVersion() {
        return version;
    }

    /**
     * Establece el número ordinal del ciclo.
     *
     * @param cycleNumber número opcional
     * @return esta entidad
     */
    public CycleEntity cycleNumber(Integer cycleNumber) {
        this.cycleNumber = cycleNumber;
        return this;
    }


    /**
     * Establece el estado persistido.
     *
     * <p>Debe utilizarse después de validar la transición
     * mediante el dominio MIEC. Esta operación no autoriza
     * por sí sola ningún cambio de estado.
     *
     * @param state nuevo estado obligatorio
     * @return esta entidad
     */
    public CycleEntity state(String state) {
        this.state = requireText(state, "state", 50);
        return this;
    }

    /**
     * Establece el nivel de autonomía persistido.
     *
     * <p>La política de autonomía debe ser validada
     * por los servicios de dominio correspondientes.
     *
     * @param autonomyLevel nivel obligatorio
     * @return esta entidad
     */
    public CycleEntity autonomyLevel(String autonomyLevel) {
        this.autonomyLevel = requireText(
                autonomyLevel,
                "autonomyLevel",
                50
        );
        return this;
    }

    /**
     * Establece la referencia de configuración.
     *
     * @param configurationVersionId versión opcional
     * @return esta entidad
     */
    public CycleEntity configurationVersionId(
            String configurationVersionId
    ) {
        this.configurationVersionId = optionalText(
                configurationVersionId,
                "configurationVersionId",
                100
        );
        return this;
    }

    /**
     * Establece el instante de inicio del ciclo.
     *
     * @param startedAt instante de inicio o null
     * @return esta entidad
     */
    public CycleEntity startedAt(Instant startedAt) {
        this.startedAt = startedAt;
        return this;
    }

    /**
     * Establece el instante de cierre del ciclo.
     *
     * @param closedAt instante de cierre o null
     * @return esta entidad
     */
    public CycleEntity closedAt(Instant closedAt) {
        this.closedAt = closedAt;
        return this;
    }

    /**
     * Establece la justificación del cierre.
     *
     * @param closureReason motivo opcional
     * @return esta entidad
     */
    public CycleEntity closureReason(String closureReason) {
        this.closureReason = optionalText(
                closureReason,
                "closureReason",
                255
        );
        return this;
    }
}

