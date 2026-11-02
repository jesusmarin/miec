package com.miec.experiment.petclinic;

import com.miec.action.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba explícita entre procesos. Requiere -Dmiec.petclinic.jar=<jar> y se
 * ejecuta con -Dtest=PetClinicProcessIT; no arranca procesos en la regresión habitual.
 */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:petclinic_process_it;DB_CLOSE_DELAY=-1")
@Import(PetClinicEvidenceVerifier.class)
class PetClinicProcessIT {
    private final Path temporary=workspaceDirectory();
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IdempotentActionExecutionService execution;
    @Autowired private ExecutionAttemptService attempts;
    @Autowired private ExecutionOperationRegistry operations;
    @Autowired private PetClinicEvidenceVerifier verifier;
    @Autowired private ExecutionReconciliationService reconciliation;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String secret=UUID.randomUUID().toString()+UUID.randomUUID();

    /** Conserva base y registros bajo target para inspección, sin limpieza de directorios ajenos en Windows. */
    private static Path workspaceDirectory() {
        try {
            return Files.createTempDirectory(Files.createDirectories(Path.of("target","petclinic-process-it")),"run-").toAbsolutePath();
        } catch(IOException failure) {throw new java.io.UncheckedIOException(failure);}
    }

    /** Proceso aislado cuyo cierre nunca afecta otra instancia de PetClinic. */
    private record Running(Process process,URI uri) implements AutoCloseable {
        /** Detiene exclusivamente el hijo iniciado por esta prueba y espera la liberación de H2. */
        @Override public void close() throws InterruptedException {
            process.destroy();
            if(!process.waitFor(15,TimeUnit.SECONDS)) {process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}
        }
    }

