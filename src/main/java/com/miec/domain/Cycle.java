
package com.miec.domain;

import com.miec.state.CycleState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Cycle {

    private final UUID id;
    private final String name;
    private final String objective;

    private CycleState status;

    private final Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    public Cycle(String name, String objective) {
        this.id = UUID.randomUUID();
        this.name = requireText(name, "El nombre es obligatorio");
        this.objective = requireText(objective, "El objetivo es obligatorio");
        this.status = CycleState.E00_INACTIVO;
        this.createdAt = Instant.now();
    }

    private String requireText(String value, String message) {
        Objects.requireNonNull(value, message);

        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }

    /**
     * Cambia el estado del ciclo verificando las transiciones
     * definidas en MIEC-F01.
     */
    public void transitionTo(CycleState newState) {

        Objects.requireNonNull(newState, "El nuevo estado es obligatorio");

        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "No se puede modificar un ciclo terminado."
            );
        }

        if (!isValidTransition(status, newState)) {
            throw new IllegalStateException(
                    "Transición no permitida: " + status + " -> " + newState
            );
        }

        if (newState == CycleState.E01_INICIALIZANDO
                && startedAt == null) {
            startedAt = Instant.now();
        }

        if (newState.isTerminal()) {
            finishedAt = Instant.now();
        }

        status = newState;
    }

    /**
     * Determina las transiciones permitidas según MIEC-F01 v0.1.
     */
    private boolean isValidTransition(
            CycleState current,
            CycleState next) {

        return switch (current) {

            case E00_INACTIVO ->
                    next == CycleState.E01_INICIALIZANDO;

            case E01_INICIALIZANDO ->
                    next == CycleState.E02_OBSERVANDO
                            || next == CycleState.E12_SUSPENDIDO;

            case E02_OBSERVANDO ->
                    next == CycleState.E03_DIAGNOSTICANDO
                            || next == CycleState.E12_SUSPENDIDO;

            case E03_DIAGNOSTICANDO ->
                    next == CycleState.E04_PLANIFICANDO
                            || next == CycleState.E02_OBSERVANDO;

            case E04_PLANIFICANDO ->
                    next == CycleState.E05_ESPERANDO_AUTORIZACION
                            || next == CycleState.E06_PREPARADO
                            || next == CycleState.E03_DIAGNOSTICANDO;

            case E05_ESPERANDO_AUTORIZACION ->
                    next == CycleState.E06_PREPARADO
                            || next == CycleState.E12_SUSPENDIDO;

            case E06_PREPARADO ->
                    next == CycleState.E07_EJECUTANDO;

            case E07_EJECUTANDO ->
                    next == CycleState.E08_VERIFICANDO
                            || next == CycleState.E12_SUSPENDIDO
                            || next == CycleState.E13_RECUPERANDO;

            case E08_VERIFICANDO ->
                    next == CycleState.E09_DECIDIENDO
                            || next == CycleState.E02_OBSERVANDO;

            case E09_DECIDIENDO ->
                    next == CycleState.E10_APRENDIENDO;

            case E10_APRENDIENDO ->
                    next == CycleState.E11_CONTINUANDO;

            case E11_CONTINUANDO ->
                    next == CycleState.E02_OBSERVANDO
                            || next == CycleState.E14_FINALIZADO
                            || next == CycleState.E04_PLANIFICANDO;

            case E12_SUSPENDIDO ->
                    next == CycleState.E02_OBSERVANDO
                            || next == CycleState.E04_PLANIFICANDO
                            || next == CycleState.E13_RECUPERANDO
                            || next == CycleState.E15_ABORTADO;

            case E13_RECUPERANDO ->
                    next == CycleState.E08_VERIFICANDO
                            || next == CycleState.E12_SUSPENDIDO
                            || next == CycleState.E15_ABORTADO;

            case E14_FINALIZADO, E15_ABORTADO -> false;
        };
    }

    /**
     * Inicia el ciclo, pasando de E00_INACTIVO a E01_INICIALIZANDO.
     *
     * @throws IllegalStateException si el ciclo ya está inicializado
     *         o en un estado posterior.
     * @see CycleState#E00_INACTIVO
     * @see CycleState#E01_INICIALIZANDO
     */
    public void start() {
        transitionTo(CycleState.E01_INICIALIZANDO);
    }

    /**
     * Indica si el estado representa la ausencia de un ciclo activo.
     */
    public boolean isInitial() {
        return this.status == CycleState.E00_INACTIVO;
    }

    public void suspend() {
        transitionTo(CycleState.E12_SUSPENDIDO);
    }

    public void resume() {
        if (status != CycleState.E12_SUSPENDIDO) {
            throw new IllegalStateException(
                    "Solo se puede reanudar un ciclo suspendido."
            );
        }

        transitionTo(CycleState.E13_RECUPERANDO);
    }

    /**
     * Reanuda el ciclo hacia el estado objetivo especificado.
     *
     * @param target debe ser E02, E04, E13 o E15, conforme a F01 §4.
     * @throws IllegalStateException si el ciclo no está suspendido
     *         o si el estado objetivo no es una reanudación válida.
     */
    public void resumeTo(CycleState target) {
        if (status != CycleState.E12_SUSPENDIDO) {
            throw new IllegalStateException(
                    "Solo se puede reanudar un ciclo suspendido."
            );
        }

        if (target != CycleState.E02_OBSERVANDO
                && target != CycleState.E04_PLANIFICANDO
                && target != CycleState.E13_RECUPERANDO
                && target != CycleState.E15_ABORTADO) {
            throw new IllegalArgumentException(
                    "Estado objetivo no válido para reanudación: " + target
            );
        }

        transitionTo(target);
    }

    /**
     * Finaliza el ciclo.
     *
     * @throws IllegalStateException si el ciclo no está en E11_CONTINUANDO
     *         o si ya está en un estado terminal.
     * @see CycleState#E11_CONTINUANDO
     * @see CycleState#E14_FINALIZADO
     */
    public void finish() {
        transitionTo(CycleState.E14_FINALIZADO);
    }

    /**
     * Aborta el ciclo.
     *
     * @throws IllegalStateException si el ciclo no está en E12_SUSPENDIDO
     *         o E13_RECUPERANDO, o si ya está en un estado terminal.
     * @see CycleState#E12_SUSPENDIDO
     * @see CycleState#E13_RECUPERANDO
     * @see CycleState#E15_ABORTADO
     */
    public void abort() {
        transitionTo(CycleState.E15_ABORTADO);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean isSuspended() {
        return status.isSuspended();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getObjective() {
        return objective;
    }

    public CycleState getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
