package com.miec.orchestration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.miec.orchestration.MeasurementContracts.*;
import static com.miec.orchestration.PostMeasurementTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Consultas HTTP reales y ausencia explícita de creación operativa antes de 06.4. */
@Import(ExecutionTestConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:eval_api;DB_CLOSE_DELAY=-1","server.address=127.0.0.1",
        "miec.api.author.username=author","miec.api.author.password=eval-author-api-test",
        "miec.api.approver.username=approver","miec.api.approver.password=eval-approver-api-test"})
@DisplayName("MVP-06.3 — consulta HTTP autenticada")
class EvaluationApiTest {
    @LocalServerPort int port;
    @Autowired LifecycleService cycles;
    @Autowired MeasurementWorkflow measures;
    @Autowired InterventionWorkflow intervention;
    @Autowired PlanExecutionApplicationService execution;
    @Autowired PostMeasurementWorkflow posts;
    @Autowired ClosedSetEvaluationStore store;
    @Autowired EvaluationApplicationService service;
    @Autowired PlatformTransactionManager transactions;
    private final JsonMapper json=JsonMapper.builder().build();
    /** No conserva la identidad simulada de preparación entre pruebas. */
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    /** HTTP con Basic y plazo acotado; POST solo se usa para comprobar su rechazo. */
    private HttpResponse<String> send(String path,String user,boolean post)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(15));
        if(user!=null)request.header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+(user.equals("author")?"eval-author-api-test":"eval-approver-api-test")).getBytes(StandardCharsets.UTF_8)));
        if(post)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));else request.GET();
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    /** Guarda desde el API interno transaccional, sin agregar endpoints de test a producción. */
    private EvaluationContracts.Saved seed() {
        var f=ready(cycles,measures,intervention,execution);var context=posts.record(f.cycleId(),f.planId(),command(f,cycles.getCycle(f.cycleId()).version()),"author");var post=context.measurements().getFirst();
        var sealed=posts.seal(f.cycleId(),f.planId(),new SealPost(context.cycleVersion(),f.runId(),f.baselineAt().minusSeconds(1),f.baselineAt().plusSeconds(1),post.measuredAt(),now(),post.method(),
                List.of(new PairSelection(f.indicatorId(),f.baselineId(),post.measurementId())),"fijar"),"approver");
        return new TransactionTemplate(transactions).execute(tx->store.persist(f.planId(),sealed.set().setId(),sealed.cycleVersion(),"approver"));
    }
    /** Ambos roles leen la misma conclusión; sin identidad no hay acceso y POST no está implementado. */
    @Test @DisplayName("Consulta snapshot con Basic y rechaza creación por HTTP")
    void shouldReadPersistedSnapshotWithoutPublishingCreation()throws Exception {
        var saved=seed();String path="/api/evaluations/"+saved.report().result().id();
        assertEquals(401,send(path,null,false).statusCode());
        for(String user:List.of("author","approver")){
            var response=send(path,user,false);assertEquals(200,response.statusCode(),response.body());assertEquals(json.readTree(json.writeValueAsString(saved)),json.readTree(response.body()));
        }
        var list=send("/api/measurement-sets/"+saved.inputs().setId()+"/evaluations","author",false);assertEquals(200,list.statusCode());assertEquals(1,json.readTree(list.body()).size());
        assertEquals(405,send(path,"author",true).statusCode());assertEquals("E08_VERIFICANDO",cycles.getCycle(saved.inputs().cycleId()).state());
        SecurityContextHolder.clearContext();assertThrows(LifecycleException.class,()->service.get(saved.report().result().id()));
    }
    /** Documenta únicamente GET y conserva inexistencia e IDs inválidos como errores controlados. */
    @Test @DisplayName("OpenAPI publica solo GET y errores de consulta controlados")
    void shouldDocumentReadOnlyRoutesAndErrors()throws Exception {
        assertEquals(404,send("/api/evaluations/"+UUID.randomUUID(),"author",false).statusCode());
        assertEquals(400,send("/api/evaluations/not-a-uuid","author",false).statusCode());
        var paths=json.readTree(send("/v3/api-docs","author",false).body()).path("paths");
        for(String route:List.of("/api/evaluations/{evaluationId}","/api/measurement-sets/{setId}/evaluations")) {
            assertFalse(paths.path(route).path("get").path("security").isEmpty());assertFalse(paths.path(route).has("post"));
        }
    }
}
