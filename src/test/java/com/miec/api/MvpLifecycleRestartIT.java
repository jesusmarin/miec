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
        return post(base,path,body,"restart-author");
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
        return get(base,path,"restart-author");
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
        return start(jar,directory,database,generation,java.util.Map.of());
    }

    /** Configura un destinatario aislado únicamente para las pruebas de ejecución del JAR. */
    private Running start(Path jar,Path directory,String database,int generation,java.util.Map<String,String> recipient) throws Exception {
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
        builder.environment().putAll(recipient);
        if(!recipient.isEmpty()) {
            builder.command().add("--miec.execution.lease-duration=PT5S");
            builder.command().add("--miec.execution.heartbeat-interval=PT1S");
        }
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
    /** Otra JVM conserva objetivo numérico, línea base, fechas, autor y evidencia de cierre. */
    @Test void shouldPersistMeasuredBaselineAcrossJvmRestart() throws Exception {
        String configured=System.getProperty("miec.mvp.jar"); assertNotNull(configured);
        Path jar=Path.of(configured).toAbsolutePath();
        Path directory=Files.createTempDirectory(Files.createDirectories(Path.of("target","measurement-restart-it")),"run-").toAbsolutePath();
        String database="jdbc:h2:file:"+directory.resolve("mvpdb").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        String path,contextBody,cycleBody,historyBody;
        long pid;
        try(var first=start(jar,directory,database,1)) {
            pid=first.process().pid();
            var system=post(first.base(),"/api/systems","{\"name\":\"Medición reinicio\"}"); assertEquals(201,system.statusCode());
            var cycle=post(first.base(),"/api/systems/"+json.readTree(system.body()).path("systemId").asString()+"/cycles","{}"); assertEquals(201,cycle.statusCode());
            path="/api/cycles/"+json.readTree(cycle.body()).path("cycleId").asString();
            assertEquals(200,post(first.base(),path+"/start","{\"expectedVersion\":0,\"reason\":\"inicio\"}").statusCode());
            var initialized=post(first.base(),path+"/measurement/initialize", """
                    {"expectedVersion":1,"objective":"Latencia","successCriteria":"p95 <= 100 ms","reason":"objetivo",
                     "criteria":[{"name":"p95","unit":"ms","operator":"LTE","target":100}]}
                    ""","restart-author"); assertEquals(200,initialized.statusCode(),initialized.body());
            var context=json.readTree(get(first.base(),path+"/measurement","restart-author").body());
            String indicator=context.path("objectives").get(0).path("criteria").get(0).path("indicatorId").asString();
            var measurement=post(first.base(),path+"/measurement/baselines", """
                    {"expectedVersion":2,"indicatorId":"%s","value":125.12345,"unit":"ms","source":"sensor",
                     "measuredAt":"%s","reason":"línea base"}
                    """.formatted(indicator,java.time.Instant.now().minusSeconds(5)),"restart-author");
            assertEquals(200,measurement.statusCode(),measurement.body());
            var closed=post(first.base(),path+"/measurement/complete","{\"expectedVersion\":3,\"reason\":\"completo\"}","restart-author");
            assertEquals(200,closed.statusCode(),closed.body());
            contextBody=get(first.base(),path+"/measurement","restart-author").body();
            cycleBody=get(first.base(),path).body(); historyBody=get(first.base(),path+"/history").body();
        }
        awaitFileUnlock(directory.resolve("mvpdb.mv.db"));
        try(var second=start(jar,directory,database,2)) {
            assertNotEquals(pid,second.process().pid());
            assertEquals(json.readTree(contextBody),json.readTree(get(second.base(),path+"/measurement","restart-approver").body()));
            assertEquals(json.readTree(cycleBody),json.readTree(get(second.base(),path).body()));
            assertEquals(json.readTree(historyBody),json.readTree(get(second.base(),path+"/history").body()));
            assertEquals(409,post(second.base(),path+"/measurement/complete","{\"expectedVersion\":4,\"reason\":\"repetido\"}","restart-author").statusCode());
        }
        System.out.println("MVP v01 MEDICIONES: objetivo, indicadores, línea base y cierre conservados entre dos JVM.");
    }

    /** Comando JSON con identidad real y comprobación de HTTP antes de leer el resultado. */
    private tools.jackson.databind.JsonNode checkedPost(Running running,String path,Object command,String user,int status) throws Exception {
        var response=post(running.base(),path,json.writeValueAsString(command),user);
        assertEquals(status,response.statusCode(),response.body());return json.readTree(response.body());
    }

    /** Crea y aprueba un plan ejecutable íntegramente por las APIs del JAR. */
    private tools.jackson.databind.JsonNode executablePlan(Running running) throws Exception {
        var system=checkedPost(running,"/api/systems",java.util.Map.of("name","ejecución entre JVM"),"restart-author",201);
        var cycle=checkedPost(running,"/api/systems/"+system.path("systemId").asString()+"/cycles",java.util.Map.of(),"restart-author",201);
        String path="/api/cycles/"+cycle.path("cycleId").asString();
        checkedPost(running,path+"/start",java.util.Map.of("expectedVersion",0,"reason","inicio"),"restart-author",200);
        var objective=checkedPost(running,path+"/initialize",java.util.Map.of("expectedVersion",1,"objective","objetivo","successCriteria","criterio","reason","r"),"restart-author",200);
        var observation=checkedPost(running,path+"/observations",java.util.Map.of("expectedVersion",2,"description","dato","reason","r"),"restart-author",200);
        checkedPost(running,path+"/diagnosis",java.util.Map.of("expectedVersion",3,"evidenceId",observation.path("resourceId").asString(),"summary","diagnóstico","reason","r"),"restart-author",200);
        var spec=java.util.Map.of("adapterId","http-recipient-v1","adapterVersion","1","destinationId","restart-destination","mediaType","application/json","body","{\"change\":1}");
        var plan=checkedPost(running,path+"/plans",java.util.Map.of("expectedVersion",4,"objectiveId",objective.path("resourceId").asString(),"expectedOutcomes","resultado","rollbackStrategy","restaurar",
                "actions",java.util.List.of(java.util.Map.of("actionType","PROCESS_EXECUTION","description","acción HTTP","execution",spec))),"restart-author",201);
        var request=checkedPost(running,"/api/plans/"+plan.path("planId").asString()+"/authorization-requests",java.util.Map.of("expectedPlanVersion",0,"actionId",plan.path("actions").get(0).path("actionId").asString(),"validForSeconds",600),"restart-author",201);
        checkedPost(running,"/api/authorizations/"+request.path("authorizationId").asString()+"/approve",java.util.Map.of("expectedVersion",0,"reason","aprobar"),"restart-approver",200);
        return json.readTree(get(running.base(),"/api/plans/"+plan.path("planId").asString()).body());
    }

    /** Variables de servidor; la clave no aparece en argumentos de proceso ni en archivos. */
    private java.util.Map<String,String> recipientEnvironment(com.miec.integration.contract.HttpRecipientFixture recipient) {
        return java.util.Map.of("MIEC_RECIPIENT_ENABLED","true","MIEC_RECIPIENT_DESTINATION_ID","restart-destination",
                "MIEC_RECIPIENT_BASE_URL",recipient.base().toString(),"MIEC_RECIPIENT_API_KEY",com.miec.integration.contract.HttpRecipientFixture.API_KEY);
    }

    /** MIEC y el ledger del destinatario se reabren conservando efecto y resultado sin nuevos envíos. */
    @Test void shouldPersistExecutableRunAndRecipientEffectAcrossRestart() throws Exception {
        Path jar=Path.of(java.util.Objects.requireNonNull(System.getProperty("miec.mvp.jar"))).toAbsolutePath();
        Path directory=Files.createTempDirectory(Files.createDirectories(Path.of("target","execution-restart-it")),"run-").toAbsolutePath();
        String database="jdbc:h2:file:"+directory.resolve("miec").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        String recipientDb="jdbc:h2:file:"+directory.resolve("recipient").toString().replace('\\','/')+";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        String planId,cycleId,runBody;
        try(var recipient=new com.miec.integration.contract.HttpRecipientFixture(recipientDb)) {
            try(var first=start(jar,directory,database,1,recipientEnvironment(recipient))) {
                var plan=executablePlan(first);planId=plan.path("planId").asString();cycleId=plan.path("cycleId").asString();
                var cycle=json.readTree(get(first.base(),"/api/cycles/"+cycleId).body());
                var response=checkedPost(first,"/api/cycles/"+cycleId+"/plans/"+planId+"/execution",java.util.Map.of("expectedCycleVersion",cycle.path("version").asLong(),"expectedPlanVersion",plan.path("version").asLong(),"reason","ejecutar"),"restart-author",201);
                assertEquals("APPLIED",response.path("state").asString());assertEquals(1,recipient.effects());
                var currentCycle=json.readTree(get(first.base(),"/api/cycles/"+cycleId).body());
                var finalized=checkedPost(first,"/api/plans/"+planId+"/execution/finish",java.util.Map.of("expectedVersion",response.path("version").asLong(),
                        "expectedCycleVersion",currentCycle.path("version").asLong(),"reason","revisión técnica"),"restart-approver",200);
                assertEquals("E08_VERIFICANDO",finalized.path("cycleState").asString());
                runBody=get(first.base(),"/api/plans/"+planId+"/execution").body();
            }
        }
        awaitFileUnlock(directory.resolve("miec.mv.db"));
        try(var reopened=new com.miec.integration.contract.HttpRecipientFixture(recipientDb)) {
            assertEquals(1,reopened.effects());
            try(var second=start(jar,directory,database,2,recipientEnvironment(reopened))) {
                assertEquals(json.readTree(runBody),json.readTree(get(second.base(),"/api/plans/"+planId+"/execution").body()));
                checkedPost(second,"/api/cycles/"+cycleId+"/plans/"+planId+"/execution",java.util.Map.of("expectedCycleVersion",0,"expectedPlanVersion",0,"reason","repetido"),"restart-author",409);
                assertEquals(1,reopened.effects());
            }
        }
        System.out.println("MVP-03 REINICIO: E08, coordinación IR-009 y efecto HTTP conservados; ledger del destinatario reabierto sin duplicación.");
    }

    /** Matar la JVM tras el efecto deja incertidumbre durable; reiniciar y recuperar nunca reenvían a ciegas. */
    @Test void shouldBlockReplayAfterJvmDiesFollowingRecipientCommit() throws Exception {
        Path jar=Path.of(java.util.Objects.requireNonNull(System.getProperty("miec.mvp.jar"))).toAbsolutePath();
        Path directory=Files.createTempDirectory(Files.createDirectories(Path.of("target","execution-crash-it")),"run-").toAbsolutePath();
        String database="jdbc:h2:file:"+directory.resolve("miec").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        String recipientDb="jdbc:h2:file:"+directory.resolve("recipient").toString().replace('\\','/')+";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        try(var recipient=new com.miec.integration.contract.HttpRecipientFixture(recipientDb)) {
            recipient.mode("HOLD_AFTER_COMMIT");String planId;
            try(var first=start(jar,directory,database,1,recipientEnvironment(recipient))) {
                var plan=executablePlan(first);planId=plan.path("planId").asString();String cycleId=plan.path("cycleId").asString();
                var cycle=json.readTree(get(first.base(),"/api/cycles/"+cycleId).body());
                var request=HttpRequest.newBuilder(first.base().resolve("/api/cycles/"+cycleId+"/plans/"+planId+"/execution"))
                        .header("Content-Type","application/json").timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of("expectedCycleVersion",cycle.path("version").asLong(),"expectedPlanVersion",plan.path("version").asLong(),"reason","probar caída"))));
                authenticate(request,"restart-author");var pending=client.sendAsync(request.build(),HttpResponse.BodyHandlers.ofString());
                try {
                    assertTrue(recipient.awaitCommit());first.process().destroyForcibly();assertTrue(first.process().waitFor(10,TimeUnit.SECONDS));
                } finally { pending.cancel(true);recipient.release(); }
            }
            awaitFileUnlock(directory.resolve("miec.mv.db"));
            try(var second=start(jar,directory,database,2,recipientEnvironment(recipient))) {
                var run=json.readTree(get(second.base(),"/api/plans/"+planId+"/execution").body());
                long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
                while(!run.path("requiresReview").asBoolean()&&System.nanoTime()<deadline) {
                    TimeUnit.MILLISECONDS.sleep(100);run=json.readTree(get(second.base(),"/api/plans/"+planId+"/execution").body());
                }
                assertTrue(run.path("requiresReview").asBoolean());
                checkedPost(second,"/api/plans/"+planId+"/execution/resume",java.util.Map.of("expectedVersion",run.path("version").asLong(),"reason","no reenviar"),"restart-author",409);
                var recovered=checkedPost(second,"/api/plans/"+planId+"/execution/recover",java.util.Map.of("expectedVersion",run.path("version").asLong(),"reason","revisar incertidumbre"),"restart-approver",200);
                assertEquals("RECOVERY_REQUIRED",recovered.path("state").asString());assertEquals("E13_RECUPERANDO",recovered.path("cycleState").asString());
                String path="/api/plans/"+planId+"/execution";
                var permission=checkedPost(second,path+"/reconciliation-requests",java.util.Map.of("expectedVersion",recovered.path("version").asLong(),
                        "actionId",recovered.path("steps").get(0).path("actionId").asString(),"validForSeconds",600,"reason","consultar después de caída"),"restart-author",201);
                checkedPost(second,"/api/execution/reconciliation-authorizations/"+permission.path("authorizationId").asString()+"/approve",
                        java.util.Map.of("expectedVersion",0,"reason","autorizar consulta"),"restart-approver",200);
                var current=json.readTree(get(second.base(),path).body());
                var confirmed=checkedPost(second,path+"/reconcile",java.util.Map.of("expectedVersion",current.path("version").asLong(),
                        "actionId",permission.path("actionId").asString(),"authorizationId",permission.path("authorizationId").asString(),"reason","recuperar efecto remoto"),"restart-approver",200);
                assertEquals("APPLIED",confirmed.path("state").asString());
                var currentCycle=json.readTree(get(second.base(),"/api/cycles/"+confirmed.path("cycleId").asString()).body());
                var finalized=checkedPost(second,path+"/finish",java.util.Map.of("expectedVersion",confirmed.path("version").asLong(),
                        "expectedCycleVersion",currentCycle.path("version").asLong(),"reason","efecto confirmado tras reinicio"),"restart-approver",200);
                assertEquals("E08_VERIFICANDO",finalized.path("cycleState").asString());
                assertEquals(1,recipient.effects());
            }
        }
        System.out.println("MVP-03 CAÍDA JVM: incertidumbre preservada, consulta remota autorizada, reconciliación y E08; un solo efecto.");
    }
}
