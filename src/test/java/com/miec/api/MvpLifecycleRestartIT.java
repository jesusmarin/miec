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
    private final String authorPassword=UUID.randomUUID().toString()+UUID.randomUUID();
    private final String approverPassword=UUID.randomUUID().toString()+UUID.randomUUID();

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
        return post(base,path,body,null);
    }

    /** Envía una identidad real del servidor, sin colocar su secreto en argumentos de proceso o archivos. */
    private HttpResponse<String> post(URI base,String path,String body,String user) throws Exception {
        var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-MIEC-Actor", "restart-test")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        authenticate(request,user);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    /** Consulta el servidor hijo sin usar servicios o repositorios del proceso de pruebas. */
    private HttpResponse<String> get(URI base, String path) throws IOException, InterruptedException {
        return get(base,path,null);
    }

    /** Consulta recursos protegidos después de reiniciar sin reutilizar sesiones del proceso anterior. */
    private HttpResponse<String> get(URI base,String path,String user) throws IOException,InterruptedException {
        var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(2)).GET();
        authenticate(request,user);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    /** Construye Basic únicamente con las cuentas efímeras instaladas por esta prueba. */
    private void authenticate(HttpRequest.Builder request,String user) {
        if(user!=null) request.header("Authorization","Basic "+java.util.Base64.getEncoder().encodeToString(
                (user+":"+(user.equals("restart-author")?authorPassword:approverPassword)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Inicia el JAR contra un archivo propio y espera una respuesta de la API, con plazo finito. */
    private Running start(Path jar, Path directory, String database, int generation) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Path log = directory.resolve("miec-" + generation + ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--server.port=" + port, "--server.address=127.0.0.1",
                "--spring.datasource.url=" + database, "--spring.h2.console.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN", "--logging.level.com.miec=INFO",
                "--logging.level.org.hibernate.SQL=WARN")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("MIEC_AUTHOR_USERNAME","restart-author");
        builder.environment().put("MIEC_AUTHOR_PASSWORD",authorPassword);
        builder.environment().put("MIEC_APPROVER_USERNAME","restart-approver");
        builder.environment().put("MIEC_APPROVER_PASSWORD",approverPassword);
        var process=builder.start();
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

    /** Otra JVM conserva plan, autorización activa, decisión humana y seis transiciones del recorrido. */
    @Test void shouldPersistHumanAuthorizationAcrossJvmRestart() throws Exception {
        String configured=System.getProperty("miec.mvp.jar"); assertNotNull(configured);
        Path jar=Path.of(configured).toAbsolutePath(); assertTrue(Files.isRegularFile(jar));
        Path directory=Files.createTempDirectory(Files.createDirectories(Path.of("target","mvp02-restart-it")),"run-").toAbsolutePath();
        String database="jdbc:h2:file:"+directory.resolve("mvpdb").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        String cycleId,planId,authorizationId,cycleBody,planBody,authorizationBody,historyBody;
        long firstPid;
        try(var first=start(jar,directory,database,1)) {
            firstPid=first.process().pid();
            var system=post(first.base(),"/api/systems","{\"name\":\"MVP02 reinicio\"}"); assertEquals(201,system.statusCode());
            var cycle=post(first.base(),"/api/systems/"+json.readTree(system.body()).path("systemId").asString()+"/cycles","{}"); assertEquals(201,cycle.statusCode());
            cycleId=json.readTree(cycle.body()).path("cycleId").asString(); String path="/api/cycles/"+cycleId;
            assertEquals(200,post(first.base(),path+"/start","{\"expectedVersion\":0,\"reason\":\"inicio\"}").statusCode());
            var objective=post(first.base(),path+"/initialize","{\"expectedVersion\":1,\"objective\":\"Reducir fallos\",\"successCriteria\":\"Prueba satisfactoria\",\"reason\":\"objetivo\"}","restart-author"); assertEquals(200,objective.statusCode());
            var evidence=post(first.base(),path+"/observations","{\"expectedVersion\":2,\"description\":\"Fallo reportado\",\"reason\":\"observación\"}","restart-author"); assertEquals(200,evidence.statusCode());
            String evidenceId=json.readTree(evidence.body()).path("resourceId").asString();
            assertEquals(200,post(first.base(),path+"/diagnosis","{\"expectedVersion\":3,\"evidenceId\":\""+evidenceId+"\",\"summary\":\"Ajustar proceso\",\"reason\":\"diagnóstico\"}","restart-author").statusCode());
            var plan=post(first.base(),path+"/plans","{\"expectedVersion\":4,\"objectiveId\":\""+json.readTree(objective.body()).path("resourceId").asString()+"\",\"expectedOutcomes\":\"Prueba correcta\",\"rollbackStrategy\":\"Restaurar\",\"actions\":[{\"actionType\":\"PROCESS_EXECUTION\",\"description\":\"Ajustar\"}]}","restart-author"); assertEquals(201,plan.statusCode());
            planId=json.readTree(plan.body()).path("planId").asString();
            String actionId=json.readTree(plan.body()).path("actions").get(0).path("actionId").asString();
            var request=post(first.base(),"/api/plans/"+planId+"/authorization-requests","{\"expectedPlanVersion\":0,\"actionId\":\""+actionId+"\",\"validForSeconds\":3600}","restart-author"); assertEquals(201,request.statusCode());
            authorizationId=json.readTree(request.body()).path("authorizationId").asString();
            var approved=post(first.base(),"/api/authorizations/"+authorizationId+"/approve","{\"expectedVersion\":0,\"reason\":\"Revisión humana\"}","restart-approver"); assertEquals(200,approved.statusCode());
            authorizationBody=approved.body(); assertEquals("APPROVED",json.readTree(authorizationBody).path("status").asString());
            planBody=get(first.base(),"/api/plans/"+planId,"restart-approver").body();
            cycleBody=get(first.base(),path).body(); historyBody=get(first.base(),path+"/history").body();
            assertEquals("E06_PREPARADO",json.readTree(cycleBody).path("state").asString());
            assertEquals(6,json.readTree(historyBody).path("items").size());
        }
        awaitFileUnlock(directory.resolve("mvpdb.mv.db"));
        try(var second=start(jar,directory,database,2)) {
            assertNotEquals(firstPid,second.process().pid());
            assertEquals(json.readTree(planBody),json.readTree(get(second.base(),"/api/plans/"+planId,"restart-approver").body()));
            assertEquals(json.readTree(authorizationBody),json.readTree(get(second.base(),"/api/authorizations/"+authorizationId,"restart-approver").body()));
            var context=get(second.base(),"/api/plans/"+planId+"/context","restart-approver");
            assertEquals(200,context.statusCode());
            assertEquals("Ajustar proceso",json.readTree(context.body()).path("diagnosis").path("summary").asString());
            assertEquals(json.readTree(cycleBody),json.readTree(get(second.base(),"/api/cycles/"+cycleId).body()));
            assertEquals(json.readTree(historyBody),json.readTree(get(second.base(),"/api/cycles/"+cycleId+"/history").body()));
            assertEquals(409,post(second.base(),"/api/authorizations/"+authorizationId+"/reject","{\"expectedVersion\":0,\"reason\":\"duplicado\"}","restart-approver").statusCode());
        }
        System.out.println("MVP-02 REINICIO VALIDADO: plan y aprobación humana conservados en otra JVM; sin ejecución de acciones.");
    }
}
