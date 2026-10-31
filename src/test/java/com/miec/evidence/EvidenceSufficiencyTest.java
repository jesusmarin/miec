
package com.miec.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Pruebas del nivel de suficiencia de evidencia MIEC")
class EvidenceSufficiencyTest {

    @Test
    @DisplayName("Debe asignar correctamente los códigos oficiales EV-0 a EV-4")
    void shouldReturnOfficialEvidenceSufficiencyCodes() {
        assertEquals("EV-0", EvidenceSufficiency.NOT_EVALUABLE.getCode());
        assertEquals("EV-1", EvidenceSufficiency.INSUFFICIENT.getCode());
        assertEquals("EV-2", EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS.getCode());
        assertEquals("EV-3", EvidenceSufficiency.SUFFICIENT.getCode());
        assertEquals("EV-4", EvidenceSufficiency.CORROBORATED.getCode());
    }

    @Test
    @DisplayName("EV-0 no debe permitir realizar una evaluación")
    void shouldNotAllowEvaluationWhenEvidenceIsNotEvaluable() {
        assertFalse(EvidenceSufficiency.NOT_EVALUABLE.allowsEvaluation());
    }

    @Test
    @DisplayName("EV-1 no debe permitir realizar una evaluación")
    void shouldNotAllowEvaluationWhenEvidenceIsInsufficient() {
        assertFalse(EvidenceSufficiency.INSUFFICIENT.allowsEvaluation());
    }

    @Test
    @DisplayName("EV-2 debe permitir evaluar reconociendo sus limitaciones")
    void shouldAllowEvaluationWithLimitations() {
        assertTrue(EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS.allowsEvaluation());
        assertTrue(EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS.hasLimitations());
    }

    @Test
    @DisplayName("EV-3 debe permitir realizar una evaluación")
    void shouldAllowEvaluationWhenEvidenceIsSufficient() {
        assertTrue(EvidenceSufficiency.SUFFICIENT.allowsEvaluation());
        assertFalse(EvidenceSufficiency.SUFFICIENT.hasLimitations());
    }

    @Test
    @DisplayName("EV-4 debe permitir evaluar evidencia corroborada")
    void shouldAllowEvaluationWhenEvidenceIsCorroborated() {
        assertTrue(EvidenceSufficiency.CORROBORATED.allowsEvaluation());
        assertFalse(EvidenceSufficiency.CORROBORATED.hasLimitations());
    }

    @Test
    @DisplayName("Solo EV-2 debe identificarse como evidencia con limitaciones")
    void shouldIdentifyOnlyAcceptableEvidenceAsLimited() {
        assertFalse(EvidenceSufficiency.NOT_EVALUABLE.hasLimitations());
        assertFalse(EvidenceSufficiency.INSUFFICIENT.hasLimitations());
        assertTrue(EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS.hasLimitations());
        assertFalse(EvidenceSufficiency.SUFFICIENT.hasLimitations());
        assertFalse(EvidenceSufficiency.CORROBORATED.hasLimitations());
    }
}

