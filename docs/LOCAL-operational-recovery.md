# Recuperación operativa delimitada — MVP local v0.1.0

Fecha: 2026-11-04. Procedimiento para el operador, basado en las APIs y guardas
existentes. Documenta capacidades y bloqueos; no implementa continuación parcial,
renovación de permisos ni nuevas transiciones. Complementa la entrega `v0.1.0`
sin mover su etiqueta. Instalación, parada y respaldo:
[guía conjunta](LOCAL-installation-operations.md).

## 1. Responsabilidad y reglas de intervención

Un operador coordina el incidente; AUTHOR solicita o ejecuta controles permitidos,
y APPROVER revisa, aprueba y confirma. Son identidades distintas del servidor;
operarlas desde una sola persona no acredita separación humana independiente.
Mantener una sola intervención sobre un plan y registrar el relevo de responsable.
La concurrencia se controla también mediante versiones y propiedad en el servidor.

Ante un timeout, caída, 500 o resultado parcial:

1. Detener nuevos comandos de ejecución del operador y conservar el error recibido.
   Un timeout del cliente no cancela el trabajo del servidor.
2. Consultar plan, run y ciclo; conservar IDs, versiones, estados e incidentes.
   No comenzar por `recover` ni por reiniciar la aplicación.
3. Si hay coordinador vigente, esperar y volver a **leer**; no robar su propiedad.
   El reloj de PostgreSQL decide la vigencia, no el cronómetro del operador.
4. Clasificar con la matriz siguiente y elegir una sola intervención explícita.
5. Después de cada comando volver a consultar. No reintentar automáticamente un
   POST por timeout, 409 o 500; primero determinar qué quedó confirmado.

No borrar intentos, evidencias, autorizaciones, incidentes o ledger; no editar
versiones, leases, fechas, estados o claves por SQL. No sustituir el plan, el
destino o la operationKey para sortear un bloqueo. Una clave nueva puede producir
otro efecto real. Un respaldo anterior tampoco deshace un efecto del destinatario.

La parada de JVM es una medida operativa, **no** una transición de suspensión,
revocación, aborto ni compensación. Una revocación o pérdida de propiedad no
garantiza cancelar una llamada externa ya en vuelo. No restaurar bases para
simular que una ejecución incierta nunca ocurrió.

## 2. Qué consultar y qué significa

Lecturas autenticadas AUTHOR/APPROVER:

| Ruta | Información para clasificar |
|---|---|
| `GET /api/plans/{planId}` | Estado, contenido y versión del plan aprobado |
| `GET /api/plans/{planId}/context` | Contexto y diagnóstico de la propuesta |
| `GET /api/plans/{planId}/execution` | `runId`, `version`, `state`, `cycleState`, `generation`, `leaseExpiresAt`, pasos e incidentes |
| `GET /api/cycles/{cycleId}` | Versión y estado actuales del ciclo |
| `GET /api/cycles/{cycleId}/history` | Transiciones auditadas del ciclo |
| `GET /api/authorizations/{authorizationId}` | Alcance, estado registrado, vigencia, solicitante y decisión |
| `GET /api/execution-adapters` | Enlace instalado y huella pública; no sus secretos |

Un paso contiene `ordinal`, `actionId`, `authorizationId`, `operationKey`, `state`,
`executionId`, `resultReference` y `observedOutcome`. Examinar todos los pasos,
no solo el primero o el estado agregado del run.

| Dato | Interpretación y límite |
|---|---|
| Run `RUNNING` | Reserva/coordinación existente; puede tener propiedad vigente o vencida |
| Run `RECOVERY_REQUIRED` | Ejecución detenida para revisión; no es autorización para continuar |
| Run `APPLIED` | Proyección de efectos aplicada; por sí sola no acredita E08, mejora ni causalidad |
| Paso `READY` | Sin marca de despacho de ese paso; solo es candidato a resume si además `observedOutcome=NONE` y se cumplen todas las demás guardas |
| Paso `DISPATCHING` / `RECOVERY_REQUIRED` | Despacho reservado o interrumpido; incluso sin `executionId` no se supone ausencia de efecto |
| `observedOutcome=APPLIED` | Resultado efectivo confirmado según IR-009; para finalizar se exige referencia coherente y ausencia de contradicciones |
| `UNKNOWN` / `EXPIRED` | Resultado incierto o lease de intento vencido; vencer no demuestra fallo ni ausencia de efecto |
| `CONFLICT` / `UNCONFIRMED` | Hechos, identidad o referencia incompatibles/incompletos; bloquear intervención de envío |
| `NONE` | No se encontró el intento en la proyección; no es una evidencia general NO_EFFECT |
| `PENDING`, `CANCELLED_PENDING`, `NO_EFFECT` | No satisfacen la finalización APPLIED ni habilitan retry por las APIs del plan |
| `requiresReview` | Aviso de revisión por recuperación, propiedad vencida o hechos incompatibles; `false` no acredita que EXECUTE_ACTION siga vigente |

