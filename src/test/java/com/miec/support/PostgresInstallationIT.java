package com.miec.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Instalacion local explicita, separada de las IT efimeras y sin operaciones de borrado. */
@EnabledIfSystemProperty(named="miec.postgres.install",matches="true")
@DisplayName("Instalacion opt-in de MIEC sobre PostgreSQL local")
class PostgresInstallationIT {
    /** Solo instala en miec-v01 vacia o valida una instalacion previa completa del mismo historial. */
    @Test @DisplayName("Instala las 21 migraciones en miec-v01 sin introducir datos de demostracion")
    void installsEmptyLocalDatabase(){
        var source=PostgresTestDatabase.configuredDataSource();var jdbc=new JdbcTemplate(source);
        assertEquals("miec-v01",jdbc.queryForObject("SELECT current_database()",String.class));
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/postgresql")
                .defaultSchema("public").cleanDisabled(true).failOnMissingLocations(true).load();
        int tables=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'",Integer.class);
        if(tables==0)flyway.migrate();
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(21,flyway.info().applied().length,"Base no vacia o historial incompleto: no se aplican cambios automaticamente");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cycle",Integer.class),"No se generan ciclos de demostracion");
        System.out.println("POSTGRESQL INSTALACION VALIDADA: miec-v01, 21 migraciones, sin datos de demostracion; petclinic no modificada.");
    }
}
