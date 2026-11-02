package com.miec.experiment.reservation;

import com.miec.action.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Segundo destinatario experimental: reserva existencias y resultado en un ledger JDBC propio. */
final class ReservationDestination {
    static final String DESTINATION = "reservation-experiment";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AtomicBoolean failAfterEffect = new AtomicBoolean();

    /** Abre un archivo aislado; conserva inventario y resultados existentes al reconstruir el adaptador. */
    ReservationDestination(String database) {
        var source = new DriverManagerDataSource(database, "sa", "");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(10);
        jdbc.execute("CREATE TABLE IF NOT EXISTS inventory(id INT PRIMARY KEY, remaining INT NOT NULL CHECK(remaining>=0))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS reservations(operation_key UUID PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL, units INT NOT NULL, reference VARCHAR(100) NOT NULL)");
        jdbc.update("INSERT INTO inventory SELECT 1,100 WHERE NOT EXISTS(SELECT 1 FROM inventory WHERE id=1)");
    }

    /** Solicitud del segundo dominio, independiente de visitas, mascotas y endpoints HTTP. */
    ExecutionRequest request() { return new ExecutionRequest(DESTINATION, "application/json", "{\"units\":3}"); }

    /** Inyecta un fallo después del descuento para probar rollback de efecto y reserva de clave. */
    void failAfterEffect() { failAfterEffect.set(true); }

    /** Cuenta el efecto real sobre inventario. */
    int remaining() { return jdbc.queryForObject("SELECT remaining FROM inventory WHERE id=1", Integer.class); }

    /** Cuenta resultados confirmados de la tabla propia del destinatario. */
    int reservations() { return jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class); }

    /** Construye un adaptador con snapshot inmutable para el contrato existente de MIEC. */
    IdempotentActionExecutor executor(ExecutionRequest snapshot) {
        if (!DESTINATION.equals(snapshot.destinationId()) || !"application/json".equals(snapshot.mediaType())) {
            throw new IllegalArgumentException("Destino o contenido incompatible");
        }
        return new IdempotentActionExecutor() {
            /** Conserva los bytes exactos entre consulta y ejecución. */
            public ExecutionRequest request() { return snapshot; }
            /** Consulta el ledger sin modificar existencias. */
            public Lookup lookup(Context context) {
                checkContext(context);
                String result = find(context.operation().key(), snapshot.fingerprint());
                return result == null ? new Lookup(Status.NOT_FOUND, null) : new Lookup(Status.APPLIED, result);
            }
            /** Inserta clave, descuenta inventario y guarda referencia en una única transacción. */
            public String execute(Context context) {
                checkContext(context);
                UUID key = context.operation().key();
                String existing = find(key, snapshot.fingerprint());
                if (existing != null) return existing;
                try {
                    return transaction.execute(tx -> {
                        int units = JsonMapper.builder().build().readTree(snapshot.body()).path("units").asInt();
                        if (units < 1 || units > 100) throw new IllegalArgumentException("Cantidad inválida");
                        String reference = "reservation:" + key;
                        jdbc.update("INSERT INTO reservations VALUES (?,?,?,?)", key, snapshot.fingerprint(), units, reference);
                        if (jdbc.update("UPDATE inventory SET remaining=remaining-? WHERE id=1 AND remaining>=?", units, units) != 1) {
                            throw new IllegalStateException("Existencias insuficientes");
                        }
                        if (failAfterEffect.getAndSet(false)) throw new IllegalStateException("Fallo posterior al descuento");
                        return reference;
                    });
                } catch (DuplicateKeyException concurrentReservation) {
                    // La transacción anterior terminó con rollback antes de consultar el resultado ganador.
                    String result = find(key, snapshot.fingerprint());
                    if (result == null) throw concurrentReservation;
                    return result;
                }
            }
            /** Impide usar los bytes de otra solicitud con este adaptador. */
            private void checkContext(Context context) {
                if (!snapshot.equals(context.request())) throw new IllegalArgumentException("Contexto de otra solicitud");
            }
        };
    }

    /** Devuelve solo un resultado compatible; nunca acepta reutilización de clave con otra huella. */
    private String find(UUID key, String fingerprint) {
        var rows = jdbc.query("SELECT fingerprint,reference FROM reservations WHERE operation_key=?", (rs, row) -> {
            if (!fingerprint.equals(rs.getString(1))) throw new IllegalArgumentException("Clave con otro contenido");
            return rs.getString(2);
        }, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
