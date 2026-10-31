package com.miec.domain;

/**
 * Clasificación de las evidencias utilizadas por MIEC.
 *
 * Cada tipo identifica la naturaleza de la información
 * recopilada durante un ciclo de evolución.
 */
public enum EvidenceType {

    // Evidencias relacionadas con la observación
    OBSERVATION,

    // Evidencias obtenidas durante el diagnóstico
    DIAGNOSTIC,

    // Evidencias relacionadas con la planificación
    PLANNING,

    // Evidencias generadas durante la ejecución
    EXECUTION,

    // Resultados de pruebas y verificaciones
    TEST_RESULT,

    // Mediciones cuantitativas y métricas
    METRIC,

    // Evidencias documentales
    DOCUMENT,

    // Registros de actividad y acontecimientos
    LOG,

    // Evidencias de autorización humana
    APPROVAL,

    // Resultados de evaluación
    EVALUATION,

    // Evidencias relacionadas con decisiones
    DECISION,

    // Información obtenida durante el aprendizaje
    LEARNING,

    // Evidencias relacionadas con recuperación
    RECOVERY,

    // Evidencias relacionadas con seguridad
    SECURITY,

    // Evidencias externas al sistema MIEC
    EXTERNAL
}
