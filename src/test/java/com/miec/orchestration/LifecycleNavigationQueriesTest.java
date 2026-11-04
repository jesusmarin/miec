package com.miec.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.miec.orchestration.LifecycleCommands.*;

/** Fixtures propios comprueban lectura, paginacion y agregacion sin depender de datos del piloto. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:lifecycle_navigation;DB_CLOSE_DELAY=-1")
@DisplayName("MVP-09 - consultas de navegacion")
class LifecycleNavigationQueriesTest {
    @Autowired LifecycleQueries queries;
    @Autowired LifecycleService lifecycle;
    @MockitoSpyBean JdbcTemplate jdbc;

    /** Nombre unico que aisla el conjunto del caso sin borrar tablas o datos de otras pruebas. */
    String prefix(){return "nav-"+UUID.randomUUID();}
    /** Registra un sistema real con un nombre de prueba aislado. */
    LifecycleViews.SystemView system(String name){return lifecycle.createSystem(new CreateSystem(name,"software",null,null));}
    /** Crea un ciclo inactivo mediante el servicio ya validado del dominio. */
    LifecycleViews.CycleView cycle(UUID system){return lifecycle.createCycle(system,new CreateCycle(null),"navigation-author");}
    /** Fija claves de orden empatadas en un fixture de lectura sin relaciones dependientes. */
    UUID tiedSystem(String name,UUID id){
        var date=Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO system_objective(system_id,name,status,created_at,updated_at) VALUES (?,?,'ACTIVE',?,?)",id,name,date,date);
        return id;
    }
    /** Extrae solo identificadores para comprobar orden y ausencia de duplicados. */
    List<UUID> ids(LifecycleNavigationViews.SystemPage page){return page.items().stream().map(LifecycleNavigationViews.SystemSummary::systemId).toList();}

    /** Ausencia de ciclos no se interpreta como un ciclo activo ni una fecha de actividad inventada. */
    @Test @DisplayName("Sistema sin ciclos y busqueda vacia devuelven resumen y paginas coherentes")
    void supportsEmptySystems(){
        String name=prefix();var system=system(name);
        var found=queries.systems(50,null,name,null);assertEquals(1,found.items().size());var summary=found.items().getFirst();
        assertEquals(system.systemId(),summary.systemId());assertEquals(0,summary.cycleCount());assertTrue(summary.cyclesByState().isEmpty());
        assertNull(summary.lastActivityAt());assertNull(summary.recentCycle());assertNull(found.nextCursor());
        assertTrue(queries.cycles(system.systemId(),50,null,null).items().isEmpty());
        assertTrue(queries.systems(50,null,prefix(),null).items().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->found.items().clear());
        assertThrows(UnsupportedOperationException.class,()->summary.cyclesByState().put("E00_INACTIVO",1L));
    }

    /** Varios eventos por ciclo no multiplican conteos; la actividad no se toma del sistema padre. */
    @Test @DisplayName("Conteos por estado y ultimo ciclo reflejan actividad real sin inflacion por eventos")
    void summarizesCycleActivity(){
        String name=prefix();var system=system(name);var first=cycle(system.systemId());cycle(system.systemId());
        var started=lifecycle.startCycle(first.cycleId(),new StartCycle(0L,"nueva actividad"),"navigation-author");
        var summary=queries.systems(50,null,name,null).items().getFirst();
        assertEquals(2,summary.cycleCount());assertEquals(Map.of("E00_INACTIVO",1L,"E01_INICIALIZANDO",1L),summary.cyclesByState());
        assertEquals(first.cycleId(),summary.recentCycle().cycleId());assertEquals(started.version(),summary.recentCycle().version());
        assertEquals(started.updatedAt(),summary.lastActivityAt());assertEquals(system.updatedAt(),lifecycle.getSystem(system.systemId()).updatedAt());
    }

    /** Un evento recibido mas tarde debe contar aunque updated_at del ciclo sea anterior. */
    @Test @DisplayName("La actividad incluye recepcion de eventos historicos y desempata por numero de ciclo")
    void includesRecordedEvents(){
        String name=prefix();var parent=system(name);var first=cycle(parent.systemId());var second=cycle(parent.systemId());
        var created=Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"));var updated=Timestamp.from(Instant.parse("2024-01-02T00:00:00Z"));
        jdbc.update("UPDATE cycle SET created_at=?,updated_at=? WHERE system_id=?",created,updated,parent.systemId());
        jdbc.update("UPDATE cycle_event SET occurred_at=?,created_at=?,recorded_at=? WHERE cycle_id IN (?,?)",created,created,updated,first.cycleId(),second.cycleId());
        assertEquals(second.cycleId(),queries.systems(50,null,name,null).items().getFirst().recentCycle().cycleId());
        var received=Timestamp.from(Instant.parse("2024-01-03T00:00:00Z"));
        jdbc.update("UPDATE cycle_event SET recorded_at=? WHERE cycle_id=?",received,first.cycleId());
        var summary=queries.systems(50,null,name,null).items().getFirst();
        assertEquals(first.cycleId(),summary.recentCycle().cycleId());assertEquals(received.toInstant(),summary.lastActivityAt());
    }

    /** Porcentajes, guiones bajos y escape son texto; el estado administrativo usa igualdad parametrizada. */
    @Test @DisplayName("Busqueda literal de nombre, mayusculas y estado no convierten filtros en SQL")
    void treatsFiltersAsLiteralValues(){
        String name=prefix();var literal=system(name+"-100%_!match");system(name+"-100XX!match");
        var found=queries.systems(50,null,(name+"-100%_!").toUpperCase(Locale.ROOT)," ACTIVE ");
        assertEquals(List.of(literal.systemId()),ids(found));
        jdbc.update("UPDATE system_objective SET status='INACTIVE' WHERE system_id=?",literal.systemId());
        assertEquals(1,queries.systems(50,null,name,"INACTIVE").items().size());
        assertEquals(1,queries.systems(50,null,name,"ACTIVE").items().size());
        assertTrue(queries.systems(50,null,name+"' OR 1=1 --",null).items().isEmpty());
    }

    /** El UUID desempata fechas iguales y limit+1 no elimina el ultimo elemento real. */
    @Test @DisplayName("Paginacion completa con fechas empatadas conserva orden y todos los sistemas")
    void pagesTiedSystems(){
        String name=prefix();var low=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var middle=UUID.fromString("80000000-0000-0000-0000-000000000001");var high=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        tiedSystem(name+"-1",low);tiedSystem(name+"-2",middle);tiedSystem(name+"-3",high);
        var first=queries.systems(1,null,name,null);assertEquals(List.of(high),ids(first));assertNotNull(first.nextCursor());
        var second=queries.systems(1,first.nextCursor(),name,null);assertEquals(List.of(middle),ids(second));assertNotNull(second.nextCursor());
        var third=queries.systems(5,second.nextCursor(),name,null);assertEquals(List.of(low),ids(third));assertNull(third.nextCursor());
    }

    /** Los cursores mantienen contexto y ancla; no constituyen autorizacion ni aceptan una posicion inexistente. */
    @Test @DisplayName("Cursores malformados, ajenos y manipulados reciben INVALID_CURSOR")
    void rejectsInvalidSystemCursors(){
        String name=prefix();var first=system(name+"-1");system(name+"-2");
        var cursor=queries.systems(1,null,name,null).nextCursor();assertNotNull(cursor);
        assertEquals("INVALID_CURSOR",assertThrows(LifecycleException.class,()->queries.systems(1,cursor,name,"ACTIVE")).code());
        assertEquals("INVALID_CURSOR",assertThrows(LifecycleException.class,()->queries.systems(1,cursor,prefix(),null)).code());
        for(String invalid:List.of("","broken","x".repeat(513)))
            assertEquals("INVALID_CURSOR",assertThrows(LifecycleException.class,()->queries.systems(1,invalid,name,null)).code());
        var fake=NavigationCursor.encode(NavigationCursor.context("systems",name,null),first.createdAt(),UUID.randomUUID());
        assertEquals("INVALID_CURSOR",assertThrows(LifecycleException.class,()->queries.systems(1,fake,name,null)).code());
        var altered=NavigationCursor.encode(NavigationCursor.context("systems",name,null),first.createdAt().plusNanos(1),first.systemId());
        assertEquals("INVALID_CURSOR",assertThrows(LifecycleException.class,()->queries.systems(1,altered,name,null)).code());
    }

    /** El cursor por creacion no se mueve cuando cambia actividad o aparece otro sistema mas reciente. */
    @Test @DisplayName("Insercion en otra transaccion entre paginas no duplica registros anteriores")
    void handlesInsertionBetweenPages() throws Exception {
        String name=prefix();var a=system(name+"-a");var b=system(name+"-b");var first=queries.systems(1,null,name,null);
        UUID inserted;
        try(var executor=Executors.newSingleThreadExecutor()) {inserted=executor.submit(()->system(name+"-new").systemId()).get();}
        cycle(first.items().getFirst().systemId());
        var remainder=queries.systems(50,first.nextCursor(),name,null);
        var all=new HashSet<>(ids(first));all.addAll(ids(remainder));assertEquals(Set.of(a.systemId(),b.systemId()),all);
        assertFalse(ids(remainder).contains(inserted));assertEquals(inserted,queries.systems(1,null,name,null).items().getFirst().systemId());
    }

    /** Los ciclos pertenecen al sistema consultado y conservan detalle y version bajo filtros F01. */
    @Test @DisplayName("Listado de ciclos pagina, filtra estado y rechaza cursores de otro sistema o filtro")
    void pagesAndFiltersCycles(){
        var parent=system(prefix());var first=cycle(parent.systemId());var second=cycle(parent.systemId());
        var started=lifecycle.startCycle(first.cycleId(),new StartCycle(0L,"inicio"),"navigation-author");
        var page=queries.cycles(parent.systemId(),1,null,null);assertEquals(second, page.items().getFirst());assertNotNull(page.nextCursor());
        var last=queries.cycles(parent.systemId(),1,page.nextCursor(),null);assertEquals(started,last.items().getFirst());assertNull(last.nextCursor());
        assertEquals(List.of(started),queries.cycles(parent.systemId(),50,null,"E01_INICIALIZANDO").items());
        assertTrue(queries.cycles(parent.systemId(),50,null,"E15_ABORTADO").items().isEmpty());
        var other=system(prefix());cycle(other.systemId());
        assertEquals("INVALID_CURSOR",assertThrows(LifecycleException.class,()->queries.cycles(other.systemId(),1,page.nextCursor(),null)).code());
        assertEquals("INVALID_CURSOR",assertThrows(LifecycleException.class,()->queries.cycles(parent.systemId(),1,page.nextCursor(),"E00_INACTIVO")).code());
        assertEquals("SYSTEM_NOT_FOUND",assertThrows(LifecycleException.class,()->queries.cycles(UUID.randomUUID(),50,null,null)).code());
    }

    /** Limites y filtros invalidos fallan antes de ejecutar consultas potencialmente no acotadas. */
    @Test @DisplayName("Limites, textos de control y estados F01 invalidos se rechazan explicitamente")
    void validatesBounds(){
        var parent=system(prefix());
        for(int limit:List.of(0,-1,101,Integer.MAX_VALUE)){
            assertEquals("INVALID_LIMIT",assertThrows(LifecycleException.class,()->queries.systems(limit,null,null,null)).code());
            assertEquals("INVALID_LIMIT",assertThrows(LifecycleException.class,()->queries.cycles(parent.systemId(),limit,null,null)).code());
        }
        assertEquals("INVALID_FILTER",assertThrows(LifecycleException.class,()->queries.systems(50,null,"x".repeat(256),null)).code());
        assertEquals("INVALID_FILTER",assertThrows(LifecycleException.class,()->queries.systems(50,null,null,"x".repeat(51))).code());
        assertEquals("INVALID_FILTER",assertThrows(LifecycleException.class,()->queries.systems(50,null,"\u0000",null)).code());
        assertEquals("INVALID_STATE",assertThrows(LifecycleException.class,()->queries.cycles(parent.systemId(),50,null,"unknown")).code());
    }

    /** Los listados no escriben eventos, fechas o versiones; no hay autorizaciones implicitas por leer. */
    @Test @DisplayName("Consultar repetidamente no modifica auditoria, version o fechas")
    void preservesReadOnlyState(){
        String name=prefix();var parent=system(name);var cycle=cycle(parent.systemId());
        var count=jdbc.queryForObject("SELECT count(*) FROM cycle_event WHERE cycle_id=?",Long.class,cycle.cycleId());
        for(int i=0;i<3;i++){queries.systems(50,null,name,null);queries.cycles(parent.systemId(),50,null,null);}
        assertEquals(parent,lifecycle.getSystem(parent.systemId()));assertEquals(cycle,lifecycle.getCycle(cycle.cycleId()));
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM cycle_event WHERE cycle_id=?",Long.class,cycle.cycleId()));
    }

    /** Detecta crecimiento de consultas por elemento, sin depender del SQL concreto de las agregaciones. */
    @Test @DisplayName("Treinta sistemas usan el mismo numero de consultas que una fila: sin N+1")
    void boundsQueryCount(){
        String name=prefix();for(int i=0;i<30;i++){var s=system(name+"-"+i);cycle(s.systemId());}
        clearInvocations(jdbc);queries.systems(1,null,name,null);long small=selectCalls();
        clearInvocations(jdbc);var page=queries.systems(30,null,name,null);long large=selectCalls();
        assertEquals(30,page.items().size());assertTrue(small>0,"La sonda debe observar consultas reales");assertEquals(small,large);assertTrue(large<=4);
    }

    /** Cuenta las llamadas publicas parametrizadas, excluyendo delegaciones internas de JdbcTemplate. */
    long selectCalls(){
        return mockingDetails(jdbc).getInvocations().stream().filter(call->{
            var method=call.getMethod();var types=method.getParameterTypes();
            return method.getName().equals("query")&&types.length==3&&types[0]==String.class&&types[1]==RowMapper.class;
        }).count();
    }
}
