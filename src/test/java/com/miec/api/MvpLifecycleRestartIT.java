package com.miec.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Prueba explícita del JAR: crea/inicia por HTTP y comprueba persistencia en una segunda JVM. */
class MvpLifecycleRestartIT {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /** Maneja solo el proceso hijo creado por esta prueba. */
    private record Running(Process process, URI base) implements AutoCloseable {
        /** Termina la JVM propia y espera antes de intentar abrir su archivo desde otro proceso. */
        @Override public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    /** Envía comandos reales a la aplicación aislada, incluyendo la identidad de auditoría. */
    private HttpResponse<String> post(URI base, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-MIEC-Actor", "restart-test")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Consulta el servidor hijo sin usar servicios o repositorios del proceso de pruebas. */
    private HttpResponse<String> get(URI base, String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Inicia el JAR contra un archivo propio y espera una respuesta de la API, con plazo finito. */
    private Running start(Path jar, Path directory, String database, int generation) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Path log = directory.resolve("miec-" + generation + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--server.port=" + port, "--server.address=127.0.0.1",
                "--spring.datasource.url=" + database, "--spring.h2.console.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN", "--logging.level.com.miec=INFO",
                "--logging.level.org.hibernate.SQL=WARN")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var running = new Running(process, URI.create("http://127.0.0.1:" + port));
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    var response = get(running.base(), "/api/systems/" + UUID.randomUUID());
                    if (response.statusCode() == 404 && response.body().contains("SYSTEM_NOT_FOUND")) return running;
                } catch (IOException unavailable) { /* Espera salud real de la API, no un tiempo fijo. */ }
                TimeUnit.MILLISECONDS.sleep(100);
            }
            throw new AssertionError("La API MVP no inició: " + Files.readString(log));
        } catch (Throwable failure) { running.close(); throw failure; }
    }

    /** Espera únicamente la liberación del archivo propio después de terminar la JVM en Windows. */
    private void awaitFileUnlock(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock != null) return;
            }
            if (System.nanoTime() >= deadline) throw new AssertionError("El archivo de la JVM terminada sigue bloqueado");
            TimeUnit.MILLISECONDS.sleep(25);
        }
    }

    /** La segunda JVM conserva IDs, fechas, versión e historial y rechaza reiniciar otra vez. */
    @Test void shouldPersistLifecycleAcrossJvmRestart() throws Exception {
        String configured = System.getProperty("miec.mvp.jar");
        assertNotNull(configured, "Defina -Dmiec.mvp.jar=<jar> y ejecute -Dtest=MvpLifecycleRestartIT");
        Path jar = Path.of(configured).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        Path directory = Files.createTempDirectory(Files.createDirectories(Path.of("target", "mvp01-restart-it")), "run-").toAbsolutePath();
        String database = "jdbc:h2:file:" + directory.resolve("mvpdb").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        String systemId, cycleId, systemBody, cycleBody, historyBody;
        long firstPid;
        try (var first = start(jar, directory, database, 1)) {
            firstPid = first.process().pid();
            var system = post(first.base(), "/api/systems", "{\"name\":\"MVP reinicio\",\"domain\":\"software\"}");
            assertEquals(201, system.statusCode());
            systemBody = system.body();
            systemId = json.readTree(systemBody).path("systemId").asString();
            var cycle = post(first.base(), "/api/systems/" + systemId + "/cycles", "{}");
            assertEquals(201, cycle.statusCode());
            cycleId = json.readTree(cycle.body()).path("cycleId").asString();
            var started = post(first.base(), "/api/cycles/" + cycleId + "/start", "{\"expectedVersion\":0,\"reason\":\"validar persistencia MVP\"}");
            assertEquals(200, started.statusCode());
            cycleBody = started.body();
            historyBody = get(first.base(), "/api/cycles/" + cycleId + "/history").body();
            assertEquals(1, json.readTree(historyBody).path("items").size());
        }
        awaitFileUnlock(directory.resolve("mvpdb.mv.db"));
        try (var second = start(jar, directory, database, 2)) {
            assertNotEquals(firstPid, second.process().pid());
            assertEquals(json.readTree(systemBody), json.readTree(get(second.base(), "/api/systems/" + systemId).body()));
            assertEquals(json.readTree(cycleBody), json.readTree(get(second.base(), "/api/cycles/" + cycleId).body()));
            assertEquals(json.readTree(historyBody), json.readTree(get(second.base(), "/api/cycles/" + cycleId + "/history").body()));
            assertEquals(409, post(second.base(), "/api/cycles/" + cycleId + "/start", "{\"expectedVersion\":0,\"reason\":\"repetido\"}").statusCode());
        }
        System.out.println("MVP-01 REINICIO VALIDADO: dos JVM, mismo archivo, sistema/ciclo/historial conservados; un único inicio.");
    }
}
