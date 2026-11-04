package com.miec.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bases PostgreSQL efímeras con credenciales locales que nunca se imprimen ni viajan en argumentos de proceso. */
public final class PostgresTestDatabase implements AutoCloseable {
    private final Settings settings;
    private final String name;
    private final String url;
    /** Configuración privada sin toString que pudiera exponer secretos. */
    private static final class Settings {
        String url,user,password;
    }
    /** Crea únicamente una base nueva con nombre aleatorio validado, nunca modifica la base configurada. */
    private PostgresTestDatabase(String label){
        if(!label.matches("[a-z_]{1,12}"))throw new IllegalArgumentException("Etiqueta de laboratorio inválida");
        settings=read();name="miec_pg_"+label+"_"+UUID.randomUUID().toString().replace("-","");
        int start=settings.url.indexOf('/',"jdbc:postgresql://".length());
        url=settings.url.substring(0,start)+"/"+name+"?currentSchema=public&connectTimeout=5&socketTimeout=15";
        try(var connection=DriverManager.getConnection(settings.url,credentials(settings));var statement=connection.createStatement()){
            statement.executeUpdate("CREATE DATABASE \""+name+"\"");
        }catch(SQLException denied){throw new IllegalStateException("No se pudo crear base de laboratorio; SQLState="+denied.getSQLState());}
    }
    /** Fábrica explícita para IT opt-in; requiere un usuario con permiso de creación de bases. */
    public static PostgresTestDatabase create(String label){return new PostgresTestDatabase(label);}
    /** Dirección no secreta de la base de laboratorio. */
    public String url(){return url;}
    /** Nombre verificable de la única base que este recurso puede eliminar. */
    public String name(){return name;}
    /** Conecta solo a la base propia; los errores de PostgreSQL no incluyen el archivo de credenciales. */
    public Connection connect() throws SQLException {return DriverManager.getConnection(url,credentials(settings));}
    /** DataSource de laboratorio para verificaciones Flyway/JDBC. */
    public DataSource dataSource(){return new DriverManagerDataSource(url,settings.user,settings.password);}
    /** Sobrescribe todos los valores H2 del fixture heredado con mayor prioridad, sin modificar su código. */
    public void configure(DynamicPropertyRegistry properties){
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->settings.user);
        properties.add("spring.datasource.password",()->settings.password);
        properties.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET TIME ZONE 'UTC'");
        properties.add("spring.jpa.database-platform",()->"org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.flyway.locations",()->"classpath:db/postgresql");
        properties.add("spring.flyway.default-schema",()->"public");
        properties.add("spring.flyway.fail-on-missing-locations",()->"true");
    }
    /** Usa variables estándar para prevalecer incluso si el perfil local tiene una contraseña literal. */
    public void configure(ProcessBuilder process){
        process.environment().put("SPRING_DATASOURCE_URL",url);
        process.environment().put("SPRING_DATASOURCE_USERNAME",settings.user);
        process.environment().put("SPRING_DATASOURCE_PASSWORD",settings.password);
    }
    /** Configuración de la base indicada por el usuario; solo para instalación explícita y validada. */
    public static DataSource configuredDataSource(){var config=read();return new DriverManagerDataSource(config.url,config.user,config.password);}
    /** Lee y resuelve los placeholders del archivo, sin copiar contraseñas a recursos de test. */
    private static Settings read(){
        try {
            var properties=new Properties();
            try(var reader=Files.newBufferedReader(Path.of(System.getProperty("miec.postgres.config","src/main/resources/application-postgres.properties")),StandardCharsets.UTF_8)){properties.load(reader);}
            var result=new Settings();result.url=resolve(properties.getProperty("spring.datasource.url"));result.user=resolve(properties.getProperty("spring.datasource.username"));result.password=resolve(properties.getProperty("spring.datasource.password"));
            if(!result.url.startsWith("jdbc:postgresql://127.0.0.1:")&&!result.url.startsWith("jdbc:postgresql://localhost:"))throw new IllegalArgumentException("Las IT requieren PostgreSQL en loopback");
            if(result.url.contains("password=")||result.url.contains("user=")||result.url.contains("@"))throw new IllegalArgumentException("Credenciales en URL no admitidas");
            return result;
        }catch(java.io.IOException missing){throw new IllegalStateException("No se pudo leer la configuración PostgreSQL local");}
    }
    /** Resuelve VARIABLE:valor por la misma precedencia de entorno usada por los perfiles. */
    private static String resolve(String value){
        Objects.requireNonNull(value,"Propiedad JDBC requerida");
        var match=Pattern.compile("^\\$\\{([^:}]+):(.*)\\}$",Pattern.DOTALL).matcher(value);
        if(!match.matches())return value;
        return System.getenv().getOrDefault(match.group(1),match.group(2));
    }
    /** Aplica límites de conexión sin exponer datos en logs o parámetros de JVM. */
    private static Properties credentials(Settings settings){var result=new Properties();result.setProperty("user",settings.user);result.setProperty("password",settings.password);result.setProperty("connectTimeout","5");result.setProperty("socketTimeout","15");return result;}
    /** Elimina exclusivamente la base creada por esta instancia, aun si el contexto Spring conserva conexiones propias. */
    @Override public void close(){
        if(!name.matches("miec_pg_[a-z_]{1,12}_[0-9a-f]{32}"))throw new IllegalStateException("Destino de limpieza inválido");
        try(var connection=DriverManager.getConnection(settings.url,credentials(settings));var statement=connection.createStatement()){
            statement.executeUpdate("DROP DATABASE \""+name+"\" WITH (FORCE)");
        }catch(SQLException failure){throw new IllegalStateException("No se pudo cerrar laboratorio; SQLState="+failure.getSQLState());}
    }
}
