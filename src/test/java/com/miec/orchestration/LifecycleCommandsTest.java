package com.miec.orchestration;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Valida límites y valores mínimos del MVP antes de construir persistencia y controladores. */
class LifecycleCommandsTest {
    /** Datos normalizados válidos y campos opcionales no requieren completar información ficticia. */
    @Test void shouldAcceptMinimalAndNormalizeCommands() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var system = new LifecycleCommands.CreateSystem(" sistema ", " ", null, " operador ");
            assertTrue(validator.validate(system).isEmpty());
            assertEquals("sistema", system.name());
            assertNull(system.domain());
            assertEquals("operador", system.ownerReference());
            assertTrue(validator.validate(new LifecycleCommands.CreateCycle(null)).isEmpty());
            assertTrue(validator.validate(new LifecycleCommands.StartCycle(0L, " iniciar ")).isEmpty());
        }
    }

    /** El sistema exige nombre y limita datos que luego se escriben en columnas acotadas. */
    @Test void shouldRejectMissingOrOversizedSystemFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var invalid : new LifecycleCommands.CreateSystem[]{
                    new LifecycleCommands.CreateSystem(null, null, null, null),
                    new LifecycleCommands.CreateSystem(" ", null, null, null),
                    new LifecycleCommands.CreateSystem("x".repeat(256), null, null, null),
                    new LifecycleCommands.CreateSystem("s", "x".repeat(101), null, null),
                    new LifecycleCommands.CreateSystem("s", null, "x".repeat(4001), null),
                    new LifecycleCommands.CreateSystem("s", null, null, "x".repeat(256))}) {
                assertFalse(validator.validate(invalid).isEmpty());
            }
        }
    }

    /** El inicio exige versión no negativa y motivo; no acepta una actualización sin contexto. */
    @Test void shouldRejectInvalidStartAndConfiguration() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var invalid : new LifecycleCommands.StartCycle[]{
                    new LifecycleCommands.StartCycle(null, "iniciar"),
                    new LifecycleCommands.StartCycle(-1L, "iniciar"),
                    new LifecycleCommands.StartCycle(0L, " "),
                    new LifecycleCommands.StartCycle(0L, "x".repeat(501))}) {
                assertFalse(validator.validate(invalid).isEmpty());
            }
            assertFalse(validator.validate(new LifecycleCommands.CreateCycle("x".repeat(101))).isEmpty());
        }
    }
}
