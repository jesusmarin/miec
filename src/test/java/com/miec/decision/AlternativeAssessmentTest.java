
package com.miec.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlternativeAssessmentTest {

    private AlternativeAssessment createValidAssessment() {
        return new AlternativeAssessment(
                DecisionAction.CONTINUE_PLAN,
                true,
                " Alternativa evaluada ",
                " Mejorar los resultados ",
                List.of(" Riesgo operativo "),
                " 100 unidades ",
                true
        );
    }

    @Test
    @DisplayName("Debe crear una alternativa admisible con datos válidos")
    void shouldCreateAdmissibleAlternative() {
        AlternativeAssessment assessment = createValidAssessment();

        assertNotNull(assessment);
        assertEquals(DecisionAction.CONTINUE_PLAN, assessment.action());
        assertTrue(assessment.admissible());
        assertEquals("Alternativa evaluada", assessment.reason());
        assertEquals("Mejorar los resultados", assessment.expectedBenefit());
        assertEquals("100 unidades", assessment.estimatedCost());
        assertTrue(assessment.reversible());
    }

    @Test
    @DisplayName("Debe crear una alternativa inadmisible sin beneficio esperado")
    void shouldCreateInadmissibleAlternativeWithoutExpectedBenefit() {
        AlternativeAssessment assessment = new AlternativeAssessment(
                DecisionAction.ABORT_INTERVENTION,
                false,
                "Incumple una restricción de seguridad",
                null,
                List.of(),
                null,
                false
        );

        assertFalse(assessment.admissible());
        assertNull(assessment.expectedBenefit());
        assertFalse(assessment.reversible());
    }

    @Test
    @DisplayName("Debe rechazar una acción nula")
    void shouldRejectNullAction() {
        assertThrows(NullPointerException.class,
                () -> new AlternativeAssessment(
                        null, true, "Razón",
                        "Beneficio", List.of(), null, true));
    }

    @Test
    @DisplayName("Debe rechazar una razón nula o vacía")
    void shouldRejectInvalidReason() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AlternativeAssessment(
                                DecisionAction.CONTINUE_PLAN,
                                true, null, "Beneficio",
                                List.of(), null, true)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AlternativeAssessment(
                                DecisionAction.CONTINUE_PLAN,
                                true, "   ", "Beneficio",
                                List.of(), null, true))
        );
    }

    @Test
    @DisplayName("Debe rechazar una alternativa admisible sin beneficio esperado")
    void shouldRejectAdmissibleAlternativeWithoutExpectedBenefit() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AlternativeAssessment(
                                DecisionAction.CONTINUE_PLAN,
                                true, "Razón", null,
                                List.of(), null, true)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AlternativeAssessment(
                                DecisionAction.CONTINUE_PLAN,
                                true, "Razón", "   ",
                                List.of(), null, true))
        );
    }

    @Test
    @DisplayName("Debe permitir una alternativa inadmisible sin beneficio esperado")
    void shouldAllowInadmissibleAlternativeWithoutExpectedBenefit() {
        AlternativeAssessment assessment = new AlternativeAssessment(
                DecisionAction.SUSPEND_CYCLE,
                false,
                "No cumple las condiciones de autorización",
                null,
                List.of(),
                null,
                true
        );

        assertNotNull(assessment);
        assertFalse(assessment.admissible());
    }

    @Test
    @DisplayName("Debe rechazar una lista de riesgos nula")
    void shouldRejectNullIdentifiedRisks() {
        assertThrows(NullPointerException.class,
                () -> new AlternativeAssessment(
                        DecisionAction.CONTINUE_PLAN,
                        true, "Razón", "Beneficio",
                        null, null, true));
    }

    @Test
    @DisplayName("Debe rechazar riesgos nulos o vacíos dentro de la lista")
    void shouldRejectInvalidRiskElements() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AlternativeAssessment(
                                DecisionAction.CONTINUE_PLAN,
                                true, "Razón", "Beneficio",
                                Arrays.asList("Riesgo válido", null),
                                null, true)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AlternativeAssessment(
                                DecisionAction.CONTINUE_PLAN,
                                true, "Razón", "Beneficio",
                                List.of("   "), null, true))
        );
    }

    @Test
    @DisplayName("Debe permitir una lista de riesgos vacía")
    void shouldAllowEmptyIdentifiedRisks() {
        AlternativeAssessment assessment = new AlternativeAssessment(
                DecisionAction.MAINTAIN_CURRENT_STATE,
                true,
                "Alternativa evaluada",
                "Conservar estabilidad",
                List.of(),
                null,
                true
        );

        assertTrue(assessment.identifiedRisks().isEmpty());
    }

    @Test
    @DisplayName("Debe rechazar un costo estimado vacío")
    void shouldRejectBlankEstimatedCost() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlternativeAssessment(
                        DecisionAction.CONTINUE_PLAN,
                        true, "Razón", "Beneficio",
                        List.of(), "   ", true));
    }

    @Test
    @DisplayName("Debe permitir un costo estimado nulo")
    void shouldAllowNullEstimatedCost() {
        AlternativeAssessment assessment = new AlternativeAssessment(
                DecisionAction.CONTINUE_PLAN,
                true,
                "Alternativa evaluada",
                "Beneficio esperado",
                List.of(),
                null,
                true
        );

        assertNull(assessment.estimatedCost());
    }

    @Test
    @DisplayName("Debe normalizar los textos eliminando espacios externos")
    void shouldTrimTextFields() {
        AlternativeAssessment assessment = createValidAssessment();

        assertEquals("Alternativa evaluada", assessment.reason());
        assertEquals("Mejorar los resultados",
                assessment.expectedBenefit());
        assertEquals("Riesgo operativo",
                assessment.identifiedRisks().getFirst());
        assertEquals("100 unidades", assessment.estimatedCost());
    }

    @Test
    @DisplayName("Debe devolver una lista de riesgos inmutable")
    void shouldReturnImmutableIdentifiedRisks() {
        AlternativeAssessment assessment = createValidAssessment();

        assertThrows(UnsupportedOperationException.class,
                () -> assessment.identifiedRisks().add("Nuevo riesgo"));
    }

    @Test
    @DisplayName("Debe conservar el carácter reversible de la alternativa")
    void shouldPreserveReversibility() {
        AlternativeAssessment assessment = createValidAssessment();

        assertTrue(assessment.reversible());

        AlternativeAssessment irreversible = new AlternativeAssessment(
                DecisionAction.ABORT_INTERVENTION,
                false,
                "Intervención no reversible",
                null,
                List.of(),
                null,
                false
        );

        assertFalse(irreversible.reversible());
    }
}

