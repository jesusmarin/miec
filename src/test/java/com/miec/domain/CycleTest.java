
package com.miec.domain;

import com.miec.state.CycleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CycleTest {

    private Cycle cycle;

    @BeforeEach
    @DisplayName("Preparar un ciclo nuevo para cada prueba")
    void setUp() {
        cycle = new Cycle(
                "Experimento MIEC-001",
                "Validar la corrección controlada de defectos"
        );
    }

    @Test
    @DisplayName("Debe crear un ciclo en estado INACTIVO")
    void shouldCreateCycleInInactiveState() {

        assertThat(cycle.getId()).isNotNull()
                .isInstanceOf(UUID.class);

        assertThat(cycle.getName())
                .isEqualTo("Experimento MIEC-001");

        assertThat(cycle.getObjective())
                .isEqualTo("Validar la corrección controlada de defectos");

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E00_INACTIVO);

        assertThat(cycle.getCreatedAt()).isNotNull();
        assertThat(cycle.getStartedAt()).isNull();
        assertThat(cycle.getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("No debe permitir crear un ciclo sin nombre")
    void shouldRejectEmptyName() {

        assertThatThrownBy(() ->
                new Cycle(" ", "Objetivo de prueba")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El nombre es obligatorio");
    }

    @Test
    @DisplayName("No debe permitir crear un ciclo sin objetivo")
    void shouldRejectEmptyObjective() {

        assertThatThrownBy(() ->
                new Cycle("Ciclo de prueba", " ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El objetivo es obligatorio");
    }

    @Test
    @DisplayName("Debe iniciar el ciclo y registrar su fecha de inicio")
    void shouldStartCycle() {

        Instant beforeStart = Instant.now();

        cycle.start();

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E01_INICIALIZANDO);

        assertThat(cycle.getStartedAt()).isNotNull()
                .isAfterOrEqualTo(beforeStart);

        assertThat(cycle.getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("Debe conservar la fecha de inicio al comenzar la ejecución")
    void shouldPreserveStartTimeWhenExecutionBegins() {

        cycle.start();
        Instant cycleStart = cycle.getStartedAt();

        moveCycleToExecution();

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E07_EJECUTANDO);

        assertThat(cycle.getStartedAt()).isEqualTo(cycleStart);
        assertThat(cycle.getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("Debe rechazar una transición no permitida")
    void shouldRejectInvalidTransition() {

        assertThatThrownBy(() ->
                cycle.transitionTo(CycleState.E07_EJECUTANDO)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transición no permitida");

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E00_INACTIVO);
    }

    @Test
    @DisplayName("Debe suspender un ciclo en ejecución")
    void shouldSuspendCycle() {

        moveCycleToExecution();

        cycle.suspend();

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E12_SUSPENDIDO);

        assertThat(cycle.isSuspended()).isTrue();
    }

    @Test
    @DisplayName("Debe enviar un ciclo suspendido al estado de recuperación")
    void shouldResumeSuspendedCycle() {

        moveCycleToExecution();

        cycle.suspend();
        cycle.resume();

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E13_RECUPERANDO);
    }

    @Test
    @DisplayName("No debe permitir recuperar un ciclo no suspendido")
    void shouldRejectResumeWhenNotSuspended() {

        assertThatThrownBy(cycle::resume)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Solo se puede reanudar");
    }

    @Test
    @DisplayName("Debe finalizar el ciclo desde CONTINUANDO")
    void shouldFinishCycle() {

        moveCycleToDecision();

        cycle.transitionTo(CycleState.E10_APRENDIENDO);
        cycle.transitionTo(CycleState.E11_CONTINUANDO);

        Instant beforeFinish = Instant.now();

        cycle.finish();

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E14_FINALIZADO);

        assertThat(cycle.isTerminal()).isTrue();
        assertThat(cycle.getFinishedAt()).isNotNull()
                .isAfterOrEqualTo(beforeFinish);
    }

    @Test
    @DisplayName("No debe permitir modificar un ciclo finalizado")
    void shouldRejectChangesAfterFinishing() {

        moveCycleToDecision();

        cycle.transitionTo(CycleState.E10_APRENDIENDO);
        cycle.transitionTo(CycleState.E11_CONTINUANDO);
        cycle.finish();

        assertThatThrownBy(() ->
                cycle.transitionTo(CycleState.E02_OBSERVANDO)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ciclo terminado");
    }

    @Test
    @DisplayName("Debe abortar un ciclo suspendido")
    void shouldAbortCycle() {

        cycle.start();
        cycle.transitionTo(CycleState.E12_SUSPENDIDO);
        cycle.abort();

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E15_ABORTADO);

        assertThat(cycle.isTerminal()).isTrue();
        assertThat(cycle.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("No debe permitir finalizar directamente un ciclo inactivo")
    void shouldRejectFinishingInactiveCycle() {

        assertThatThrownBy(cycle::finish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transición no permitida");
    }

    @Test
    @DisplayName("Debe permitir pasar de PLANIFICANDO a PREPARADO")
    void shouldAllowDirectPreparation() {

        moveCycleToPlanning();

        cycle.transitionTo(CycleState.E06_PREPARADO);

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E06_PREPARADO);
    }

    @Test
    @DisplayName("Debe permitir volver a observar cuando falta evidencia")
    void shouldAllowReturningToObservation() {

        moveCycleToDiagnostic();

        cycle.transitionTo(CycleState.E02_OBSERVANDO);

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E02_OBSERVANDO);
    }

    @Test
    @DisplayName("Debe permitir reformular el plan desde CONTINUANDO")
    void shouldAllowReturningToPlanning() {

        moveCycleToDecision();

        cycle.transitionTo(CycleState.E10_APRENDIENDO);
        cycle.transitionTo(CycleState.E11_CONTINUANDO);
        cycle.transitionTo(CycleState.E04_PLANIFICANDO);

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E04_PLANIFICANDO);
    }

    @Test
    @DisplayName("Debe permitir verificar después de una recuperación")
    void shouldAllowVerificationAfterRecovery() {

        moveCycleToExecution();

        cycle.suspend();
        cycle.resume();
        cycle.transitionTo(CycleState.E08_VERIFICANDO);

        assertThat(cycle.getStatus())
                .isEqualTo(CycleState.E08_VERIFICANDO);
    }

    private void moveCycleToDiagnostic() {
        cycle.start();
        cycle.transitionTo(CycleState.E02_OBSERVANDO);
        cycle.transitionTo(CycleState.E03_DIAGNOSTICANDO);
    }

    private void moveCycleToPlanning() {
        moveCycleToDiagnostic();
        cycle.transitionTo(CycleState.E04_PLANIFICANDO);
    }

    private void moveCycleToExecution() {
        moveCycleToPlanning();
        cycle.transitionTo(CycleState.E05_ESPERANDO_AUTORIZACION);
        cycle.transitionTo(CycleState.E06_PREPARADO);
        cycle.transitionTo(CycleState.E07_EJECUTANDO);
    }

    private void moveCycleToDecision() {
        moveCycleToExecution();
        cycle.transitionTo(CycleState.E08_VERIFICANDO);
        cycle.transitionTo(CycleState.E09_DECIDIENDO);
    }
}
