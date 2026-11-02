package com.miec.experiment.petclinic;

import com.miec.action.*;
import com.miec.integration.contract.IdempotentDestinationContract;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.UUID;

/** Ejecuta el contrato reutilizable sobre HTTP real; requiere el JAR explícito de PetClinic. */
class PetClinicContractIT extends IdempotentDestinationContract {
    /** Prepara una JVM y archivo nuevos por escenario, sin acceder a una instancia del usuario. */
    @Override protected Target openTarget() throws Exception {
        Path directory = Files.createTempDirectory(Files.createDirectories(Path.of("target", "petclinic-contract-it")), "run-").toAbsolutePath();
        String database = "jdbc:h2:file:" + directory.resolve("petclinic").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        String description = "contract-" + UUID.randomUUID();
        var request = PetClinicVisitExecutor.visitRequest(900001, 900001, LocalDate.now().plusDays(7), description);
        var server = PetClinicTestServer.start(directory, PetClinicTestServer.configuredJar(), database, secret, 1);
        return new Target() {
            /** Captura la misma visita y bytes durante el escenario. */
            public ExecutionRequest request() { return request; }
            /** Aplica los parámetros de conexión confiables de este bootstrap. */
            public IdempotentActionExecutor executor(ExecutionRequest snapshot) {
                return new PetClinicVisitExecutor(server.uri(), secret, snapshot, Duration.ofSeconds(10));
            }
            /** Detiene la JVM propia y cuenta las visitas durables por SQL, no respuestas del adaptador. */
            public int effects() throws Exception {
                server.close();
                try (var connection = DriverManager.getConnection(database + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
                     var query = connection.prepareStatement("SELECT COUNT(*) FROM visits WHERE description=?")) {
                    query.setString(1, description);
                    try (var rows = query.executeQuery()) { rows.next(); return rows.getInt(1); }
                }
            }
            /** El cierre es seguro incluso si effects ya detuvo el proceso. */
            public void close() throws Exception { server.close(); }
        };
    }
}
