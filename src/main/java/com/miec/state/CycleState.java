package com.miec.state;

/**
 * Estados oficiales de la máquina de estados MIEC-F01.
 *
 * Cada estado representa una etapa del ciclo de evolución.
 */
public enum CycleState {

    E00_INACTIVO,

    E01_INICIALIZANDO,

    E02_OBSERVANDO,

    E03_DIAGNOSTICANDO,

    E04_PLANIFICANDO,

    E05_ESPERANDO_AUTORIZACION,

    E06_PREPARADO,

    E07_EJECUTANDO,

    E08_VERIFICANDO,

    E09_DECIDIENDO,

    E10_APRENDIENDO,

    E11_CONTINUANDO,

    E12_SUSPENDIDO,

    E13_RECUPERANDO,

    E14_FINALIZADO,

    E15_ABORTADO;

    /**
     * Indica si el estado representa la terminación definitiva del ciclo.
     */
    public boolean isTerminal() {
        return this == E14_FINALIZADO
                || this == E15_ABORTADO;
    }

    /**
     * Indica si el ciclo puede continuar su procesamiento.
     */
    public boolean isSuspended() {
        return this == E12_SUSPENDIDO;
    }
}