El estado original del intento es distinto de su resultado efectivo. Un UNKNOWN
puede conservarse históricamente aunque una reconciliación autorizada confirme
APPLIED. Tampoco deducir ausencia de efecto por el nombre FAILED de un intento:
la proyección considera su resultado y evidencia. La API no expone un editor de
intentos ni una ruta para declarar el resultado técnico manualmente.

Si execution responde 404, comprobar primero el plan y el ciclo. Si siguen en el
contexto previo al inicio sin coordinador ni historia de envío, el inicio normal
requiere todas sus precondiciones y una nueva decisión explícita del operador.
Si hay E07/E13, efecto remoto u otra inconsistencia sin run consultable, escalar;
el 404 aislado no autoriza un nuevo envío.

## 3. Matriz de decisión

| Situación observada | Acción disponible | Punto de parada |
|---|---|---|
| RUNNING con coordinador vigente | GET y espera supervisada | No resume/recover/reconcile/finish mientras el coordinador siga vigente |
| E07 + RUNNING vencido, todo lo no aplicado READY/NONE, resultados anteriores APPLIED íntegros y permisos originales vigentes | AUTHOR puede `resume`, tras revisión explícita | `resume` **puede enviar** los pendientes; no usarlo como diagnóstico |
| Todos los efectos confirmados APPLIED, proyección pendiente o desactualizada | APPROVER puede `recover` para importar hechos locales; después revisar `finish` | No se renuevan permisos ni se vuelve a enviar |
| UNKNOWN o IN_PROGRESS vencido con intento identificable, en E07/E13 | Permiso RECONCILE_ACTION y `reconcile` | Solo consulta remota confiable; no acepta un resultado declarado por el operador |
| Parcial APPLIED con acciones READY después de fallo y ciclo E13 | Recuperar/reconciliar hechos ya ocurridos, registrar el parcial | No existe continuación de pendientes desde E13 |
| DISPATCHING sin intento, o PENDING abandonado | Conservar incidente y escalar | Sin prueba suficiente no hay resume seguro ni reconciliación HTTP aplicable |
| Permiso EXECUTE_ACTION vencido/revocado y trabajo pendiente | Mantener el bloqueo; recuperar solo hechos ya confirmados | No hay renovación operativa ni sustitución del permiso del paso |
| NOT_FOUND, timeout, autenticación fallida o fuente no disponible en la consulta | Conservar incertidumbre; corregir infraestructura/configuración por canal confiable y reevaluar | No convertirlo en NO_EFFECT ni hacer POST al destinatario |
| Evidencia contradictoria, cambio de contenido/enlace o identidad | Detener y escalar con evidencia | No eliminar contradicciones ni cambiar el plan aprobado para forzar éxito |
| E08 o etapa posterior con contradicción nueva | Registrar y escalar; la consulta puede indicar requiresReview | Las rutas de recuperación E07/E13 no reabren ni revierten la historia |

**Advertencia funcional:** `recover` no es una lectura inocua. Si falta al menos
un efecto APPLIED, puede dejar el run RECOVERY_REQUIRED y mover E07 a
E13_RECUPERANDO, invalidando propiedad. Si el caso era una reserva RUNNING vencida
sin despacho incierto, evaluar primero si procede `resume`: después de entrar en
E13 esa continuación ya no existe. No probar comandos para averiguar cuál funciona.

## 4. Procedimientos permitidos

### A. Reserva vencida sin despacho incierto

AUTHOR revisa E07, RUNNING y lease vencido. Para cada pendiente se exige READY/NONE,
y para cada paso aplicado un resultado APPLIED coherente. El plan debe seguir
aprobado, abierto y con enlaces idénticos disponibles; los permisos EXECUTE_ACTION
originales deben seguir vigentes y coincidir con los IDs capturados en los pasos.

