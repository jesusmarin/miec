
package com.miec.domain;

/**
 * Clasifica las decisiones registradas por MIEC.
 *
 * Clasificación inicial propuesta, pendiente de contrastar
 * con la especificación formal F02 del motor de decisiones.
 */
public enum DecisionType {

    CONTINUE,
    APPROVE,
    REJECT,
    RETRY,
    SUSPEND,
    RECOVER,
    FINISH,
    ABORT
}