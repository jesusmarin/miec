
package com.miec.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecisionActionTest {

    @Test
    @DisplayName("Debe definir exactamente once acciones de decisión")
    void shouldDefineElevenDecisionActions() {
        assertEquals(11, DecisionAction.values().length);
    }

    @Test
    @DisplayName("Debe contener todas las acciones definidas en el contrato F07")
    void shouldContainAllDefinedDecisionActions() {
        assertAll(
                () -> assertNotNull(DecisionAction.CONTINUE_PLAN),
                () -> assertNotNull(DecisionAction.ADJUST_PLAN),
                () -> assertNotNull(DecisionAction.REFORMULATE_HYPOTHESIS),
                () -> assertNotNull(DecisionAction.COLLECT_EVIDENCE),
                () -> assertNotNull(DecisionAction.ESTABLISH_BASELINE),
                () -> assertNotNull(DecisionAction.MAINTAIN_CURRENT_STATE),
                () -> assertNotNull(DecisionAction.REVERT_CHANGE),
                () -> assertNotNull(DecisionAction.REQUEST_HUMAN_REVIEW),
                () -> assertNotNull(DecisionAction.SUSPEND_CYCLE),
                () -> assertNotNull(DecisionAction.ABORT_INTERVENTION),
                () -> assertNotNull(DecisionAction.CLOSE_CYCLE)
        );
    }

    @Test
    @DisplayName("Debe conservar el orden de las acciones definido en el contrato")
    void shouldPreserveDefinedActionOrder() {
        assertArrayEquals(
                new DecisionAction[]{
                        DecisionAction.CONTINUE_PLAN,
                        DecisionAction.ADJUST_PLAN,
                        DecisionAction.REFORMULATE_HYPOTHESIS,
                        DecisionAction.COLLECT_EVIDENCE,
                        DecisionAction.ESTABLISH_BASELINE,
                        DecisionAction.MAINTAIN_CURRENT_STATE,
                        DecisionAction.REVERT_CHANGE,
                        DecisionAction.REQUEST_HUMAN_REVIEW,
                        DecisionAction.SUSPEND_CYCLE,
                        DecisionAction.ABORT_INTERVENTION,
                        DecisionAction.CLOSE_CYCLE
                },
                DecisionAction.values()
        );
    }

    @Test
    @DisplayName("Debe permitir recuperar cada acción mediante su nombre")
    void shouldRetrieveActionsByName() {
        for (DecisionAction action : DecisionAction.values()) {
            assertEquals(action, DecisionAction.valueOf(action.name()));
        }
    }
}

