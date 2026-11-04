# MVP-05 — mediciones posteriores, contrato POST-MEASURE-01

Interpretación operativa vigente:
[calidad de mediciones humanas y límites de causalidad](LOCAL-measurement-quality.md).
Una revisión aceptada conserva HUMAN_REPORT/UNVERIFIED; no acredita suficiencia
instrumental. Los apartados siguientes mantienen el alcance histórico del incremento.

## 05.1 Contrato y alcance

Secuencia: 05.1 contrato; 05.2 V015 y conservación histórica; 05.3 registro
transaccional; 05.4 coherencia de ejecución/indicadores; 05.5 revisión humana y
conjunto cerrado; 05.6 HTTP/OpenAPI; 05.7 elegibilidad E08; 05.8 regresión y reinicio.
Cada paso exige validación antes de avanzar. Esta entrega no implementa evaluación
productiva, F02, decisiones de éxito, cierre de ciclo ni portabilidad PostgreSQL.

Una medición posterior pertenece al mismo ciclo, plan y run finalizado hacia E08.
El indicador debe pertenecer al objetivo aprobado, conservar unidad exacta y
criterio estructurado. Se conservan valor decimal sin conversión, criterio/versión,
método, origen, fecha observada, recepción, autor y evidencia UNVERIFIED. La fecha
observada debe estar entre finalización técnica y recepción, inclusivamente;
se rechazan fracciones inferiores a microsegundo para evitar redondeos ambiguos.
Se consulta el reloj de base después de adquirir bloqueos.

La ejecución debe seguir APPLIED sin requiresReview. Registrar, revisar o cerrar
un conjunto no ejecuta acciones ni mueve E08 a E09. Registro/evidencia/auditoría y
versión de ciclo son atómicos; toda mutación exige expectedVersion del ciclo.
Orden de bloqueo: sistema → ciclo → plan → run. Las escrituras IR-009 usan el mismo
plan, por lo que no se intercalan contradicciones durante la validación local.
Una contradicción posterior sigue siendo posible: el futuro evaluador deberá
revalidar el contexto técnico además de usar las entradas inmutables.

AUTHOR registra resultados. APPROVER distinto del autor de la medición puede
revisar una evidencia de línea base o posterior del run. La revisión append-only
conserva identidad, método revisado, referencia del procedimiento, fecha,
limitaciones y decisión ACCEPTED_WITH_LIMITATIONS o REJECTED. No cambia la evidencia
UNVERIFIED original ni equivale a verificación automática de un instrumento,
autenticación de un sensor, suficiencia EV-2/EV-3 o causalidad. Una revisión es única
por run/evidencia; una corrección requiere un nuevo reporte, no sobrescribir datos.

APPROVER cierra un conjunto EXPLICIT_PAIR_V1: exactamente una línea base y una
medición posterior por indicador del objetivo del plan. Declara ventanas temporales
inclusivas y método común. Se rechazan duplicados, unidades/criterios incompatibles,
fechas fuera de ventana y métodos contradictorios. La ventana base termina como
máximo al crear el run; la posterior empieza como mínimo al finalizarlo. No se
promedia ni se escoge automáticamente el resultado favorable. Es comparación de
pares explícitos, no inferencia estadística sobre una población.

El conjunto conserva IDs de mediciones, revisiones presentes (o su ausencia),
ventanas, política, autor y fecha. Puede cerrarse con evidencia no revisada o
rechazada: esa insuficiencia debe conservarse para el evaluador futuro. Después
del cierre no se agregan mediciones ni revisiones a ese run. Dos cierres o un
registro concurrente con el cierre se serializan por versión: no cambian
silenciosamente las entradas de una futura evaluación. Cerrar un conjunto no
declara suficiente su evidencia ni acredita el cumplimiento del objetivo.

V015 es aditiva: tablas separadas de mediciones posteriores, revisiones y
conjuntos/pares. No se modifican valores de baseline ni se fabrican resultados históricos.
La inmutabilidad se garantiza por los comandos de aplicación (INSERT/lectura),
no frente a un administrador SQL; privilegios SQL de producción siguen pendientes.

## Matriz de aceptación prevista antes de escribir pruebas

| Paso | Escenarios |
|---|---|
| 05.1 | Contrato consistente con E08/F01, roles y significado de evidencia |
| 05.2 | V014 → V015 conserva datos; FKs y unicidad; base nueva |
| 05.3 | Decimales, procedencia y auditoría; rollback real del INSERT de evento |
| 05.4 | Ciclo/run/objetivo/indicador/unidad/fechas ajenos o incompatibles rechazados |
| 05.5 | Separación de identidades, revisión única, conjunto explícito y estable |
| 05.6 | Basic, roles, atributos desconocidos, OpenAPI y errores saneados |
| 05.7 | E07/E13/legacy/contradicciones bloqueantes; concurrencia y versiones |
| 05.8 | Regresión, JAR, reinicio y conservación de datos/revisiones/conjunto |