Solo entonces llamar `POST /api/plans/{planId}/execution/resume` con versión
del run recién leída y motivo. Se adquiere una generación nueva y se pueden
despachar los pendientes secuencialmente. No se repiten los pasos ya aplicados.
Ante rechazo por permiso, contexto o despacho incierto, detenerse y reclasificar.
No cambiar leases por SQL ni reducir su duración para provocar la adquisición.

### B. Hechos locales confirmados y progreso perdido

APPROVER revisa que no haya coordinador vigente y el ciclo esté en E07/E13.
`POST /api/plans/{planId}/execution/recover` importa únicamente los resultados
IR-009 ya persistidos; no hace GET externo, POST de efecto ni renovación.
Puede operar aunque EXECUTE_ACTION haya vencido.

Si todos los hechos son APPLIED, el run queda APPLIED y conserva E07 o E13 hasta
la revisión final. Si queda un pendiente, se conserva el parcial y se bloquea en
E13; el comando no crea un permiso para continuar ni convierte UNKNOWN en éxito.

### C. Resultado incierto con intento identificable

1. Leer run/ciclo y esperar vencimiento o fin de la propiedad aplicable. El intento
   original debe ser UNKNOWN o IN_PROGRESS sin lease vigente. Una marca de despacho
   sin intento no satisface esta ruta. Verificar enlace confiable sin cambiar su huella.
2. AUTHOR solicita `POST /api/plans/{planId}/execution/reconciliation-requests`.
   Proporcionar `actionId` del run, versión actual del run, motivo y
   `validForSeconds` de **30 a 3600**. Se crea una solicitud PENDING; no concede ejecución.
3. APPROVER distinto revisa y decide en
   `POST /api/execution/reconciliation-authorizations/{authorizationId}/approve`
   o `/reject`, con versión actual **del permiso** y motivo. Solicitud y decisión
   cambian las versiones del run/ciclo. El rechazo no prueba un resultado técnico.
4. El **mismo aprobador registrado** relee el run y llama
   `POST /api/plans/{planId}/execution/reconcile` con `actionId`, `authorizationId`,
   versión actual del run y motivo. El servidor consulta por GET al destinatario
   instalado y verifica clave, destino, huella y referencia; el cliente no aporta
   URL, credencial, evidencia ni resolución.
5. Releer el resultado y decidir si todos los efectos permiten finalizar.
   Con otros READY pendientes, se conserva E13 y no hay continuación de esos pasos.

El permiso se revalida después de la red y al persistir los hechos. Un vencimiento
durante la consulta puede impedir confirmar aunque el GET haya respondido APPLIED.
Una ausencia contractual NOT_FOUND no acredita NO_EFFECT: puede existir trabajo
concurrente o un efecto cuyo recibo no esté disponible.

La evidencia, la decisión IR-009 y la proyección del run se confirman en commits
separados. Después de una interrupción:

- Si ya existe decisión APPLIED durable, `recover` puede importar ese hecho sin
  otro envío ni renovación de EXECUTE_ACTION.
- Si solo quedó evidencia, no asumir una decisión: revisar y, si sigue siendo
  elegible, repetir explícitamente `reconcile` con versión actual y permiso vigente.
- Para el mismo intento/permiso se reutiliza el ID determinista de evidencia.
  Conservar el mismo motivo al repetir la misma revisión; decisiones existentes
  con contenido diferente no se sobrescriben. No confundir esto con retry del efecto.
- Si el permiso de reconciliación venció, seguir la sección de vigencia; si el
  contexto cambió o hay contradicción, conservar el bloqueo.

### D. Finalización técnica

APPROVER revisa todos los pasos, sus referencias y contradicciones. Con plan
aprobado, versiones actuales del run **y ciclo**, E07/E13 y sin coordinador
vigente, llamar `POST /api/plans/{planId}/execution/finish`. Puede importar hechos
APPLIED pendientes sin una llamada recover previa; no hay que añadir ese comando
si la información ya permite una finalización completa.

