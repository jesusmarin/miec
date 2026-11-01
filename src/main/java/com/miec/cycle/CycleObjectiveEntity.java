
package com.miec.cycle;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

/**
 * Entidad JPA que representa la asociación entre un ciclo
 * de evolución y un objetivo del Modelo Integral de
 * Evolución Continua (MIEC).
 *
 * <p>Corresponde a la tabla {@code cycle_objective},
 * creada en Flyway V001 y fortalecida en V002.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Relacionar un ciclo con un objetivo.</li>
 *   <li>Conservar la identidad compuesta de la relación.</li>
 *   <li>Identificar el sistema al que pertenecen ambos.</li>
 *   <li>Permitir la trazabilidad de objetivos por ciclo.</li>
 * </ul>
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El ciclo es obligatorio.</li>
 *   <li>El objetivo es obligatorio.</li>
 *   <li>El sistema es obligatorio.</li>
 *   <li>Una pareja ciclo-objetivo no puede duplicarse.</li>
 *   <li>El ciclo y el objetivo deben pertenecer al
 *       sistema identificado por {@code system_id}.</li>
 * </ul>
 *
 * <p>La última invariante se garantiza mediante las
 * restricciones compuestas de Flyway V002:
 * {@code FK_CO_CYCLE_SYSTEM} y
 * {@code FK_CO_OBJECTIVE_SYSTEM}.
 *
 * <p>La asociación mantiene identificadores UUID para
 * evitar acoplamiento innecesario entre las entidades
 * JPA actuales. Las comprobaciones de existencia
 * y pertenencia entre tablas corresponden a la base
 * de datos y a los servicios de aplicación.
 *
 * @see CycleObjectiveId
 * @see CycleEntity
 */
@Entity
@Table(name = "cycle_objective")
public class CycleObjectiveEntity {

    /**
     * Clave primaria compuesta de ciclo y objetivo.
     */
    @EmbeddedId
    private CycleObjectiveId id;

    /**
     * Identificador común del sistema objetivo.
     *
     * <p>Forma parte de las claves foráneas compuestas
     * incorporadas en V002, pero no de la clave primaria.
     */
    @Column(
            name = "system_id",
            nullable = false,
            updatable = false
    )
    private UUID systemId;

    /**
     * Constructor protegido requerido por JPA.
     */
    protected CycleObjectiveEntity() {
    }

    /**
     * Construye una asociación válida entre ciclo,
     * objetivo y sistema.
     *
     * <p>Este constructor valida que los identificadores
     * estén presentes, pero no puede demostrar por sí solo
     * que el ciclo y el objetivo existan y pertenezcan
     * al mismo sistema.
     *
     * <p>La integridad completa es comprobada mediante
     * las claves foráneas de V002.
     *
     * @param cycleId identificador obligatorio del ciclo
     * @param objectiveId identificador obligatorio del objetivo
     * @param systemId identificador obligatorio del sistema
     * @throws NullPointerException si algún identificador es nulo
     */
    public CycleObjectiveEntity(
            UUID cycleId,
            UUID objectiveId,
            UUID systemId
    ) {
        this.id = new CycleObjectiveId(cycleId, objectiveId);
        this.systemId = Objects.requireNonNull(
                systemId,
                "El identificador del sistema es obligatorio"
        );
    }

    /**
     * Obtiene la clave primaria compuesta.
     *
     * @return identificador compuesto
     */
    public CycleObjectiveId getId() {
        return id;
    }

    /**
     * Obtiene el identificador del ciclo.
     *
     * @return UUID del ciclo
     */
    public UUID getCycleId() {
        return id == null ? null : id.getCycleId();
    }

    /**
     * Obtiene el identificador del objetivo.
     *
     * @return UUID del objetivo
     */
    public UUID getObjectiveId() {
        return id == null ? null : id.getObjectiveId();
    }

    /**
     * Obtiene el identificador común del sistema.
     *
     * @return UUID del sistema
     */
    public UUID getSystemId() {
        return systemId;
    }

    /**
     * Comprueba si la entidad representa una pareja
     * específica de ciclo y objetivo.
     *
     * <p>La operación no realiza consultas a la base
     * de datos. Compara únicamente sus identificadores.
     *
     * @param cycleId identificador del ciclo consultado
     * @param objectiveId identificador del objetivo consultado
     * @return true si la pareja coincide con esta asociación
     */
    public boolean matches(
            UUID cycleId,
            UUID objectiveId
    ) {
        return id != null
                && Objects.equals(id.getCycleId(), cycleId)
                && Objects.equals(id.getObjectiveId(), objectiveId);
    }
}

