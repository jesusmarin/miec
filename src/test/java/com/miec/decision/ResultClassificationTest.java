
package com.miec.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Pruebas de clasificación de resultados MIEC")
class ResultClassificationTest {

    @Test
    @DisplayName("Debe asignar correctamente los códigos oficiales RS-01 a RS-06")
    void shouldReturnOfficialClassificationCodes() {
        assertEquals("RS-01", ResultClassification.FAVORABLE.getCode());
        assertEquals("RS-02", ResultClassification.PARTIAL.getCode());
        assertEquals("RS-03", ResultClassification.NEUTRAL.getCode());
        assertEquals("RS-04", ResultClassification.UNFAVORABLE.getCode());
        assertEquals("RS-05", ResultClassification.INCONCLUSIVE.getCode());
        assertEquals("RS-06", ResultClassification.RISK_INADMISSIBLE.getCode());
    }

    @Test
    @DisplayName("Solo RS-01 debe identificarse como resultado favorable")
    void shouldIdentifyOnlyFavorableClassification() {
        assertTrue(ResultClassification.FAVORABLE.isFavorable());

        assertFalse(ResultClassification.PARTIAL.isFavorable());
        assertFalse(ResultClassification.NEUTRAL.isFavorable());
        assertFalse(ResultClassification.UNFAVORABLE.isFavorable());
        assertFalse(ResultClassification.INCONCLUSIVE.isFavorable());
        assertFalse(ResultClassification.RISK_INADMISSIBLE.isFavorable());
    }

    @Test
    @DisplayName("RS-05 debe ser la única clasificación inconclusa")
    void shouldIdentifyInconclusiveClassification() {
        assertTrue(ResultClassification.INCONCLUSIVE.isConclusive() == false);

        assertTrue(ResultClassification.FAVORABLE.isConclusive());
        assertTrue(ResultClassification.PARTIAL.isConclusive());
        assertTrue(ResultClassification.NEUTRAL.isConclusive());
        assertTrue(ResultClassification.UNFAVORABLE.isConclusive());
        assertTrue(ResultClassification.RISK_INADMISSIBLE.isConclusive());
    }

    @Test
    @DisplayName("RS-06 debe bloquear la continuación por riesgo inadmisible")
    void shouldBlockContinuationForRiskInadmissibleResult() {
        assertTrue(ResultClassification.RISK_INADMISSIBLE.blocksContinuation());
    }

    @Test
    @DisplayName("Las demás clasificaciones no deben bloquear automáticamente la continuación")
    void shouldNotBlockContinuationForOtherClassifications() {
        assertFalse(ResultClassification.FAVORABLE.blocksContinuation());
        assertFalse(ResultClassification.PARTIAL.blocksContinuation());
        assertFalse(ResultClassification.NEUTRAL.blocksContinuation());
        assertFalse(ResultClassification.UNFAVORABLE.blocksContinuation());
        assertFalse(ResultClassification.INCONCLUSIVE.blocksContinuation());
    }
}

