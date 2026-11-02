
package com.miec.action;

import com.miec.action.PlannedActionPolicy.PlanSnapshot;
import com.miec.action.PlannedActionPolicy.PlannedAction;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Intérprete del contrato JSON v01 de acciones planificadas.
 *
 * <p>Convierte el documento persistido en
 * intervention_plan.actions a un PlanSnapshot
 * validado por PlannedActionPolicy.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>La versión debe ser exactamente "01".</li>
 *   <li>El documento debe ser un objeto JSON.</li>
 *   <li>actions debe ser un arreglo.</li>
 *   <li>Cada acción requiere UUID y tipo.</li>
 *   <li>Los identificadores no pueden repetirse.</li>
 * </ul>
 *
 * <p>No crea acciones ni modifica planes.
 */
@Component
public class PlannedActionJsonV01 {

    private final JsonMapper jsonMapper;

    /**
     * Recibe el procesador JSON configurado por Spring Boot.
     *
     * @param jsonMapper procesador JSON obligatorio
     */
    public PlannedActionJsonV01(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper);
    }

    /**
     * Interpreta las acciones almacenadas en un plan.
     *
     * @param planId identificador persistido del plan
     * @param cycleId ciclo propietario persistido
     * @param json documento JSON del plan
     * @return representación de dominio validada
     * @throws IllegalArgumentException si el contrato es inválido
     */
    public PlanSnapshot parse(
            UUID planId,
            UUID cycleId,
            String json
    ) {
        Objects.requireNonNull(planId, "planId es obligatorio");
        Objects.requireNonNull(cycleId, "cycleId es obligatorio");

        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                    "IR-009: el plan no declara acciones JSON"
            );
        }

        final JsonNode root;

        try {
            root = jsonMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "IR-009: JSON de acciones inválido",
                    exception
            );
        }

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    "IR-009: el documento debe ser un objeto JSON"
            );
        }

        JsonNode version = root.path("schemaVersion");

        if (!version.isTextual()
                || !"01".equals(version.asText())) {
            throw new IllegalArgumentException(
                    "IR-009: versión de acciones no admitida"
            );
        }

        JsonNode actions = root.path("actions");

        if (!actions.isArray()) {
            throw new IllegalArgumentException(
                    "IR-009: actions debe ser un arreglo"
            );
        }

        List<PlannedAction> plannedActions =
                new ArrayList<>();

        for (JsonNode action : actions) {
            if (!action.isObject()) {
                throw new IllegalArgumentException(
                        "IR-009: cada acción debe ser un objeto"
                );
            }

            String actionIdText = requiredText(
                    action,
                    "actionId"
            );

            String actionType = requiredText(
                    action,
                    "actionType"
            );

            final UUID actionId;

            try {
                actionId = UUID.fromString(actionIdText);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "IR-009: actionId no es un UUID válido",
                        exception
                );
            }

            plannedActions.add(
                    new PlannedAction(actionId, actionType)
            );
        }

        // PlanSnapshot detecta acciones duplicadas
        // y conserva una copia inmutable.
        return new PlanSnapshot(
                planId,
                cycleId,
                plannedActions
        );
    }

    /**
     * Obtiene un texto obligatorio de un nodo JSON.
     *
     * @param node objeto que contiene el campo
     * @param field nombre del atributo
     * @return valor textual normalizado
     */
    private static String requiredText(
            JsonNode node,
            String field
    ) {
        JsonNode value = node.path(field);

        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "IR-009: campo obligatorio inválido: " + field
            );
        }

        return value.asText().trim();
    }
}
