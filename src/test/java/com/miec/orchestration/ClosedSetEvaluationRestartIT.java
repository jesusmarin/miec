package com.miec.orchestration;

import com.miec.MiecPrototypeApplication;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.PostMeasurementTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Guardado interno y lectura por el JAR real sobre la misma base, sin endpoints de creación anticipados. */
@DisplayName("MVP-06.3 — conclusión recuperable entre JVM")
class ClosedSetEvaluationRestartIT {
    /** Prepara contexto en proceso propio, cierra conexiones y verifica lectura idéntica desde otra JVM. */
    @Test @DisplayName("Snapshot, métricas, EV y políticas persisten después de cambiar de JVM")
    void shouldReadInternalConclusionFromPackagedApplication() throws Exception {
        Path jar=Path.of(Objects.requireNonNull(System.getProperty("miec.mvp.jar"),"Defina miec.mvp.jar")).toAbsolutePath();assertTrue(Files.isRegularFile(jar));
        Path directory=Files.createTempDirectory(Files.createDirectories(Path.of("target","evaluation-restart-it")),"run-").toAbsolutePath();
        String database="jdbc:h2:file:"+directory.resolve("evaluation").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        EvaluationContracts.Saved saved;
        var application=new SpringApplication(MiecPrototypeApplication.class,ExecutionTestConfiguration.class);
        try(var context=application.run("--server.port=0","--server.address=127.0.0.1","--spring.datasource.url="+database,
                "--spring.datasource.username=sa","--spring.datasource.password=","--miec.recipient.enabled=false")) {
            var cycles=context.getBean(LifecycleService.class);var measures=context.getBean(MeasurementWorkflow.class);
            var fixture=ready(cycles,measures,context.getBean(InterventionWorkflow.class),context.getBean(PlanExecutionApplicationService.class));
            var posts=context.getBean(PostMeasurementWorkflow.class);
            var recorded=posts.record(fixture.cycleId(),fixture.planId(),command(fixture,cycles.getCycle(fixture.cycleId()).version()),"author");var post=recorded.measurements().getFirst();
            var sealed=posts.seal(fixture.cycleId(),fixture.planId(),new SealPost(recorded.cycleVersion(),fixture.runId(),fixture.baselineAt().minusSeconds(1),fixture.baselineAt().plusSeconds(1),
                    post.measuredAt(),now(),post.method(),List.of(new PairSelection(fixture.indicatorId(),fixture.baselineId(),post.measurementId())),"conjunto"),"approver");
            saved=new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(tx->context.getBean(ClosedSetEvaluationStore.class)
                    .persist(fixture.planId(),sealed.set().setId(),sealed.cycleVersion(),"approver"));
        } finally {SecurityContextHolder.clearContext();}
        int port;try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        String password=UUID.randomUUID().toString()+UUID.randomUUID();
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),
                "--server.port="+port,"--server.address=127.0.0.1","--spring.datasource.url="+database,"--spring.datasource.username=sa","--spring.datasource.password=","--miec.recipient.enabled=false")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("reader.log").toFile());
        builder.environment().put("MIEC_AUTHOR_USERNAME","evaluation-reader");builder.environment().put("MIEC_AUTHOR_PASSWORD",password);
        builder.environment().put("MIEC_APPROVER_PASSWORD","");
        var process=builder.start();var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var uri=URI.create("http://127.0.0.1:"+port+"/api/evaluations/"+saved.report().result().id());
        try {
            HttpResponse<String> response=null;long deadline=System.nanoTime()+Duration.ofSeconds(75).toNanos();
            while(System.nanoTime()<deadline) {
                assertTrue(process.isAlive(),"La JVM hija terminó; consultar reader.log en el directorio propio de test");
                try {
                    response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Authorization","Basic "+Base64.getEncoder().encodeToString(("evaluation-reader:"+password).getBytes(StandardCharsets.UTF_8))).GET().build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()==200)break;
                }catch(java.io.IOException unavailable){/* Arranque con plazo acotado. */}
                TimeUnit.MILLISECONDS.sleep(200);
            }
            assertNotNull(response);assertEquals(200,response.statusCode(),response.body());var json=JsonMapper.builder().build();
            assertEquals(json.readTree(json.writeValueAsString(saved)),json.readTree(response.body()));
            System.out.println("MVP-06 REINICIO: snapshot y conclusión internos recuperados desde el JAR, con políticas y EV originales; E08 sin transición anticipada.");
        } finally {
            process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}
        }
    }
}