Todas las acciones deben tener resultado efectivo APPLIED. El commit incluye
E08_VERIFICANDO, evento, transición y versiones. El run permanece APPLIED;
no se inventa un estado FINALIZED. No exige renovar EXECUTE_ACTION porque no
envía nuevos efectos. Otra finalización sobre la etapa avanzada se rechaza.
E08 permite medir y evaluar; no declara éxito del objetivo ni compensa efectos.

## 5. Permisos vencidos: tres situaciones diferentes

**EXECUTE_ACTION:** debe seguir vigente al reservar y antes de enviar cada acción.
Heartbeat no lo renueva. El vencimiento entre acciones detiene lo pendiente y no
deshace lo aplicado. No hay endpoint de renovación/sustitución para el run.
La solicitud normal solo admite planes PROPOSED/AWAITING_AUTHORIZATION en E04/E05
y rechaza otra solicitud para la misma acción; por tanto, tampoco se debe prometer
que un permiso vencido antes del inicio se soluciona repitiendo la solicitud.
Registrar el bloqueo y escalar el diseño operativo requerido.

**RECONCILE_ACTION:** vigencia contada desde la solicitud, no desde su aprobación.
Si vence una solicitud o un permiso, AUTHOR puede crear **otra solicitud explícita**
si la acción/contexto siguen siendo elegibles y no existe otra solicitud/permiso
PENDING/APPROVED aún vigente. Requiere nueva decisión de un APPROVER distinto.
No prorroga el permiso previo ni concede EXECUTE_ACTION. No se puede aprobar una
solicitud vencida; consultar el estado registrado no amplía el plazo.

**Lease de propiedad:** es diferente de ambas autorizaciones. Su vencimiento no
prueba que el efecto no ocurrió ni que el proceso anterior esté físicamente muerto.
El núcleo controla propietario/generación/fencing, pero la aplicación local no
puede cancelar retroactivamente una llamada remota admitida. Mantener sincronizados
los relojes y no calcular elegibilidad modificando tiempos de los registros.

El estado almacenado puede seguir siendo APPROVED después del vencimiento:
la política comprueba también fechas, alcance y revocación con reloj de base.
Una etiqueta APPROVED o `requiresReview=false` no bastan para autorizar envío.

## 6. Cuerpos, roles y errores para operar

Usar Swagger autenticado en `/swagger-ui/index.html`. Consultar de nuevo cada
recurso antes de preparar un POST. Los siguientes valores **son ilustrativos**:
reemplazar UUID y versiones por los leídos, no copiarlos como constantes ni usar
un bucle que aumente versiones hasta obtener 200.

`resume` (AUTHOR) o `recover` (APPROVER), según la clasificación:

```json
{"expectedVersion":12,"reason":"INC-LOCAL-001: intervención revisada según el procedimiento aplicable"}
```

Solicitud de reconciliación (AUTHOR):

```json
{"expectedVersion":12,"actionId":"UUID-ACCION","validForSeconds":600,"reason":"INC-LOCAL-001: consultar efecto incierto"}
```

Aprobación/rechazo (APPROVER distinto; versión del permiso):

```json
{"expectedVersion":0,"reason":"INC-LOCAL-001: decisión sobre consulta acotada"}
```

Reconciliación (APPROVER registrado; versión actual del run):

```json
{"expectedVersion":14,"actionId":"UUID-ACCION","authorizationId":"UUID-PERMISO","reason":"INC-LOCAL-001: confirmar efecto observado"}
```

Finalización (APPROVER; versiones actuales de run y ciclo):

```json
{"expectedVersion":15,"expectedCycleVersion":22,"reason":"INC-LOCAL-001: todos los efectos confirmados y revisados"}
```

Motivos no vacíos, máximo 500 caracteres, sin secretos. El actor procede de Basic,
no de un atributo del cuerpo. La solicitud devuelve 201; los otros controles 200.
Un 200 de recover/reconcile puede conservar RECOVERY_REQUIRED: leer siempre el
cuerpo y el recurso posterior. Un 201 de inicio también puede devolver un parcial.

