package com.miec.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Valida la consulta de diagnóstico con SQL real y un reloj fijo.
 * Comprueba umbral inclusivo, estados, parámetros, orden, límite,
 * mapeo y ausencia de modificaciones en los intentos.
 *
 * <p>Usa Flyway y H2 en memoria. Cada escenario se revierte mediante
 * la transacción de prueba: el detector no utiliza REQUIRES_NEW.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:miec_detection_test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional
@DisplayName("IR-009 - Detección de ejecuciones potencialmente interrumpidas")
class InterruptedExecutionDetectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final Duration MINIMUM_AGE = Duration.ofMinutes(30);
    private static final Instant CUTOFF = NOW.minus(MINIMUM_AGE);

    @Autowired
    private JdbcTemplate jdbc;

    private InterruptedExecutionDetectionService detector;
    private UUID planId;
    private UUID cycleId;
    private UUID authorizationId;

    /**
     * Prepara el detector con un reloj fijo y crea las referencias
     * de sistema, ciclo, plan y autorización para cada prueba.
     *
     * <p>Los datos participan en la transacción de prueba y se revierten
     * al finalizar el escenario.
     */
    @BeforeEach
    void setUp() {
        detector = new InterruptedExecutionDetectionService(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        UUID systemId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        planId = UUID.randomUUID();
        authorizationId = UUID.randomUUID();
        jdbc.update("INSERT INTO system_objective(system_id, name) VALUES (?, ?)",
                systemId, "Detector test");
        jdbc.update("INSERT INTO cycle(cycle_id, system_id) VALUES (?, ?)",
                cycleId, systemId);
        jdbc.update("INSERT INTO intervention_plan(plan_id, cycle_id, status) VALUES (?, ?, ?)",
                planId, cycleId, "PROPOSED");
        jdbc.update("""
                INSERT INTO authorizations(authorization_id, plan_id, requested_by, status, issued_at)
                VALUES (?, ?, ?, ?, ?)
                """, authorizationId, planId, "test", "APPROVED", Timestamp.from(NOW));
    }

    /**
     * Inserta un intento para consultar directamente el detector,
     * sin ejecutar el flujo de autorización del coordinador.
     *
     * <p>Asigna el resultado de efectos y la fecha de cierre según
     * el estado, respetando las restricciones de V003. Cada intento
     * utiliza una acción nueva y el número de intento 2.
     *
     * @param executionId identificador único del intento
     * @param status estado permitido por V003
     * @param startedAt inicio del intento; nulo para PENDING y obligatorio
     *                  para los demás estados
     * @return identificador del intento insertado
     */
    private UUID insertAttempt(UUID executionId, String status, Instant startedAt) {
        String outcome = switch (status) {
            case "COMPLETED" -> "EFFECT_APPLIED";
            case "FAILED" -> "NO_EFFECT_CONFIRMED";
            default -> "EFFECT_UNKNOWN";
        };
        Instant completedAt = switch (status) {
            case "COMPLETED", "FAILED", "UNKNOWN" -> startedAt.plusSeconds(1);
            default -> null;
        };
        jdbc.update("""
                INSERT INTO execution_attempt(
                    execution_id, planned_action_id, plan_id, cycle_id,
                    authorization_id, attempt_number, execution_status,
                    effect_outcome, actor_id, started_at, completed_at,
                    result_reference, error_reference, created_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, executionId, UUID.randomUUID(), planId, cycleId,
                authorizationId, 2, status, outcome, "detector-test",
                startedAt == null ? null : Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt),
                "result:test", "error:test", Timestamp.from(NOW.minusSeconds(7200)), 7L);
        return executionId;
    }

    /**
     * Verifica que la antigüedad mínima sea inclusiva: devuelve el intento
     * anterior al corte y el situado exactamente en él, y excluye
     * el iniciado un milisegundo después.
     */
    @Test
    @DisplayName("Incluye el umbral exacto y excluye los inicios posteriores")
    void shouldUseInclusiveAgeThreshold() {
        UUID older = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF.minusSeconds(1));
        UUID exact = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF);
        insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF.plusMillis(1));

        assertEquals(java.util.List.of(older, exact), detector.findCandidates(MINIMUM_AGE, 100)
                .stream().map(InterruptedExecutionDetectionService.ReviewCandidate::executionId).toList());
    }

    /**
     * Comprueba que solo IN_PROGRESS sea candidato. Excluye PENDING
     * sin fecha de inicio y los estados finales COMPLETED, FAILED
     * y UNKNOWN, aunque tengan una fecha de inicio antigua.
     */
    @Test
    @DisplayName("Excluye todos los estados distintos de IN_PROGRESS")
    void shouldExcludeOtherStates() {
        insertAttempt(UUID.randomUUID(), "PENDING", null);
        for (String status : java.util.List.of("COMPLETED", "FAILED", "UNKNOWN")) {
            insertAttempt(UUID.randomUUID(), status, CUTOFF.minusSeconds(60));
        }
        UUID running = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF);

        var candidates = detector.findCandidates(MINIMUM_AGE, 100);
        assertEquals(1, candidates.size());
        assertEquals(running, candidates.getFirst().executionId());
    }

    /**
     * Comprueba el contrato de antigüedad: un valor nulo produce
     * NullPointerException y una duración cero o negativa produce
     * IllegalArgumentException.
     */
    @Test
    @DisplayName("Rechaza antigüedades nulas, cero o negativas")
    void shouldRejectInvalidAges() {
        assertThrows(NullPointerException.class, () -> detector.findCandidates(null, 10));
        assertThrows(IllegalArgumentException.class, () -> detector.findCandidates(Duration.ZERO, 10));
        assertThrows(IllegalArgumentException.class,
                () -> detector.findCandidates(Duration.ofSeconds(-1), 10));
    }

    /**
     * Verifica el rechazo de límites negativos, cero y mayores de 1000.
     * Confirma que los extremos permitidos, 1 y 1000, pueden consultarse
     * sin error cuando no existen candidatos.
     */
    @Test
    @DisplayName("Rechaza límites fuera de 1 a 1000 y acepta ambos extremos")
    void shouldValidateResultLimits() {
        for (int limit : new int[]{-1, 0, 1001}) {
            assertThrows(IllegalArgumentException.class,
                    () -> detector.findCandidates(MINIMUM_AGE, limit));
        }
        assertTrue(detector.findCandidates(MINIMUM_AGE, 1).isEmpty());
        assertTrue(detector.findCandidates(MINIMUM_AGE, 1000).isEmpty());
    }

    /**
     * Comprueba que los intentos más antiguos aparezcan primero y que
     * un empate de fechas se resuelva por execution_id ascendente.
     * Solicita dos resultados de tres candidatos para validar que
     * el límite conserva los primeros elementos del orden esperado.
     */
    @Test
    @DisplayName("Ordena por inicio e identificador y aplica el límite después del orden")
    void shouldOrderAndLimitCandidates() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertAttempt(high, "IN_PROGRESS", CUTOFF);
        insertAttempt(low, "IN_PROGRESS", CUTOFF);
        UUID oldest = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF.minusSeconds(60));

        assertEquals(java.util.List.of(oldest, low), detector.findCandidates(MINIMUM_AGE, 2)
                .stream().map(InterruptedExecutionDetectionService.ReviewCandidate::executionId).toList());
    }

    /**
     * Verifica una lista vacía tanto sin intentos registrados como
     * cuando solo existe un intento IN_PROGRESS demasiado reciente.
     */
    @Test
    @DisplayName("Devuelve una lista vacía cuando no hay candidatos")
    void shouldReturnEmptyWhenNoCandidatesExist() {
        assertTrue(detector.findCandidates(MINIMUM_AGE, 100).isEmpty());
        insertAttempt(UUID.randomUUID(), "IN_PROGRESS", NOW);
        assertTrue(detector.findCandidates(MINIMUM_AGE, 100).isEmpty());
    }

    /**
     * Contrasta el candidato con los valores persistidos: identificadores
     * de ejecución, plan y acción, número de intento y fecha de inicio.
     * La comparación del registro completo comprueba todos sus campos.
     */
    @Test
    @DisplayName("Mapea todos los campos del candidato desde la base de datos")
    void shouldMapCandidateFields() {
        UUID executionId = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF);
        UUID actionId = jdbc.queryForObject(
                "SELECT planned_action_id FROM execution_attempt WHERE execution_id = ?",
                UUID.class, executionId);

        assertEquals(new InterruptedExecutionDetectionService.ReviewCandidate(
                executionId, planId, actionId, 2, CUTOFF
        ), detector.findCandidates(MINIMUM_AGE, 100).getFirst());
    }

    /**
     * Verifica que un intento antiguo deje de ser candidato después de
     * finalizar en COMPLETED o UNKNOWN entre dos consultas consecutivas.
     *
     * <p>La transición se simula mediante JDBC respetando V003. Se conserva
     * otro intento IN_PROGRESS como control y se comprueba que el detector
     * no modifica ninguna columna del intento finalizado.
     *
     * @param finalStatus estado final que debe excluirse de la segunda consulta
     */
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "UNKNOWN"})
    @DisplayName("Excluye un intento que finaliza entre dos consultas")
    void shouldExcludeAttemptFinishedBetweenQueries(String finalStatus) {
        UUID executionId = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF);
        UUID controlId = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF.minusSeconds(1));
        assertEquals(java.util.List.of(controlId, executionId),
                detector.findCandidates(MINIMUM_AGE, 100).stream()
                        .map(InterruptedExecutionDetectionService.ReviewCandidate::executionId).toList());

        assertEquals(1, jdbc.update("""
                UPDATE execution_attempt
                SET execution_status = ?, effect_outcome = ?,
                    completed_at = ?, version = version + 1
                WHERE execution_id = ? AND execution_status = 'IN_PROGRESS'
                """, finalStatus,
                "COMPLETED".equals(finalStatus) ? "EFFECT_APPLIED" : "EFFECT_UNKNOWN",
                Timestamp.from(NOW), executionId));

        String select = "SELECT * FROM execution_attempt WHERE execution_id = ?";
        var afterTransition = jdbc.queryForMap(select, executionId);

        assertEquals(java.util.List.of(controlId),
                detector.findCandidates(MINIMUM_AGE, 100).stream()
                        .map(InterruptedExecutionDetectionService.ReviewCandidate::executionId).toList());
        assertEquals(afterTransition, jdbc.queryForMap(select, executionId));
    }

    /**
     * Comprueba que un intento inicialmente reciente alcance el umbral
     * al avanzar el reloj. Usa tres instantes fijos, sin esperas reales
     * ni cambios en la fecha persistida del intento.
     */
    @Test
    @DisplayName("Detecta el intento cuando el avance del reloj alcanza el umbral")
    void shouldDetectAttemptWhenClockAdvances() {
        UUID executionId = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", NOW);
        assertTrue(detector.findCandidates(MINIMUM_AGE, 100).isEmpty());

        var beforeThreshold = new InterruptedExecutionDetectionService(jdbc,
                Clock.fixed(NOW.plus(MINIMUM_AGE).minusMillis(1), ZoneOffset.UTC));
        assertTrue(beforeThreshold.findCandidates(MINIMUM_AGE, 100).isEmpty());

        var atThreshold = new InterruptedExecutionDetectionService(jdbc,
                Clock.fixed(NOW.plus(MINIMUM_AGE), ZoneOffset.UTC));
        var candidates = atThreshold.findCandidates(MINIMUM_AGE, 100);
        assertEquals(1, candidates.size());
        assertEquals(executionId, candidates.getFirst().executionId());
        assertEquals(NOW, candidates.getFirst().startedAt());
    }

    /**
     * Inserta 1001 candidatos en orden inverso al cronológico y solicita
     * el máximo permitido. Comprueba los 1000 identificadores esperados
     * en orden y excluye el candidato más reciente.
     */
    @Test
    @DisplayName("Devuelve los 1000 candidatos más antiguos cuando hay 1001")
    void shouldApplyMaximumLimitWithMoreThanOneThousandCandidates() {
        var insertionOrder = new java.util.ArrayList<UUID>();
        for (int index = 0; index < 1001; index++) {
            insertionOrder.add(insertAttempt(UUID.randomUUID(), "IN_PROGRESS",
                    CUTOFF.minusSeconds(index)));
        }
        var expected = new java.util.ArrayList<>(insertionOrder);
        java.util.Collections.reverse(expected);

        var candidates = detector.findCandidates(MINIMUM_AGE, 1000);

        assertEquals(1000, candidates.size());
        assertEquals(expected.subList(0, 1000), candidates.stream()
                .map(InterruptedExecutionDetectionService.ReviewCandidate::executionId).toList());
        assertFalse(candidates.stream().anyMatch(candidate ->
                candidate.executionId().equals(insertionOrder.getFirst())));
    }

    /**
     * Captura todas las columnas del intento y realiza dos consultas
     * de diagnóstico. Comprueba que el registro completo permanece
     * igual, incluidos estado, versión, fechas y referencias.
     */
    @Test
    @DisplayName("No modifica estados, versión, fechas ni referencias al consultar repetidamente")
    void shouldLeaveAllAttemptColumnsUnchanged() {
        UUID executionId = insertAttempt(UUID.randomUUID(), "IN_PROGRESS", CUTOFF);
        String select = "SELECT * FROM execution_attempt WHERE execution_id = ?";
        var before = jdbc.queryForMap(select, executionId);

        assertEquals(1, detector.findCandidates(MINIMUM_AGE, 100).size());
        assertEquals(1, detector.findCandidates(MINIMUM_AGE, 100).size());

        assertEquals(before, jdbc.queryForMap(select, executionId));
    }
}
