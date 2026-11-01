
package com.miec.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Pruebas del nivel de suficiencia de evidencia MIEC")
class EvidenceSufficiencyTest {

        @Test
        @DisplayName("F07-T01: Los códigos EV deben corresponder exactamente a la especificación MIEC-F02")
        void testEvidenceSufficiencyCodes() {
            assertEquals("EV-0", EvidenceSufficiency.NOT_EVALUABLE.getCode());
            assertEquals("EV-1", EvidenceSufficiency.INSUFFICIENT.getCode());
            assertEquals("EV-2", EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS.getCode());
            assertEquals("EV-3", EvidenceSufficiency.SUFFICIENT.getCode());
            assertEquals("EV-4", EvidenceSufficiency.CORROBORATED.getCode());
        }

        @Test
        @DisplayName("F07-T01: Deben existir exactamente 5 niveles de suficiencia definidos")
        void testEnumValuesCount() {
            assertEquals(5, EvidenceSufficiency.values().length,
                    "El modelo debe tener exactamente 5 niveles de suficiencia de evidencia");
        }

        @Test
        @DisplayName("F07-T02: EV-0 y EV-1 NO deben permitir una evaluación concluyente")
        void testAllowsEvaluation_FalseForInsufficient() {
            assertFalse(EvidenceSufficiency.NOT_EVALUABLE.allowsEvaluation(),
                    "EV-0 (No evaluable) no debe permitir evaluación");
            assertFalse(EvidenceSufficiency.INSUFFICIENT.allowsEvaluation(),
                    "EV-1 (Insuficiente) no debe permitir evaluación");
        }

        @Test
        @DisplayName("F07-T02: EV-2, EV-3 y EV-4 SÍ deben permitir evaluación")
        void testAllowsEvaluation_TrueForSufficient() {
            assertTrue(EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS.allowsEvaluation(),
                    "EV-2 debe permitir evaluación limitada");
            assertTrue(EvidenceSufficiency.SUFFICIENT.allowsEvaluation(),
                    "EV-3 debe permitir evaluación ordinaria");
            assertTrue(EvidenceSufficiency.CORROBORATED.allowsEvaluation(),
                    "EV-4 debe permitir evaluación con corroboración");
        }

        @Test
        @DisplayName("F07-T02 (Corrección de Bug): EV-0, EV-1 y EV-2 DEBEN reportar limitaciones inherentes")
        void testHasLimitations_TrueWhenLimited() {
            assertTrue(EvidenceSufficiency.NOT_EVALUABLE.hasLimitations(),
                    "EV-0 debe reportar limitaciones (es inutilizable)");
            assertTrue(EvidenceSufficiency.INSUFFICIENT.hasLimitations(),
                    "EV-1 debe reportar limitaciones (no permite conclusión confiable)");
            assertTrue(EvidenceSufficiency.ACCEPTABLE_WITH_LIMITATIONS.hasLimitations(),
                    "EV-2 debe reportar limitaciones (incertidumbres documentadas)");
        }

        @Test
        @DisplayName("F07-T02: EV-3 y EV-4 NO deben reportar limitaciones inherentes")
        void testHasLimitations_FalseWhenSufficient() {
            assertFalse(EvidenceSufficiency.SUFFICIENT.hasLimitations(),
                    "EV-3 no debe tener limitaciones inherentes");
            assertFalse(EvidenceSufficiency.CORROBORATED.hasLimitations(),
                    "EV-4 no debe tener limitaciones inherentes");
        }
    }

