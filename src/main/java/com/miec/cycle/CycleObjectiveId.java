
package com.miec.cycle;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Identificador compuesto de una asociación ciclo-objetivo.
 *
 * <p>Representa la clave primaria de la tabla
 * {@code cycle_objective}, formada por:
 * <ul>
 *   <li>{@code cycle_id}: identificador del ciclo.</li>
 *   <li>{@code objective_id}: identificador del objetivo.</li>
 * </ul>
 *
 * <p>La identidad es independiente de {@code system_id},
 * que se utiliza para comprobar la integridad referencial
 * mediante las claves foráneas de Flyway V002.
 *
 * <p>Esta clase implementa {@link Serializable},
 * {@link #equals(Object)} y {@link #hashCode()}
 * conforme a los requisitos de claves compuestas JPA.
 *
 * <p>Los identificadores son inmutables una vez construidos
 * por la aplicación. El constructor sin argumentos existe
 * exclusivamente para JPA.
 *
 * @see CycleObjectiveEntity
 */
@Embeddable
public class CycleObjectiveId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Identificador del ciclo asociado.
     */
    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    /**
     * Identificador del objetivo asociado.
     */
    @Column(name = "objective_id", nullable = false)
    private UUID objectiveId;

    /**
     * Constructor requerido por JPA.
     */
    protected CycleObjectiveId() {
    }

    /**
     * Construye una clave primaria compuesta válida.
     *
     * @param cycleId identificador obligatorio del ciclo
     * @param objectiveId identificador obligatorio del objetivo
     * @throws NullPointerException si algún identificador es nulo
     */
    public CycleObjectiveId(
            UUID cycleId,
            UUID objectiveId
    ) {
        this.cycleId = Objects.requireNonNull(
                cycleId,
                "El identificador del ciclo es obligatorio"
        );

        this.objectiveId = Objects.requireNonNull(
                objectiveId,
                "El identificador del objetivo es obligatorio"
        );
    }

    /**
     * Obtiene el identificador del ciclo.
     *
     * @return UUID del ciclo
     */
    public UUID getCycleId() {
        return cycleId;
    }

    /**
     * Obtiene el identificador del objetivo.
     *
     * @return UUID del objetivo
     */
    public UUID getObjectiveId() {
        return objectiveId;
    }

    /**
     * Compara esta clave con otra por sus dos identificadores.
     *
     * @param other objeto que se desea comparar
     * @return true si ambas claves representan la misma asociación
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof CycleObjectiveId that)) {
            return false;
        }

        return Objects.equals(cycleId, that.cycleId)
                && Objects.equals(objectiveId, that.objectiveId);
    }

    /**
     * Calcula el código hash de la clave compuesta.
     *
     * @return hash basado en ciclo y objetivo
     */
    @Override
    public int hashCode() {
        return Objects.hash(cycleId, objectiveId);
    }

    /**
     * Devuelve una representación diagnóstica de la clave.
     *
     * @return representación textual de ambos identificadores
     */
    @Override
    public String toString() {
        return "CycleObjectiveId{"
                + "cycleId=" + cycleId
                + ", objectiveId=" + objectiveId
                + '}';
    }
}

