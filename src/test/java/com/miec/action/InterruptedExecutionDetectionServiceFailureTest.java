package com.miec.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Valida la propagación de fallos de consulta sin iniciar Spring ni una base
 * de datos. El fallo se simula en JDBC; no representa una caída real de H2.
 */
@DisplayName("IR-009 - Fallos de consulta del detector")
class InterruptedExecutionDetectionServiceFailureTest {

    /**
     * Comprueba que un fallo de acceso se propague con su identidad original,
     * en lugar de convertirse en una lista vacía de candidatos.
     *
     * <p>Verifica que el detector solo intente la consulta de lectura con el
     * corte y límite esperados, sin actualizaciones ni otras llamadas JDBC.
     */
    @Test
    @DisplayName("Propaga el fallo de consulta sin ocultarlo ni intentar escrituras")
    void shouldPropagateQueryFailureWithoutWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-11-02T12:00:00Z");
        var detector = new InterruptedExecutionDetectionService(
                jdbc, Clock.fixed(now, ZoneOffset.UTC));
        Duration minimumAge = Duration.ofMinutes(30);
        Timestamp cutoff = Timestamp.from(now.minus(minimumAge));
        var databaseFailure = new DataAccessResourceFailureException(
                "Fallo simulado al consultar execution_attempt");

        doThrow(databaseFailure).when(jdbc).query(
                anyString(),
                org.mockito.ArgumentMatchers
                        .<RowMapper<InterruptedExecutionDetectionService.ReviewCandidate>>any(),
                eq(cutoff), eq(Timestamp.from(now)), eq(100));

        var failure = assertThrows(DataAccessResourceFailureException.class,
                () -> detector.findCandidates(minimumAge, 100));

        assertSame(databaseFailure, failure);
        verify(jdbc).query(
                anyString(),
                org.mockito.ArgumentMatchers
                        .<RowMapper<InterruptedExecutionDetectionService.ReviewCandidate>>any(),
                eq(cutoff), eq(Timestamp.from(now)), eq(100));
        verifyNoMoreInteractions(jdbc);
    }
}
