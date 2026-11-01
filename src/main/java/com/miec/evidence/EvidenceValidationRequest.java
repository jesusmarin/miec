package com.miec.evidence;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Solicitud de validación de suficiencia de evidencia (MIEC-F02, MD-01).
 *
 * <p>Representa la entrada del proceso que determina si la evidencia
 * disponible permite emitir una evaluación conforme a los criterios
 * establecidos.
 *
 * <p>Este contrato no transporta ni permite declarar la suficiencia:
 * {@link EvidenceSufficiency} es el resultado de la validación, nunca un
 * dato de entrada (F07-R02).
 *
 * <p>Invariantes:
 * <ul>
 *   <li>El ciclo y el objetivo son obligatorios: sin objetivo no existe
 *       criterio de pertinencia de la evidencia.</li>
 *   <li>La lista de evidencias puede estar vacía. La ausencia de evidencia
 *       es una entrada válida y debe conducir a
 *       {@link EvidenceSufficiency#NOT_EVALUABLE} (EV-0), no a un error
 *       de construcción (F02 §3.7).</li>
 *   <li>No se admiten identificadores nulos ni duplicados: la duplicación
 *       podría simular una corroboración inexistente.</li>
 *   <li>La versión de criterios es obligatoria: toda evaluación debe poder
 *       identificarse por la versión de reglas aplicada (F07-R12).</li>
 * </ul>
 *
 * @param cycleId           identificador del ciclo que solicita la validación
 * @param objectiveId       identificador del objetivo frente al cual se
 *                          evaluará la pertinencia de la evidencia
 * @param evidenceIds       identificadores de las evidencias aportadas;
 *                          puede estar vacía, nunca ser nula
 * @param criteriaVersionId versión de los criterios de validación aplicables
 * @param requestedAt       instante en que se formula la solicitud
 */
public record EvidenceValidationRequest(
        UUID cycleId,
        UUID objectiveId,
        List<UUID> evidenceIds,
        String criteriaVersionId,
        Instant requestedAt
) {

    /**
     * Constructor compacto: valida invariantes y normaliza los datos.
     *
     * <p>Aplica {@code trim()} a la versión de criterios y realiza una
     * copia defensiva e inmutable de la lista de evidencias, preservando
     * el orden recibido.
     *
     * @throws NullPointerException     si {@code cycleId}, {@code objectiveId},
     *                                  {@code evidenceIds},
     *                                  {@code criteriaVersionId} o
     *                                  {@code requestedAt} son nulos
     * @throws IllegalArgumentException si {@code criteriaVersionId} está en
     *                                  blanco, o si {@code evidenceIds}
     *                                  contiene elementos nulos o duplicados
     */
    public EvidenceValidationRequest {
        Objects.requireNonNull(cycleId,
                "El identificador del ciclo es obligatorio");

        Objects.requireNonNull(objectiveId,
                "El identificador del objetivo es obligatorio");

        evidenceIds = validateEvidenceIds(evidenceIds);

        Objects.requireNonNull(criteriaVersionId,
                "La versión de criterios es obligatoria");

        if (criteriaVersionId.isBlank()) {
            throw new IllegalArgumentException(
                    "La versión de criterios es obligatoria");
        }

        criteriaVersionId = criteriaVersionId.trim();

        Objects.requireNonNull(requestedAt,
                "El instante de solicitud es obligatorio");
    }

    /**
     * Valida y normaliza la lista de identificadores de evidencia.
     *
     * <p>Admite la lista vacía como representación explícita de la
     * ausencia de evidencia. Rechaza elementos nulos y duplicados.
     *
     * @param ids lista aportada por el solicitante
     * @return copia inmutable de la lista, en el orden recibido
     * @throws NullPointerException     si la lista es nula
     * @throws IllegalArgumentException si contiene nulos o duplicados
     */
    private static List<UUID> validateEvidenceIds(List<UUID> ids) {
        Objects.requireNonNull(ids,
                "La lista de evidencias es obligatoria; "
                        + "use una lista vacía si no hay evidencia disponible");

        Set<UUID> seen = new HashSet<>();

        for (UUID id : ids) {
            if (id == null) {
                throw new IllegalArgumentException(
                        "La lista de evidencias no puede contener elementos nulos");
            }

            if (!seen.add(id)) {
                throw new IllegalArgumentException(
                        "Identificador de evidencia duplicado: " + id);
            }
        }

        return List.copyOf(ids);
    }

    /**
     * Indica si la solicitud se formuló sin ninguna evidencia aportada.
     *
     * <p>Es un atajo de lectura, no un juicio sobre la suficiencia: la
     * clasificación definitiva corresponde al validador.
     *
     * @return {@code true} si no se aportó ninguna evidencia
     */
    public boolean hasNoEvidence() {
        return evidenceIds.isEmpty();
    }

    /**
     * Devuelve la cantidad de evidencias aportadas.
     *
     * <p>El conteo no mide suficiencia ni independencia: varias evidencias
     * pueden provenir de una misma fuente.
     *
     * @return número de identificadores de evidencia recibidos
     */
    public int evidenceCount() {
        return evidenceIds.size();
    }
}