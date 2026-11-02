package com.miec.experiment.petclinic;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Bootstrap compartido de pruebas; administra exclusivamente la JVM hija que inicia. */
record PetClinicTestServer(Process process, URI uri) implements AutoCloseable {
    /** Obtiene el JAR explícito: nunca descarga ni inicia una instancia del usuario. */
    static Path configuredJar() {
        String configured = System.getProperty("miec.petclinic.jar");
        assertNotNull(configured, "Defina -Dmiec.petclinic.jar para ejecutar esta prueba explícita");
        Path jar = Path.of(configured).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        return jar;
    }

    /**
     * Abre el archivo solo después de terminar la JVM propia. Windows puede liberar
     * su bloqueo después del evento de salida. Solo reintenta H2 90020 por hasta
     * cinco segundos; otros errores y las aserciones de durabilidad no se repiten.
     */
    static Connection openDatabaseAfterExit(String database) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                return DriverManager.getConnection(database + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
            } catch (SQLException failure) {
                if (failure.getErrorCode() != 90020 || System.nanoTime() >= deadline) throw failure;
                TimeUnit.MILLISECONDS.sleep(25);
            }
        }
    }

    /** Inicia un perfil aislado, conserva logs y espera salud real con límite temporal. */
    static PetClinicTestServer start(Path directory, Path jar, String database, String secret, int generation) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Path log = directory.resolve("petclinic-" + generation + ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--spring.profiles.active=miec", "--server.port=" + port,
                "--spring.datasource.url=" + database, "--spring.main.banner-mode=off", "--logging.level.root=WARN");
        builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("MIEC_PETCLINIC_API_KEY", secret);
        var running = new PetClinicTestServer(builder.start(), URI.create("http://127.0.0.1:" + port));
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline && running.process().isAlive()) {
                try {
                    var response = client.send(HttpRequest.newBuilder(running.uri().resolve("/actuator/health"))
                            .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) return running;
                } catch (IOException unavailable) { /* Arranque aún no terminado. */ }
                TimeUnit.MILLISECONDS.sleep(100);
            }
            throw new AssertionError("PetClinic no quedó saludable: " + Files.readString(log));
        } catch (Throwable failure) { running.close(); throw failure; }
    }

    /** Detiene el hijo propio y espera que libere el archivo, incluso ante un fallo de la prueba. */
    @Override public void close() throws InterruptedException {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
    }
}
