package com.miec.orchestration;

import com.miec.support.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Ejecuta los mismos escenarios de rondas y permisos contra PostgreSQL real, en una base propia. */
@EnabledIfSystemProperty(named="miec.postgres.test",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("PostgreSQL 14 — continuidad, permisos, concurrencia, rollback y dos rondas")
class PostgresRoundContinuationIT extends RoundContinuationTest {
    private static final PostgresTestDatabase DATABASE=PostgresTestDatabase.create("rounds");
    @Autowired Flyway flyway;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    /** Comprueba el enlace JSON de Hibernate ademas de los INSERT JDBC usados por el recorrido. */
    @Test @DisplayName("JPA persiste documentos JSON sin convertirlos en cadenas JSON")
    void persistsJpaJson(){
        var system=cycles.createSystem(new LifecycleCommands.CreateSystem("json",null,null,null));
        var cycle=cycles.createCycle(system.systemId(),new LifecycleCommands.CreateCycle(null),"author");
        var decision=new com.miec.decision.DecisionRecordEntity(cycle.cycleId(),"TEST","RECORDED")
                .alternativesConsidered("[\"continue\"]").riskConstraints("{\"risk\":\"low\"}")
                .proposedTransition("{\"state\":\"E02_OBSERVANDO\"}");
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            entityManager.persist(decision);entityManager.flush();
        });
        assertEquals("array",jdbc.queryForObject("SELECT json_typeof(alternatives_considered) FROM decision_record WHERE decision_id=?",String.class,decision.getDecisionId()));
        assertEquals("low",jdbc.queryForObject("SELECT risk_constraints->>'risk' FROM decision_record WHERE decision_id=?",String.class,decision.getDecisionId()));
        assertEquals("E02_OBSERVANDO",jdbc.queryForObject("SELECT proposed_transition->>'state' FROM decision_record WHERE decision_id=?",String.class,decision.getDecisionId()));
    }
    /** El reloj debe avanzar dentro de una transaccion y los tokens no pueden reutilizarse tras rollback. */
    @Test @DisplayName("Reloj de pared transaccional y fencing monotono despues de rollback")
    void preservesClockAndFencing(){
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        long first=transaction.execute(tx -> {
            var before=jdbc.queryForObject("SELECT miec_wall_clock()",java.sql.Timestamp.class).toInstant();
            jdbc.execute("SELECT pg_sleep(0.02)");
            var after=jdbc.queryForObject("SELECT miec_wall_clock()",java.sql.Timestamp.class).toInstant();
            assertTrue(after.isAfter(before));
            long token=jdbc.queryForObject("SELECT nextval('execution_fencing_sequence')",Long.class);
            tx.setRollbackOnly();return token;
        });
        assertTrue(jdbc.queryForObject("SELECT nextval('execution_fencing_sequence')",Long.class)>first);
    }
    /** Usa fechas de observacion del servidor sin relajar la validacion de evidencia externa. */
    @BeforeEach void useDatabaseObservationClock(){
        PostMeasurementTestSupport.observationClock(() -> jdbc.queryForObject(
                "SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant());
    }
    /** Evita que el reloj de laboratorio alcance otros escenarios. */
    @AfterEach void restoreObservationClock(){PostMeasurementTestSupport.resetObservationClock();}
    /** Sustituye el datasource H2 del fixture conservando sus servicios y adaptador de test. */
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties){DATABASE.configure(properties);}
    /** Libera únicamente la base de laboratorio; no toca miec-v01 ni petclinic. */
    @AfterAll static void cleanupPostgres(){DATABASE.close();}
    /** Detecta regresiones del reloj del servidor que invalidarian leases y ventanas temporales. */
    @Test @DisplayName("El reloj PostgreSQL no retrocede entre lecturas consecutivas")
    void databaseClockDoesNotRegress(){
        var previous=jdbc.queryForObject("SELECT miec_wall_clock()",java.sql.Timestamp.class).toInstant();
        for(int i=0;i<2000;i++){
            var current=jdbc.queryForObject("SELECT miec_wall_clock()",java.sql.Timestamp.class).toInstant();
            assertFalse(current.isBefore(previous),"Reloj PostgreSQL retrocedio: "+previous+" -> "+current);
            previous=current;
        }
    }
    /** El caso de actualización H2 heredado se sustituye por validación de la pista nueva PostgreSQL, sin importar historia H2. */
    @Override @Test @DisplayName("Instalación PostgreSQL nueva aplica y valida las 21 migraciones sin ejecutar Java H2")
    void upgradesRegisteredContext(){
        assertEquals(DATABASE.name(),jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals(21,flyway.info().applied().length);assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals("timestamp with time zone",jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='execution_attempt' AND column_name='lease_expires_at'",String.class));
        assertEquals("json",jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='round_context' AND column_name='context_snapshot'",String.class));
    }
}
