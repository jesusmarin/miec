# MVP-09 — Consulta y navegación inicial

Contrato: **MVP09-NAVIGATION-01**. Alcance: catálogo de sistemas con actividad y
colección de ciclos de cada sistema, sin cambiar los comandos o las vistas de detalle.

## Rutas y permisos

| Ruta | Filtros | Resultado |
|---|---|---|
| `GET /api/systems` | `q`, `status`, `limit`, `after` | `SystemPage` con resúmenes de sistemas |
| `GET /api/systems/{systemId}/cycles` | `state`, `limit`, `after` | `CyclePage` con el contrato `CycleView` existente |

AUTHOR y APPROVER pueden consultar. Basic es obligatorio: sin identidad válida se
devuelve 401; un rol ajeno no adquiere permiso lector. La fachada verifica identidad
también en llamadas internas. APPROVER conserva su restricción de crear/iniciar.
El catálogo es global dentro del MVP local; `ownerReference` sigue siendo metadato,
no un filtro de autorización por propietario ni una implementación multitenant.

`q` busca una subcadena literal del **nombre** sin distinguir mayúsculas. Se
normalizan sus espacios externos y mayúsculas para mantener el contexto del cursor.
`%`, `_` y `!` se tratan como caracteres, no como comodines. Máximo 255 caracteres.
`status` compara exactamente el estado administrativo del sistema, por ejemplo
`ACTIVE`; admite hasta 50 caracteres y no se confunde con F01. Los valores desconocidos
pueden producir una página vacía: el modelo de sistema no define un catálogo cerrado.

`state` admite los códigos oficiales de `CycleState`, incluido E15_ABORTADO, con
mayúsculas exactas. Un estado desconocido produce `INVALID_STATE`. Los espacios
externos se normalizan; filtros vacíos se interpretan como ausentes. Caracteres
de control o longitudes excesivas producen `INVALID_FILTER`.

## Resumen para la pantalla inicial

```json
{
  "items": [{
    "systemId": "UUID",
    "name": "Proyecto local",
    "domain": "software",
    "status": "ACTIVE",
    "createdAt": "2026-11-04T00:00:00Z",
    "cycleCount": 2,
    "cyclesByState": {"E00_INACTIVO": 1, "E14_FINALIZADO": 1},
    "lastActivityAt": "2026-11-04T12:00:00Z",
    "recentCycle": {
      "cycleId": "UUID",
      "cycleNumber": 1,
      "state": "E14_FINALIZADO",
      "version": 47,
      "lastActivityAt": "2026-11-04T12:00:00Z"
    }
  }],
  "nextCursor": null
}
```

El ejemplo muestra la forma del contrato; las horas e IDs no representan una
medición nueva. Los estados ausentes del mapa significan cero. Se incluyen todos
los ciclos del sistema, también inactivos, suspendidos y terminales; un ciclo
abierto no se etiqueta automáticamente como una ejecución activa.

La actividad de cada ciclo es el máximo de su fecha de actualización y la última
recepción `cycle_event.recorded_at`, con creación como respaldo para históricos.
`recentCycle` es el ciclo con actividad más reciente; los empates usan número e ID
descendentes. No significa necesariamente el último creado ni uno en ejecución.
La actividad no procede de `system.updatedAt` y no incluye cada heartbeat interno.
Sin ciclos: conteo cero, mapa vacío, `lastActivityAt` y `recentCycle` nulos.

Las páginas no exponen entidades JPA, credenciales, propietarios, cuerpos de acciones
o grafos de planes. Las consultas de detalle existentes conservan su respuesta.

## Paginación y concurrencia

El orden de ambas colecciones es `created_at DESC, id DESC`. El límite predeterminado
es 50 y se admite 1..100. Se solicita una fila adicional para decidir si existe una
página siguiente, sin descartar elementos de la última página.

`nextCursor` es opaco; enviarlo como `after` conservando los filtros y el sistema.
Se permite cambiar `limit`. El token contiene una versión, contexto de filtros y
ancla de creación/UUID. Se valida que el ancla persistida pertenezca al ámbito y
conserve su fecha. No es una credencial ni sustituye el control de permisos.

Los cursores no ordenan por actividad, porque esa fecha cambia mientras se navega.
Una actualización del estado o nombre del ancla no invalida por sí sola su posición
inmutable; el filtro solicitado sigue aplicándose a todos los resultados.

Cada página se calcula en una transacción de solo lectura `REPEATABLE_READ`.
No se mantiene una transacción ni un snapshot entre peticiones. Una inserción más
reciente puede aparecer al refrescar la primera página; no se promete que recorrer
páginas mientras cambian filtros de los registros produzca un censo histórico fijo.

```text
GET /api/systems?q=petclinic&status=ACTIVE&limit=20
GET /api/systems?q=petclinic&status=ACTIVE&limit=20&after=NEXT_CURSOR
GET /api/systems/SYSTEM_ID/cycles?state=E13_RECUPERANDO&limit=20
```

