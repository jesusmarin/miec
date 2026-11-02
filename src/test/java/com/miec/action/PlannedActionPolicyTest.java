
package com.miec.action;

import com.miec.action.PlannedActionPolicy.ActionRequest;
import com.miec.action.PlannedActionPolicy.PlanSnapshot;
import com.miec.action.PlannedActionPolicy.PlannedAction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de pertenencia de acciones a planes.
 *
 * <p>Complementan IR-009 y verifican que no puedan
 * utilizarse identidades o tipos de acción ajenos
 * a la definición de un plan.
 */
@DisplayName(
        "MIEC-F03 IR-009 - Validación de acciones planificadas"
)
class PlannedActionPolicyTest {

    private PlannedActionPolicy policy;
    private UUID planId;
    private UUID cycleId;
    private UUID actionId;

    /**
     * Prepara identificadores independientes.
     */
    @BeforeEach
    void setUp() {
        policy = new PlannedActionPolicy();
        planId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        actionId = UUID.randomUUID();
    }

    /**
     * Construye un plan con una acción válida.
     *
     * @return plan de referencia
     */
    private PlanSnapshot validPlan() {
        return new PlanSnapshot(
                planId,
                cycleId,
                List.of(new PlannedAction(
                        actionId,
                        "PROCESS_EXECUTION"
                ))
        );
    }

    /**
     * Construye una solicitud coincidente.
     *
     * @return solicitud válida
     */
    private ActionRequest validRequest() {
        return new ActionRequest(
                planId,
                cycleId,
                actionId,
                "PROCESS_EXECUTION"
        );
    }

    @Test
    @DisplayName(
            "Debe aceptar una acción declarada en su plan"
    )
    void shouldAcceptDeclaredAction() {
        assertTrue(
                policy.evaluate(
                        validPlan(), validRequest()
                ).allowed()
        );
    }

    @Test
    @DisplayName(
            "Debe rechazar una acción no declarada en el plan"
    )
    void shouldRejectUnknownAction() {
        ActionRequest request = new ActionRequest(
                planId,
                cycleId,
                UUID.randomUUID(),
                "PROCESS_EXECUTION"
        );

        assertFalse(
                policy.evaluate(
                        validPlan(), request
                ).allowed()
        );
    }

    @Test
    @DisplayName(
            "Debe rechazar una acción de un plan diferente"
    )
    void shouldRejectDifferentPlan() {
        ActionRequest request = new ActionRequest(
                UUID.randomUUID(),
                cycleId,
                actionId,
                "PROCESS_EXECUTION"
        );

        assertFalse(
                policy.evaluate(
                        validPlan(), request
                ).allowed()
        );
    }

    @Test
    @DisplayName(
            "Debe rechazar una acción asociada a otro ciclo"
    )
    void shouldRejectDifferentCycle() {
        ActionRequest request = new ActionRequest(
                planId,
                UUID.randomUUID(),
                actionId,
                "PROCESS_EXECUTION"
        );

        assertFalse(
                policy.evaluate(
                        validPlan(), request
                ).allowed()
        );
    }

    @Test
    @DisplayName(
            "Debe rechazar un tipo de acción diferente al planificado"
    )
    void shouldRejectDifferentActionType() {
        ActionRequest request = new ActionRequest(
                planId,
                cycleId,
                actionId,
                "DELETE_SYSTEM"
        );

        assertFalse(
                policy.evaluate(
                        validPlan(), request
                ).allowed()
        );
    }

    @Test
    @DisplayName(
            "Debe rechazar la ejecución de un plan sin acciones"
    )
    void shouldRejectEmptyPlan() {
        PlanSnapshot emptyPlan = new PlanSnapshot(
                planId,
                cycleId,
                List.of()
        );

        assertFalse(
                policy.evaluate(
                        emptyPlan, validRequest()
                ).allowed()
        );
    }

    @Test
    @DisplayName(
            "Debe rechazar identificadores duplicados en un mismo plan"
    )
    void shouldRejectDuplicateActions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanSnapshot(
                        planId,
                        cycleId,
                        List.of(
                                new PlannedAction(
                                        actionId, "PROCESS_EXECUTION"
                                ),
                                new PlannedAction(
                                        actionId, "PROCESS_EXECUTION"
                                )
                        )
                )
        );
    }

    @Test
    @DisplayName(
            "Debe conservar una copia inmutable de las acciones del plan"
    )
    void shouldPreserveImmutableActions() {
        PlanSnapshot plan = validPlan();

        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.actions().add(
                        new PlannedAction(
                                UUID.randomUUID(),
                                "OTHER_ACTION"
                        )
                )
        );

        assertEquals(1, plan.actions().size());
    }
}
