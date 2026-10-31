
package com.miec.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutonomyLevelTest {

    @Test
    @DisplayName("Debe definir los cinco niveles de autonomía")
    void shouldDefineFiveAutonomyLevels() {
        assertEquals(5, AutonomyLevel.values().length);
    }

    @Test
    @DisplayName("Debe asignar correctamente el código A0 al nivel de observación")
    void shouldAssignCodeA0ToObservation() {
        assertEquals("A0", AutonomyLevel.OBSERVATION.getCode());
    }

    @Test
    @DisplayName("Debe asignar correctamente el código A1 al nivel de asistencia")
    void shouldAssignCodeA1ToAssistance() {
        assertEquals("A1", AutonomyLevel.ASSISTANCE.getCode());
    }

    @Test
    @DisplayName("Debe asignar correctamente el código A2 a la ejecución supervisada")
    void shouldAssignCodeA2ToSupervisedExecution() {
        assertEquals("A2", AutonomyLevel.SUPERVISED_EXECUTION.getCode());
    }

    @Test
    @DisplayName("Debe asignar correctamente el código A3 a la autonomía acotada")
    void shouldAssignCodeA3ToBoundedAutonomy() {
        assertEquals("A3", AutonomyLevel.BOUNDED_AUTONOMY.getCode());
    }

    @Test
    @DisplayName("Debe asignar correctamente el código A4 a la autonomía ampliada")
    void shouldAssignCodeA4ToExtendedAutonomy() {
        assertEquals("A4", AutonomyLevel.EXTENDED_AUTONOMY.getCode());
    }

    @Test
    @DisplayName("Debe permitir ejecución autónoma según el nivel A3")
    void shouldAllowAutonomousExecutionForBoundedAutonomy() {
        assertTrue(AutonomyLevel.BOUNDED_AUTONOMY.allowsAutonomousExecution());
    }

    @Test
    @DisplayName("Debe permitir ejecución autónoma según el nivel A4")
    void shouldAllowAutonomousExecutionForExtendedAutonomy() {
        assertTrue(AutonomyLevel.EXTENDED_AUTONOMY.allowsAutonomousExecution());
    }

    @Test
    @DisplayName("No debe permitir ejecución autónoma en los niveles A0, A1 y A2")
    void shouldNotAllowAutonomousExecutionForLowerLevels() {
        assertFalse(AutonomyLevel.OBSERVATION.allowsAutonomousExecution());
        assertFalse(AutonomyLevel.ASSISTANCE.allowsAutonomousExecution());
        assertFalse(AutonomyLevel.SUPERVISED_EXECUTION.allowsAutonomousExecution());
    }
}
