
package com.miec.action;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Política de pertenencia de acciones a planes MIEC-F03.
 *
 * <p>Complementa IR-009 verificando que la operación
 * solicitada esté definida en el plan correspondiente.
 *
 * <p>No concede autorizaciones ni ejecuta acciones.
 * La verificación de autorizaciones corresponde a
 * AuthorizationValidityPolicy.
 *
 * <p>Los datos del plan se reciben como estructuras
 * de dominio, sin depender del formato JSON persistido.
 */
@Component
public class PlannedActionPolicy {

    /**
     * Definición de una acción planificada.
     *
     * @param actionId identidad de la acción planificada
     * @param actionType tipo de operación
     */
    public record PlannedAction(
            UUID actionId,
            String actionType
    ) {
        public PlannedAction {
            Objects.requireNonNull(
                    actionId, "actionId es obligatorio"
            );
            actionType = requireText(actionType, "actionType");
        }
    }

    /**
     * Representación mínima de un plan.
     *
     * @param planId identificador del plan
     * @param cycleId ciclo propietario
     * @param actions acciones declaradas
     */
    public record PlanSnapshot(
            UUID planId,
            UUID cycleId,
            List<PlannedAction> actions
    ) {
        public PlanSnapshot {
            Objects.requireNonNull(
                    planId, "planId es obligatorio"
            );
            Objects.requireNonNull(
                    cycleId, "cycleId es obligatorio"
            );
            Objects.requireNonNull(
                    actions, "actions es obligatorio"
            );

            actions = List.copyOf(actions);

            Set<UUID> identifiers = new HashSet<>();

            for (PlannedAction action : actions) {
                if (!identifiers.add(action.actionId())) {
                    throw new IllegalArgumentException(
                            "Acción duplicada en el plan: "
                                    + action.actionId()
                    );
                }
            }
        }
    }

    /**
     * Operación cuya pertenencia al plan se comprueba.
     *
     * @param planId plan solicitado
     * @param cycleId ciclo solicitado
     * @param actionId identidad de acción planificada
     * @param actionType tipo requerido
     */
    public record ActionRequest(
            UUID planId,
            UUID cycleId,
            UUID actionId,
            String actionType
    ) {
        public ActionRequest {
            Objects.requireNonNull(planId, "planId es obligatorio");
            Objects.requireNonNull(cycleId, "cycleId es obligatorio");
            Objects.requireNonNull(actionId, "actionId es obligatorio");
            actionType = requireText(actionType, "actionType");
        }
    }

    /**
     * Resultado justificado de validación.
     *
     * @param allowed indica pertenencia válida
     * @param reason motivo de la decisión
     */
    public record Decision(
            boolean allowed,
            String reason
    ) {
        public Decision {
            Objects.requireNonNull(
                    reason, "reason es obligatorio"
            );
        }
    }

    /**
     * Comprueba que la acción solicitada exista
     * en el plan y corresponda a su tipo declarado.
     *
     * <p>Las coincidencias de identidad y tipo
     * son exactas; no se admiten comodines.
     *
     * @param plan definición del plan
     * @param request acción solicitada
     * @return decisión justificada
     */
    public Decision evaluate(
            PlanSnapshot plan,
            ActionRequest request
    ) {
        Objects.requireNonNull(plan, "plan es obligatorio");
        Objects.requireNonNull(request, "request es obligatorio");

        if (!plan.planId().equals(request.planId())) {
            return deny("La acción corresponde a otro plan");
        }

        if (!plan.cycleId().equals(request.cycleId())) {
            return deny("La acción corresponde a otro ciclo");
        }

        for (PlannedAction action : plan.actions()) {
            if (action.actionId().equals(request.actionId())) {
                if (!action.actionType()
                        .equals(request.actionType())) {
                    return deny(
                            "El tipo de acción no coincide "
                                    + "con el plan"
                    );
                }

                return new Decision(
                        true,
                        "La acción está declarada en el plan"
                );
            }
        }

        return deny("La acción no existe en el plan");
    }

    /**
     * Construye una denegación.
     *
     * @param reason justificación
     * @return decisión negativa
     */
    private Decision deny(String reason) {
        return new Decision(false, reason);
    }

    /**
     * Normaliza un identificador textual obligatorio.
     *
     * @param value texto recibido
     * @param field nombre del atributo
     * @return texto normalizado
     */
    private static String requireText(
            String value,
            String field
    ) {
        Objects.requireNonNull(
                value, field + " es obligatorio"
        );

        String normalized = value.trim();

        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new IllegalArgumentException(
                    field + " debe contener entre 1 y 100 caracteres"
            );
        }

        return normalized;
    }
}

