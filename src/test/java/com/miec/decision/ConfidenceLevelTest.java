
package com.miec.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Pruebas del nivel de confianza MIEC")
class ConfidenceLevelTest {

    @Test
    @DisplayName("Debe contener los cuatro niveles de confianza definidos")
    void shouldContainAllConfidenceLevels() {
        assertEquals(4, ConfidenceLevel.values().length);
    }

    @Test
    @DisplayName("Debe declarar correctamente el nivel de confianza baja")
    void shouldDeclareLowConfidence() {
        assertNotNull(ConfidenceLevel.LOW);
    }

    @Test
    @DisplayName("Debe declarar correctamente el nivel de confianza moderada")
    void shouldDeclareModerateConfidence() {
        assertNotNull(ConfidenceLevel.MODERATE);
    }

    @Test
    @DisplayName("Debe declarar correctamente el nivel de confianza alta")
    void shouldDeclareHighConfidence() {
        assertNotNull(ConfidenceLevel.HIGH);
    }

    @Test
    @DisplayName("Debe declarar correctamente el nivel de confianza indeterminada")
    void shouldDeclareUndeterminedConfidence() {
        assertNotNull(ConfidenceLevel.UNDETERMINED);
    }

    @Test
    @DisplayName("Debe conservar el orden definido de los niveles de confianza")
    void shouldPreserveConfidenceLevelOrder() {
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.values()[0]);
        assertEquals(ConfidenceLevel.MODERATE, ConfidenceLevel.values()[1]);
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.values()[2]);
        assertEquals(ConfidenceLevel.UNDETERMINED, ConfidenceLevel.values()[3]);
    }

    @Test
    @DisplayName("Debe permitir recuperar cada nivel mediante su nombre")
    void shouldResolveConfidenceLevelsByName() {
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.valueOf("LOW"));
        assertEquals(ConfidenceLevel.MODERATE, ConfidenceLevel.valueOf("MODERATE"));
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.valueOf("HIGH"));
        assertEquals(ConfidenceLevel.UNDETERMINED, ConfidenceLevel.valueOf("UNDETERMINED"));
    }
}