Un sistema existente sin ciclos devuelve 200 y página vacía; uno inexistente,
404 `SYSTEM_NOT_FOUND`. Cursores malformados, manipulados, de otro sistema o de
otros filtros devuelven 400 `INVALID_CURSOR`. Límite inválido: 400 `INVALID_LIMIT`.
UUID o tipos ilegibles: 400 `INVALID_REQUEST`. Los errores no incluyen SQL o trazas.

## Implementación y persistencia

`LifecycleController` delega en `LifecycleApplicationService`, que comprueba
`InterventionIdentity.reader()` y llama a `LifecycleQueries`. Las nuevas vistas
están en `LifecycleNavigationViews`; el codec interno está en `NavigationCursor`.

La página de sistemas utiliza hasta tres consultas, o cuatro con validación de
cursor: página, conteos por estado y actividad reciente. Las agregaciones se
restringen a los IDs de la página. No hay consultas por cada sistema ni conteos
inflados al unir un ciclo con varios eventos. Una página vacía omite agregaciones.

La página de ciclos usa dos consultas, o tres con cursor: existencia del sistema,
ancla opcional y página. Todas usan parámetros preparados y columnas/órdenes fijos
del servidor. No se admiten fragmentos SQL ni ordenamiento arbitrario del cliente.

No se introduce migración V022 ni se modifican migraciones publicadas. Se reutilizan
las tablas y los índices existentes de ciclos por sistema y eventos por ciclo.
El control de número de consultas no constituye una prueba de carga de gran volumen;
índices adicionales deben justificarse con planes y carga representativa.

Este incremento no añade una pantalla ni `GET /api/cycles` transversal. Los dos
listados permiten construir la pantalla inicial y abrir ciclos al seleccionar un
sistema. Un tablero operativo transversal puede definirse como incremento posterior.

## Validación reproducible

```powershell
# Identidad, consultas H2 y seis escenarios HTTP/OpenAPI.
.\mvnw.cmd '-Dtest=LifecycleApplicationServiceTest,LifecycleNavigationQueriesTest,LifecycleNavigationApiTest' test

# Los mismos once escenarios de consulta sobre una base PostgreSQL temporal propia.
.\mvnw.cmd '-Dtest=LifecycleNavigationPostgresIT' '-Dmiec.postgres.test=true' test

# Regresión y JAR.
.\mvnw.cmd verify

# JAR PostgreSQL: detalles, listados, auditoría y reinicio en dos JVM.
.\mvnw.cmd '-Dtest=PostgresApplicationRestartIT' '-Dmiec.postgres.test=true' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

Las pruebas PostgreSQL son opt-in y crean/eliminan solo sus bases `miec_pg_*`.
No modifican las bases originales o restauradas del piloto MVP-08. Las pruebas
incluyen vacíos, varios eventos por ciclo, estados, actividad recibida tardíamente,
UUID empatados, controles literales, anclas ajenas o alteradas, inserciones en otra
transacción entre páginas, lectura sin escrituras y número de consultas constante.
Las clases y métodos nuevos incluyen Javadoc y los tests incluyen DisplayName.

Resultados locales del 2026-11-04, todos sin fallos, errores ni omisiones:

| Grupo | Casos | Resultado |
|---|---:|---|
| Regresión completa y empaquetado | 923 | Aprobados |
| Control final H2/PostgreSQL y JAR entre dos JVM | 23 | Aprobados: 11 H2, 11 PostgreSQL y 1 del JAR |
| HTTP y OpenAPI | 6 | Incluidos en la regresión |
| Fachada de identidad | 4 | Incluidos en la regresión |

La regresión terminó a las 14:29:15 y el control final a las 14:32:03, hora de
Bogotá. El control final repite escenarios H2 presentes en la regresión; los grupos
no se suman como escenarios distintos. La sonda N+1 también exige observar consultas
reales antes de comparar su cantidad para una y treinta filas.

Se inició además el JAR contra `miec-v01` con un lector temporal y se hicieron solo
GET sobre el sistema `77b5f8dd-2f7e-40ca-863a-af8d2a809d51` del piloto MVP-08.
El resumen mostró dos ciclos, E00=1 y E14=1, y actividad reciente del ciclo
`93121e19-cfd6-4882-82d1-4daca073ca47`, E14 y versión 47. El listado por creación
muestra primero el ciclo inactivo número 2; `recentCycle` señala correctamente
la actividad posterior del número 1. Las lecturas repetidas conservaron la respuesta.
La JVM propia se cerró mediante el MBean local de Spring.

Este control no vuelve a ejecutar ni modifica el expediente MVP-08: sus doce casos
siguen ligados a los hashes de sus artefactos originales. La aceptación del candidato
final de entrega deberá identificar su commit y su binario definitivos.

Registros locales excluidos de Git:
`review-mvp09-queries.log`, `review-mvp09-http-postgres.log`,
`review-mvp09-regression.log`, `review-mvp09-jar-postgres.log` y
`review-mvp09-live.log`. La respuesta del sistema del piloto está en
`target/mvp09-navigation-pilot.json`, artefacto local regenerable de comprobación.
