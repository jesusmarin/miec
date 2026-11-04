package com.miec.orchestration;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Diferencias JDBC acotadas a SQL preparado del servidor; nunca recibe SQL aportado por clientes. */
@Component
class DatabaseSql {
    private final boolean postgres;
    /** Identifica el motor real, independientemente del nombre de perfil o de una declaración del llamador. */
    DatabaseSql(JdbcTemplate jdbc){
        String product=Objects.requireNonNull(jdbc.execute((ConnectionCallback<String>)connection->connection.getMetaData().getDatabaseProductName()));
        if(!product.equals("H2")&&!product.equals("PostgreSQL"))throw new IllegalStateException("Motor SQL no soportado por MIEC");
        postgres=product.equals("PostgreSQL");
    }
    /** Conserva número/orden de parámetros y parsea documentos JSON, evitando almacenarlos como cadenas JSON. */
    String json(String preparedSql){
        Objects.requireNonNull(preparedSql);
        return postgres?preparedSql.replace("? FORMAT JSON","CAST(? AS JSON)"):preparedSql;
    }
}