| Respuesta / código típico | Conducta |
|---|---|
| 400 / VALIDATION_ERROR, INVALID_REQUEST, ACTION_NOT_PLANNED | Corregir contrato o relación de IDs; no enviar atributos extra |
| 401 / 403 | Resolver identidad/rol; no cambiar credenciales para saltarse separación o ámbito |
| 404 / PLAN_EXECUTION_NOT_FOUND | Revisar contexto e incidente; no concluir ausencia de efecto remoto |
| 409 / RUN_VERSION_CONFLICT, VERSION_CONFLICT, AUTHORIZATION_VERSION_CONFLICT | Releer run/ciclo/permiso y revisar el cambio antes de un nuevo comando |
| 409 / COORDINATOR_BUSY | Esperar y consultar; no invalidar lease por SQL |
| 409 / EXECUTION_NOT_RESUMABLE, DISPATCH_UNCERTAIN | No continuar: clasificar el parcial o la incertidumbre |
| 409 / EXECUTION_AUTHORIZATION_INVALID, RECONCILIATION_AUTHORIZATION_INVALID, AUTHORIZATION_EXPIRED | Distinguir el alcance; aplicar límites de vigencia de la sección 5 |
| 409 / DESTINATION_NOT_CONFIRMED, ATTEMPT_NOT_RECONCILABLE, RESULT_UNCONFIRMED | Mantener incertidumbre/incompletitud y escalar si no hay ruta admisible |
| 500 / EXECUTION_AUDIT_UNAVAILABLE | Conservar `fields.incidentId`; puede ser solo correlación de log si no se confirmó el INSERT |
| Otro 500, timeout o conexión perdida | Consultar después de recuperar disponibilidad; ningún código aislado prueba rollback remoto |

Los incidentes conservan fase/código e IDs. REJECTED_BEFORE_SEND describe el
callback del intento actual, no ausencia global de efecto. APPLIED_CONFIRMATION_FAILED
no reemplaza evidencia/reconciliación. Buscar correlaciones `MIEC incident=<UUID>`
sin publicar cabeceras, cuerpos privados, credenciales o trazas con datos de negocio.

## 7. Límites que obligan a escalar

No existen en el MVP rutas operativas generales para:

- Continuar pasos READY desde E13, o convertir E13 nuevamente en E07 para enviarlos.
- Renovar/reemplazar EXECUTE_ACTION vencido en un run o reaprobar su contenido.
- Resolver por HTTP un despacho sin intento, cancelar PENDING abandonado o
  reconciliar NO_EFFECT para habilitar un nuevo intento del plan.
- Forzar APPLIED, borrar contradicciones, compensar todos los efectos o hacer
  rollback distribuido; un `rollbackStrategy` descriptivo no ejecuta compensación.
- Suspender/abortar arbitrariamente un ciclo en ejecución o reabrir E14.

La existencia de reglas F01 o servicios internos de IR-009 no equivale a una API
operativa autorizada. Un fallo parcial con pendientes puede quedar bloqueado
legítimamente: dejar constancia del alcance aplicado, lo no enviado y lo incierto.
No presentar el bloqueo documentado como recuperación universal implementada.

Escalar con IDs y evidencia al responsable técnico/funcional. Cualquier continuación
futura requiere contrato explícito, autorización, tratamiento de efectos previos,
transición F01 válida, persistencia/auditoría y pruebas de concurrencia/reinicio.
Crear otro plan/ciclo no es un atajo operativo para repetir acciones previas:
revisar antes sus consecuencias con el responsable y conservar trazabilidad.

Si se requiere copia para diagnóstico, seguir el respaldo coordinado del **par**
MIEC/PetClinic. No restaurar solo una base ni exponer dumps públicamente.

## 8. Acta del incidente y cierre de intervención

Usar [plantilla de intervención](LOCAL-recovery-record-template.md), fuera de Git
una vez rellenada con información privada. Registrar:

- Responsable, identidad actuante, fechas UTC, versión/commit y hashes de JAR.
- Sistema/ciclo/ronda/plan/run/acción/intento/operationKey y correlaciones disponibles.
- Estado y versiones antes/después; evidencia aplicada, pendiente e incierta por paso.
- Permiso, alcance, decisión y fechas; comandos exactos sin cabeceras ni secretos.
- Respuestas, hechos durables y razón para continuar, detener o escalar.
- Responsable siguiente y condición concreta para reevaluar si sigue bloqueado.

Resultados del incidente: `RECUPERADO_LOCALMENTE`, `RECONCILIADO_APPLIED`,
`FINALIZADO_TECNICAMENTE_E08` o `BLOQUEADO_ESCALADO`. Son etiquetas del acta,
no nuevos estados del dominio. Un incidente puede terminar escalado y el ciclo
seguir bloqueado; no cerrar el ciclo ni declarar éxito para cerrar el ticket.