    /** Inicia el JAR con perfil persistente y espera una condición de salud real, con límite. */
    private Running start(Path jar,String database,int generation) throws Exception {
        int port;
        try(var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())) {port=socket.getLocalPort();}
        Path log=temporary.resolve("petclinic-"+generation+".log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-jar",jar.toString(),"--spring.profiles.active=miec","--server.port="+port,
                "--spring.datasource.url="+database,"--spring.main.banner-mode=off","--logging.level.root=WARN");
        builder.directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("MIEC_PETCLINIC_API_KEY",secret);
        var process=builder.start();var running=new Running(process,URI.create("http://127.0.0.1:"+port));
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
            while(System.nanoTime()<deadline && process.isAlive()) {
                try {
                    var response=client.send(HttpRequest.newBuilder(running.uri().resolve("/actuator/health"))
                            .timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding());
                    if(response.statusCode()==200) return running;
                } catch(IOException unavailable) { /* Condición de arranque todavía no satisfecha. */ }
                TimeUnit.MILLISECONDS.sleep(100);
            }
            throw new AssertionError("PetClinic no quedó saludable: "+Files.readString(log));
        } catch(Throwable failure) {running.close();throw failure;}
    }

    /** Consulta el archivo después de cerrar el proceso para contar efectos durables reales. */
    private void assertSingleDurableVisit(String database,String description,UUID key) throws Exception {
        try(var connection=DriverManager.getConnection(database+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r","sa","");
            var visits=connection.prepareStatement("SELECT COUNT(*) FROM visits WHERE description=?");
            var keys=connection.prepareStatement("SELECT COUNT(*) FROM miec_visit_operations WHERE operation_key=?")) {
            visits.setString(1,description);keys.setObject(1,key);
            try(var count=visits.executeQuery()) {assertTrue(count.next());assertEquals(1,count.getInt(1));}
            try(var count=keys.executeQuery()) {assertTrue(count.next());assertEquals(1,count.getInt(1));}
        }
    }

    /**
     * Confirma el efecto en PetClinic, pierde la respuesta en el transporte,
     * verifica y reconcilia desde MIEC y reinicia la JVM destinataria. La consulta
     * y el replay posteriores conservan referencia y una sola visita en el archivo.
     */
    @Test @DisplayName("C09 real: respuesta perdida, evidencia autenticada y reinicio conservan una sola visita")
    void shouldSurviveLostResponseAndPetClinicRestart() throws Exception {
        String configured=System.getProperty("miec.petclinic.jar");
        assertNotNull(configured,"Defina -Dmiec.petclinic.jar para ejecutar esta prueba explícita");
        Path jar=Path.of(configured).toAbsolutePath();assertTrue(Files.isRegularFile(jar));
        String database="jdbc:h2:file:"+temporary.resolve("petclinic-db").toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        var f=ExecutionRecoveryTestSupport.fixture(jdbc);
        String description="C09-process-"+UUID.randomUUID();
        var request=PetClinicVisitExecutor.visitRequest(900001,900001,LocalDate.now().plusDays(7),description);
        var committed=new CountDownLatch(1);IdempotentActionExecutor.Context context;String reference;long initialPid;
        try(var first=start(jar,database,1)) {
            initialPid=first.process().pid();
            var proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            proxy.createContext("/miec/",exchange->{
                try(exchange) {
                    var upstream=HttpRequest.newBuilder(first.uri().resolve(exchange.getRequestURI().getPath())).timeout(Duration.ofSeconds(10));
                    for(String header:new String[]{"X-MIEC-Api-Key","Content-Type","Idempotency-Key","X-MIEC-Destination","X-MIEC-Fingerprint","X-MIEC-Fencing-Token"}) {
                        String value=exchange.getRequestHeaders().getFirst(header);if(value!=null) upstream.header(header,value);
                    }
                    boolean post=exchange.getRequestMethod().equals("POST");
                    if(post) upstream.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));else upstream.GET();
                    var response=client.send(upstream.build(),HttpResponse.BodyHandlers.ofByteArray());
                    if(post) {
                        if(response.statusCode()==201||response.statusCode()==200) committed.countDown();
                        // No headers are sent downstream: the caller loses a response after upstream commit.
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
                } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
                catch(IOException disconnected) {exchange.close();}
            });proxy.start();
            try {
                var lossy=new PetClinicVisitExecutor(URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()),secret,request,Duration.ofSeconds(5));
                assertThrows(IllegalStateException.class,()->execution.execute(f.command(),lossy));
                assertTrue(committed.await(5,TimeUnit.SECONDS),"Debe perderse la respuesta después de confirmar el efecto");
            } finally {proxy.stop(0);}
            var snapshot=attempts.findHistory(f.plan(),f.action()).getFirst();
            assertEquals(ExecutionRetryPolicy.ExecutionStatus.UNKNOWN,snapshot.status());
            context=new IdempotentActionExecutor.Context(operations.findAuthorized(f.command()).orElseThrow(),snapshot.ownership(),request);
            var direct=new PetClinicVisitExecutor(first.uri(),secret,request,Duration.ofSeconds(5));
            var proof=verifier.verifyApplied(UUID.randomUUID(),direct,context,f.reviewAuthorization(),"reviewer");
            reconciliation.decide(new ExecutionReconciliationService.Command(snapshot.executionId(),proof.id(),
                    ExecutionReconciliationPolicy.Resolution.APPLIED,f.reviewAuthorization(),"reviewer","Resultado autenticado de PetClinic"));
            reference=execution.recoverResult(f.command()).orElseThrow().reference();
            assertEquals(reference,direct.execute(context));
            assertThrows(AuthorizedActionExecutionService.ExecutionDeniedException.class,()->execution.execute(f.command(),direct));
            var wrong=new PetClinicVisitExecutor(first.uri(),"incorrect-key-for-c09-test",request,Duration.ofSeconds(5));
            assertEquals(IdempotentActionExecutor.Status.UNKNOWN,wrong.lookup(context).status());
            System.out.println("C09 PROCESO: respuesta perdida posterior a commit verificada; MIEC conserva UNKNOWN y resolución APPLIED.");
        }
        assertSingleDurableVisit(database,description,context.operation().key());
        try(var restarted=start(jar,database,2)) {
            assertNotEquals(initialPid,restarted.process().pid());
            var direct=new PetClinicVisitExecutor(restarted.uri(),secret,request,Duration.ofSeconds(5));
            assertEquals(reference,direct.lookup(context).resultReference());assertEquals(reference,direct.execute(context));
        }
        assertSingleDurableVisit(database,description,context.operation().key());
        assertEquals(1,attempts.findHistory(f.plan(),f.action()).size());
        System.out.println("C09 PROCESO VALIDADO: nueva JVM, mismo archivo, misma clave y referencia; una visita y una operación durables.");
    }
}
