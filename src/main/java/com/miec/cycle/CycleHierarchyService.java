
package com.miec.cycle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Servicio transaccional para gestionar la jerarquía
 * de ciclos de evolución de MIEC.
 *
 * <p>Implementa IR-013: prevención de relaciones
 * circulares indirectas entre ciclos.
 *
 * <p>También respeta:
 * <ul>
 *   <li>IR-011: padre e hijo del mismo sistema.</li>
 *   <li>IR-012: prohibición de autorreferencia directa.</li>
 * </ul>
 *
 * <p>Antes de modificar la jerarquía, bloquea
 * el registro del sistema objetivo mediante
 * SELECT FOR UPDATE. Todas las operaciones de
 * modificación de jerarquía deben utilizar este
 * servicio para respetar la coordinación.
 *
 * <p>Utiliza JDBC para modificar únicamente
 * parent_cycle_id, updated_at y version.
 *
 * <p>La modificación incrementa version para
 * mantener la compatibilidad con el control de
 * concurrencia optimista de CycleEntity.
 */
@Service
public class CycleHierarchyService {

    /** Acceso JDBC a los registros persistidos. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Construye el servicio.
     *
     * @param jdbcTemplate acceso obligatorio a la base
     */
    public CycleHierarchyService(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "JdbcTemplate es obligatorio"
        );
    }

    /**
     * Referencias mínimas de un ciclo.
     *
     * @param systemId sistema propietario
     * @param parentCycleId identificador del padre
     */
    private record CycleNode(
            UUID systemId,
            UUID parentCycleId
    ) {
    }

    /**
     * Obtiene los datos jerárquicos de un ciclo.
     *
     * @param cycleId identificador consultado
     * @return nodo o null si el ciclo no existe
     */
    private CycleNode findCycle(UUID cycleId) {
        List<CycleNode> nodes = jdbcTemplate.query(
                """
                SELECT system_id, parent_cycle_id
                FROM cycle
                WHERE cycle_id = ?
                """,
                (rs, rowNum) -> new CycleNode(
                        rs.getObject("system_id", UUID.class),
                        rs.getObject("parent_cycle_id", UUID.class)
                ),
                cycleId
        );

        return nodes.isEmpty() ? null : nodes.getFirst();
    }

    /**
     * Bloquea el sistema objetivo hasta finalizar
     * la transacción.
     *
     * <p>El bloqueo serializa las operaciones de
     * este servicio sobre la jerarquía de un mismo
     * sistema. Evita que dos solicitudes creen
     * simultáneamente referencias circulares.
     *
     * @param systemId identificador del sistema
     */
    private void lockSystem(UUID systemId) {
        List<UUID> locked = jdbcTemplate.query(
                """
                SELECT system_id
                FROM system_objective
                WHERE system_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) ->
                        rs.getObject("system_id", UUID.class),
                systemId
        );

        if (locked.isEmpty()) {
            throw new IllegalStateException(
                    "El sistema objetivo no existe: " + systemId
            );
        }
    }

    /**
     * Comprueba que la asignación propuesta no genere
     * una relación circular en la jerarquía.
     *
     * <p>Recorre los antepasados del padre propuesto
     * y utiliza un conjunto de identificadores para
     * detectar ciclos preexistentes.
     *
     * @param childId ciclo que recibirá el padre
     * @param proposedParentId padre propuesto
     * @param systemId sistema al que pertenecen
     * @throws IllegalArgumentException si el padre
     *         es inválido o provoca un ciclo
     * @throws IllegalStateException si la jerarquía
     *         ya contiene una relación circular
     */
    private void validateAncestors(
            UUID childId,
            UUID proposedParentId,
            UUID systemId
    ) {
        Set<UUID> visited = new HashSet<>();
        UUID currentId = proposedParentId;

        while (currentId != null) {
            if (childId.equals(currentId)) {
                throw new IllegalArgumentException(
                        "IR-013: la asignación crea "
                                + "un ciclo jerárquico"
                );
            }

            if (!visited.add(currentId)) {
                throw new IllegalStateException(
                        "IR-013: se detectó una jerarquía "
                                + "circular preexistente"
                );
            }

            CycleNode current = findCycle(currentId);

            if (current == null) {
                throw new IllegalArgumentException(
                        "El ciclo padre o antepasado no existe: "
                                + currentId
                );
            }

            if (!systemId.equals(current.systemId())) {
                throw new IllegalArgumentException(
                        "IR-011: los ciclos pertenecen "
                                + "a sistemas diferentes"
                );
            }

            currentId = current.parentCycleId();
        }
    }

    /**
     * Asigna o elimina el padre de un ciclo.
     *
     * <p>La operación es atómica:
     * <ol>
     *   <li>Verifica que el ciclo hijo exista.</li>
     *   <li>Bloquea su sistema objetivo.</li>
     *   <li>Comprueba que siga perteneciendo al sistema.</li>
     *   <li>Recorre la jerarquía del padre propuesto.</li>
     *   <li>Actualiza la relación si es válida.</li>
     * </ol>
     *
     * <p>Una excepción impide la actualización.
     * La transacción se revierte automáticamente
     * ante excepciones de ejecución.
     *
     * @param childId ciclo que será modificado
     * @param proposedParentId padre nuevo, o null
     * @throws NullPointerException si childId es null
     * @throws IllegalArgumentException si alguno de
     *         los ciclos es inválido o genera un ciclo
     * @throws IllegalStateException si existe una
     *         inconsistencia en la jerarquía
     */
    @Transactional
    public void assignParent(
            UUID childId,
            UUID proposedParentId
    ) {
        Objects.requireNonNull(
                childId,
                "El identificador del ciclo es obligatorio"
        );

        CycleNode initialChild = findCycle(childId);

        if (initialChild == null) {
            throw new IllegalArgumentException(
                    "El ciclo hijo no existe: " + childId
            );
        }

        UUID systemId = initialChild.systemId();

        lockSystem(systemId);

        CycleNode child = findCycle(childId);

        if (child == null
                || !systemId.equals(child.systemId())) {
            throw new IllegalStateException(
                    "El ciclo cambió durante la operación"
            );
        }

        if (proposedParentId != null) {
            validateAncestors(
                    childId,
                    proposedParentId,
                    systemId
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE cycle
                SET parent_cycle_id = ?,
                    updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE cycle_id = ?
                  AND system_id = ?
                """,
                proposedParentId,
                childId,
                systemId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "No fue posible actualizar la jerarquía"
            );
        }
    }
}