## 9. Trazabilidad y validación del alcance

| Escenario documentado | Validación existente |
|---|---|
| Reserva vencida, generación y despacho incierto | `PlanExecutionStoreTest.shouldFenceExpiredCoordinatorAndRejectUncertainResume` |
| Inicio con permiso vencido | `PlanExecutionStoreTest.shouldRejectLegacyPlanAndExpiredAuthorizationWithoutChangingCycle` |
| Parcial y pérdida de propiedad; no envío de los siguientes | `PlanExecutionApplicationTest.shouldStopOnLostResponseAndNeverBlindlyReplay`, `shouldNotAdvanceAfterCoordinatorLeaseIsLostDuringEffect` |
| Permiso revalidado entre acciones | `PlanExecutionApplicationTest.shouldRevalidateAuthorizationBetweenActions` |
| Hecho confirmado recuperado con permiso vencido | `PlanExecutionApplicationTest.shouldRecoverCommittedResultAfterPermissionExpiresWithoutExecutingAgain` |
| Reconciliación APPLIED y parcial conservado | `PlanRecoveryApplicationTest.shouldReconcileLostResponseAndFinalizeWithoutSendingAgain`, `shouldPreservePartialResultAndNeverDispatchRemainingSteps` |
| NOT_FOUND, vencimiento/revocación durante GET, contradicción | `PlanRecoveryApplicationTest.shouldKeepNotFoundUncertainWithoutRetry`, `shouldRevalidatePermissionAfterNetwork`, `shouldRejectContradictionArrivingDuringLookup` |
| Roles, versiones, separación y rechazo humano | `PlanRecoveryApplicationTest`; `PlanExecutionApiTest` |
| Finalización, rollback de auditoría y concurrencia | `PlanExecutionFinalizationTest` |

Comando de revalidación, desde la raíz MIEC con SDK instalado:

```powershell
.\mvnw.cmd '-Dtest=PlanExecutionStoreTest,PlanExecutionApplicationTest,PlanRecoveryApplicationTest,PlanExecutionFinalizationTest,PlanExecutionApiTest' test
```

Estos tests usan bases propias y adaptadores de laboratorio; sus cambios SQL de
fechas/estado son fixtures de prueba, **no instrucciones para el operador**.
Esta comprobación no recrea el piloto completo, ni acredita infraestructura real
adicional. Los resultados de la revisión se registran al final de esta guía.

Fuentes de implementación: [coordinación](../src/main/java/com/miec/orchestration/PlanExecutionStore.java),
[recuperación](../src/main/java/com/miec/orchestration/PlanRecoveryApplicationService.java),
[guardas de reconciliación](../src/main/java/com/miec/orchestration/PlanRecoveryStore.java),
[API](../src/main/java/com/miec/api/PlanExecutionController.java).
Contexto histórico: [MVP-03](MVP-03-finalization-recovery.md).

### Resultado de esta revisión

Ejecutado el 2026-11-04; finalización a las 17:00:55, hora de Bogotá.

| Clase existente reejecutada | Casos | Fallos / errores / omisiones |
|---|---:|---|
| PlanExecutionStoreTest | 6 | 0 / 0 / 0 |
| PlanExecutionApplicationTest | 8 | 0 / 0 / 0 |
| PlanRecoveryApplicationTest | 7 | 0 / 0 / 0 |
| PlanExecutionFinalizationTest | 5 | 0 / 0 / 0 |
| PlanExecutionApiTest | 8 | 0 / 0 / 0 |
| **Total enfocado** | **34** | **0 / 0 / 0** |

Se revisaron rutas, cuerpos, roles y guardas contra el código fuente; los enlaces
y los ejemplos JSON se verificaron. No se añadieron pruebas ni se modificaron
Java, migraciones o APIs: se reutilizó la cobertura relevante del comportamiento
que este procedimiento describe. No se reejecutó la regresión completa ni CI.
Las pruebas no intervinieron en `miec-v01` ni `petclinic`. El log local es
`review-operational-recovery.log`, excluido de Git; los informes se generan en
`target/surefire-reports`. La entrega no elimina las deudas de continuación parcial,
renovación de ejecución, suspensión/aborto o resolución de ausencia de efecto.