## Implementación y persistencia

`MeasurementContracts.PostMeasure` compone el contrato `Measure` existente;
`MeasurementQueries.indicator/requireUnit` se comparten con línea base. Los
resultados tienen su consulta `PostContext`, sin cambiar el significado ni el JSON
de `Context.baselines`. `MeasurementApplicationService` mantiene la fachada
autenticada; `PostMeasurementWorkflow` coordina comandos transaccionales y
`PlanExecutionStore.view` aporta los hechos técnicos IR-009. No se modifica el
contrato de `ResultEvaluator` ni se introduce un segundo modelo de indicadores.

V015 crea `post_measurement`, `measurement_review`, `measurement_set` y
`measurement_pair`, con FKs de contexto de run/plan/ciclo y de pares por indicador.
Añade únicamente claves candidatas a tablas existentes. Cada resultado copia
operador, umbral y versión del criterio; el cierre comprueba que siguen siendo
compatibles. Una revisión referencia evidencia del mismo ciclo y run y no
sobrescribe sus columnas. El conjunto fija IDs de revisiones, incluida su ausencia.
El control de contexto/autoría/método se aplica además en servicio bajo locks.

La columna `measurement_method` de indicadores históricos puede estar vacía:
se exige método en el resultado y su declaración explícita en la revisión de
línea base. Sin esa revisión no se afirma comparabilidad metodológica probada.
Una selección cerrada con revisión ausente/rechazada sigue siendo insuficiente;
el futuro evaluador deberá respetarlo. Cerrar es irreversible por esta API:
correcciones o nuevas rondas requerirán un contrato de conjuntos versionados,
sin editar el conjunto ya usado por una evaluación.

## 05.6 API y ejemplo de laboratorio

Raíz: `/api/cycles/{cycleId}/plans/{planId}/measurements`.
Swagger autenticado: `/swagger-ui/index.html`.

| Operación | Rol | Resultado |
|---|---|---|
| GET raíz | AUTHOR/APPROVER | Contexto, cycleVersion, mediciones, revisiones y set opcional |
| POST raíz | AUTHOR | Registra valor/evidencia/auditoría y conserva E08 |
| POST `/reviews` | APPROVER distinto del autor | Atestación humana única; evidencia cruda intacta |
| POST `/seal` | APPROVER | Conjunto EXPLICIT_PAIR_V1 fijo, sin evaluación |

Todas las respuestas correctas son 200 con el contexto actualizado. Errores:
400 entrada/unidad/método/fecha/referencia; 401 sin Basic; 403 rol/autorevisión;
404 recurso inexistente; 409 versión, etapa, contradicción, revisión existente o
conjunto cerrado; 500 persistencia fallida con respuesta saneada.

Primero completar [ejecución y finalización](MVP-03-finalization-recovery.md)
sobre un ciclo con indicadores numéricos y línea base. Consultar GET raíz y GET
`/api/cycles/{cycleId}/measurement` antes de construir cada comando. Usar versiones
devueltas por el servidor, no las ilustrativas de estos ejemplos.

Registro por AUTHOR:

```json
{
  "runId":"UUID del run finalizado",
  "method":"procedimiento p95 v1",
  "measurement":{
    "expectedVersion":20,
    "indicatorId":"UUID del indicador del objetivo del plan",
    "value":90.12345,
    "unit":"ms",
    "source":"informe de laboratorio",
    "measuredAt":"2026-11-03T18:00:00.123456Z",
    "reason":"Medición posterior al cambio"
  }
}
```

Revisión por APPROVER distinto (repetir explícitamente para la evidencia de la
línea base y la posterior que se usarán; los evidenceId se consultan en sus APIs):

```json
{
  "expectedVersion":21,
  "runId":"UUID del run",
  "evidenceId":"UUID de la evidencia de medición",
  "verdict":"ACCEPTED_WITH_LIMITATIONS",
  "method":"procedimiento p95 v1",
  "procedureReference":"acta de contraste 001",
  "limitations":"Revisión humana de una muestra; no demuestra causalidad",
  "reason":"Conservar revisión y limitaciones"
}
```

Cierre por APPROVER después de revisar muestras, ventanas y limitaciones:

```json
{
  "expectedVersion":23,
  "runId":"UUID del run",
  "baselineFrom":"2026-11-03T17:00:00Z",
  "baselineTo":"2026-11-03T17:10:00Z",
  "postFrom":"2026-11-03T18:00:00Z",
  "postTo":"2026-11-03T18:10:00Z",
  "method":"procedimiento p95 v1",
  "pairs":[{"indicatorId":"UUID","baselineId":"UUID","measurementId":"UUID"}],
  "reason":"Fijar entradas reproducibles para evaluación futura"
}
```

Las fechas de ejemplo deben reemplazarse por ventanas reales anteriores a recepción
y compatibles con el run. No se reutiliza una línea base como resultado posterior.
Ante timeout consultar el contexto/auditoría y versiones antes de registrar de
nuevo; no existe idempotencia de creación por una clave del cliente en este contrato.

