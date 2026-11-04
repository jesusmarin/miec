package com.miec.orchestration;

import com.miec.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Ejecuta todos los escenarios de consulta H2 sin cambios contra una base PostgreSQL propia. */
@EnabledIfSystemProperty(named="miec.postgres.test",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("MVP-09 - paridad PostgreSQL de consultas y cursores")
class LifecycleNavigationPostgresIT extends LifecycleNavigationQueriesTest {
    private static final PostgresTestDatabase DATABASE=PostgresTestDatabase.create("navigation");
    /** Selecciona PostgreSQL real sin usar ni modificar los datos del piloto. */
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties){DATABASE.configure(properties);}
    /** Elimina exclusivamente la base temporal creada por esta clase. */
    @AfterAll static void closeDatabase(){DATABASE.close();}
}
