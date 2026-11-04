package com.miec.api;

import com.miec.support.PostgresTestDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Valida el perfil PostgreSQL del JAR y su persistencia HTTP entre dos JVM independientes. */
@EnabledIfSystemProperty(named="miec.postgres.test",matches="true")
@DisplayName("PostgreSQL - arranque del JAR, autenticacion y reinicio")
class PostgresApplicationRestartIT {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    private final String password=UUID.randomUUID().toString();
    /** Termina exclusivamente la JVM iniciada por esta prueba antes de eliminar su base propia. */
    private record Running(Process process,URI base) implements AutoCloseable {
        /** Cierre acotado, incluido el caso de un fallo de asercion HTTP. */
        @Override public void close() throws InterruptedException {
            process.destroy();
            if(!process.waitFor(15,TimeUnit.SECONDS)){
                process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));
            }
        }
    }
    /** Consulta o envia comandos autenticados sin revelar las credenciales en argumentos o logs. */
    private HttpResponse<String> request(URI base,String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString(("postgres-author:"+password).getBytes(StandardCharsets.UTF_8)));
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    /** Arranca en loopback con credenciales efimeras y un plazo finito de disponibilidad. */
    private Running start(Path jar,Path directory,PostgresTestDatabase database,int generation) throws Exception {
        int port;try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-jar",jar.toString(),"--spring.profiles.active=postgres","--server.address=127.0.0.1","--server.port="+port,
                "--spring.main.banner-mode=off","--logging.level.root=WARN","--logging.level.org.hibernate.SQL=WARN")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("postgres-jvm-"+generation+".log").toFile());
        database.configure(builder);
        builder.environment().put("MIEC_AUTHOR_USERNAME","postgres-author");
        builder.environment().put("MIEC_AUTHOR_PASSWORD",password);
        builder.environment().put("MIEC_APPROVER_USERNAME","postgres-approver");
        builder.environment().put("MIEC_APPROVER_PASSWORD",UUID.randomUUID().toString());
        var running=new Running(builder.start(),URI.create("http://127.0.0.1:"+port));
        try{
            long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
            while(running.process().isAlive()&&System.nanoTime()<deadline){
                try{if(request(running.base(),"/api/systems/"+UUID.randomUUID(),null).statusCode()==404)return running;}
                catch(java.io.IOException unavailable){/* El servidor aun esta iniciando. */}
                Thread.sleep(200);
            }
            throw new AssertionError("El JAR PostgreSQL no estuvo disponible; revisar target/postgres-restart-it");
        }catch(Throwable failure){running.close();throw failure;}
    }
    /** Conserva sistema, ciclo y auditoria, y rechaza un inicio duplicado despues del reinicio. */
    @Test @DisplayName("Dos JVM conservan sistema, ciclo versionado y auditoria PostgreSQL")
    void persistsAcrossJvmRestart() throws Exception {
        var jar=Path.of(Objects.requireNonNull(System.getProperty("miec.mvp.jar"),"Defina -Dmiec.mvp.jar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        var directory=Files.createTempDirectory(Files.createDirectories(Path.of("target/postgres-restart-it")),"run-").toAbsolutePath();
        try(var database=PostgresTestDatabase.create("restart")){
            String systemId,cycleId,systemBody,cycleBody,historyBody;long firstPid;
            try(var first=start(jar,directory,database,1)){
                firstPid=first.process().pid();
                var system=request(first.base(),"/api/systems","{\"name\":\"PostgreSQL restart\",\"domain\":\"software\"}");
                assertEquals(201,system.statusCode());systemBody=system.body();systemId=json.readTree(systemBody).path("systemId").asString();
                var cycle=request(first.base(),"/api/systems/"+systemId+"/cycles","{}");assertEquals(201,cycle.statusCode());
                cycleId=json.readTree(cycle.body()).path("cycleId").asString();
                var started=request(first.base(),"/api/cycles/"+cycleId+"/start","{\"expectedVersion\":0,\"reason\":\"validar PostgreSQL\"}");
                assertEquals(200,started.statusCode());cycleBody=started.body();
                var history=request(first.base(),"/api/cycles/"+cycleId+"/history",null);assertEquals(200,history.statusCode());historyBody=history.body();
            }
            try(var second=start(jar,directory,database,2)){
                assertNotEquals(firstPid,second.process().pid());
                assertEquals(json.readTree(systemBody),json.readTree(request(second.base(),"/api/systems/"+systemId,null).body()));
                assertEquals(json.readTree(cycleBody),json.readTree(request(second.base(),"/api/cycles/"+cycleId,null).body()));
                assertEquals(json.readTree(historyBody),json.readTree(request(second.base(),"/api/cycles/"+cycleId+"/history",null).body()));
                assertEquals(409,request(second.base(),"/api/cycles/"+cycleId+"/start","{\"expectedVersion\":0,\"reason\":\"repetido\"}").statusCode());
            }
        }
        System.out.println("POSTGRESQL REINICIO VALIDADO: dos JVM, sistema/ciclo/auditoria conservados y comando duplicado rechazado.");
    }
}