## Trazabilidad y validación reproducible

| Invariante / requisito | Componente | Prueba |
|---|---|---|
| Historia sin resultados fabricados | V015 | PostMeasurementMigrationTest |
| Hecho/evidencia/evento atómicos | PostMeasurementWorkflow.record | PostMeasurementWorkflowTest |
| Objetivo, unidades y fechas del run | MeasurementQueries + eligible/indicator | PostMeasurementWorkflowTest |
| Revisión separada, actor y limitaciones | PostMeasurementWorkflow.review | PostMeasurementReviewTest |
| Entradas estables sin selección implícita | PostMeasurementWorkflow.seal | PostMeasurementReviewTest |
| E08 y hechos técnicos vigentes | eligible + PlanExecutionStore | PostMeasurementEligibilityTest |
| Serialización registro/cierre | versión ciclo + locks | PostMeasurementEligibilityTest |
| Identidad y contrato HTTP/OpenAPI | Fachada + controlador + seguridad | PostMeasurementApiTest |
| Persistencia fuera de JVM | JAR y fixture HTTP JDBC | MvpLifecycleRestartIT |

Las clases nuevas de prueba y cada método de test tienen Javadoc y @DisplayName.
Las cinco pruebas del JAR también se identifican con @DisplayName. La demostración
automática crea su sistema, objetivo, línea base, plan y ejecución mediante HTTP;
registra y revisa resultados, cierra conjunto y reabre el mismo archivo desde otra
JVM. Solo utiliza procesos/bases propios y no modifica la base del usuario.

```powershell
.\mvnw.cmd '-Dtest=PostMeasurementMigrationTest,PostMeasurementWorkflowTest,PostMeasurementReviewTest,PostMeasurementApiTest,PostMeasurementEligibilityTest' test
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

Prerrequisito: SDK hermano instalado según README. No se requieren PostgreSQL ni
PetClinic para estas pruebas. H2 no acredita portabilidad o integridad ante cortes
de energía; revisión humana no acredita verificación instrumental. Ver también
la [matriz transversal](MVP-v01-traceability.md).

## 05.8 Resultado de validación

Validado el 2026-11-03. Los pasos 05.1–05.8 quedan completados en el alcance
POST-MEASURE-01 definido arriba.

| Puerta | Resultado |
|---|---|
| 05.1 Contrato | Reglas, invariantes, roles, ventanas y matriz definidos antes del código |
| 05.2 Migración | 1 prueba V014 → V015, datos históricos intactos y sin resultados ficticios |
| 05.3 Registro y compatibilidad | 11 pruebas satisfactorias, incluidas las existentes de línea base |
| 05.4 Integridad | 5 pruebas de registro, relaciones, unidades, objetivo y precisión |
| 05.5 Revisión/conjunto | 10 pruebas de registro/revisión/cierre, con rollback de auditoría |
| 05.6 HTTP/OpenAPI | 3 pruebas HTTP satisfactorias |
| 05.7 E08/concurrencia | 5 pruebas satisfactorias, con versiones y contradicciones tardías |
| Regresión clean verify | 842 pruebas, cero fallos, errores u omisiones; JAR construido |
| JAR entre JVM | 5 pruebas satisfactorias, incluye resultado/revisiones/conjunto persistentes y caída tras commit remoto |
| Artefacto y documentación | Servicio/controlador/V015 presentes; sin SDK, PetClinic ni fixtures en JAR; enlaces válidos |

Los conteos enfocados se superponen y no se suman. Se agregaron 19 métodos de
prueba a la regresión anterior de 823. Las cinco pruebas del JAR se ejecutan
aparte después de construirlo; no se omiten ni se presentan como parte de verify.

La primera regresión detectó un fixture previo que vencía un permiso exactamente
en CURRENT_TIMESTAMP. Se corrigió exclusivamente el test para situar su vencimiento
un segundo antes del reloj de vigencia, sin cambiar la autorización productiva.
Sus 7 pruebas enfocadas y la posterior regresión completa resultaron satisfactorias.

Logs locales ignorados por Git: `review-mvp05-step2.log` a
`review-mvp05-step7.log`, `review-mvp05-clock-fixture.log`,
`review-mvp05-regression.log` y `review-mvp05-restart.log`.
Se utilizaron bases/procesos propios y no se ejecutaron cambios contra la base
del usuario. La publicación y ejecución remota de Actions requieren subir el commit;
no se realizó commit ni push desde esta entrega.

Siguiente incremento P0: evaluador productivo determinista sobre el conjunto fijo,
con política versionada, comparabilidad y suficiencia de evidencia explícitas.
Después corresponde decisión/aprendizaje/cierre mediante F02/F01. La revisión
humana incorporada aquí no cierra la deuda de autenticación y verificación de
fuentes instrumentales ni la de integridad frente a acceso SQL directo.
