# MVP-03 — admisión previa al envío, pasos 1–4

## Paso 1: contrato y frontera temporal

El punto de admisión está después del lookup sin efectos y antes de entrar en
IdempotentActionExecutor.execute. NOT_FOUND permite considerar un envío bajo el
contrato de deduplicación durable; no prueba ausencia global de efecto.

Antes de admitir se comprueban contexto de coordinación, huella aprobada, identidad
del intento, alcance y vigencia de la autorización. También estado IN_PROGRESS,
propietario, fencing token, lease y salud del supervisor del intento. Se usa el reloj
de base de datos después de adquirir los bloqueos. Ninguna guarda renueva el permiso
o el lease. Las transacciones terminan antes de invocar IO externo.

Orden: lookup → guarda local del adaptador/coordinador → salud del supervisor →
validación transaccional final → salud del supervisor → invocación del envío.
Consultar APPLIED permite recuperar un resultado existente sin un nuevo envío;
UNKNOWN no se transforma en éxito ni habilita POST.

Una admisión local no puede ser atómica con el efecto remoto sin cooperación del
destinatario. Una revocación o pérdida de propiedad después de admitir no garantiza
cancelación del efecto en vuelo. El destinatario debe implementar deduplicación,
fencing y, si exige vigencia en el instante del efecto, su propia verificación.

## Pasos 2 y 3: validación final

La validación reutiliza IR-010 y el snapshot del plan; no añade un segundo motor de
permisos. Se bloquea plan → intento → autorización. La solicitud de la operación
debe corresponder a clave, destino y bytes persistidos. En planes MVP con huella de
aprobación se revalida esa huella y la definición ejecutable. Los contratos internos
históricos sin esa extensión conservan validación de acción, permiso y propiedad.

Los adaptadores tienen un hook beforeSend para validación local sin IO ni efectos.
La guarda de coordinación se ejecuta allí, antes de la admisión IR-009, evitando
dejar una consulta larga de coordinación después de la última validación de permiso.
Un adaptador instalado es código confiable del servidor, no un plugin aportado por
el comando HTTP. Una excepción del propio adaptador no puede acreditar ausencia de envío.

## Paso 4: incidentes y límites de evidencia

V014 implementa una migración aditiva para un registro append-only de incidentes:
UUID de correlación, plan/ciclo/acción, intento y coordinación cuando existan,
código cerrado, fase e instante del reloj de base. No contendrá mensajes de
excepción, cuerpos, claves, SQL ni trazas. No modifica migraciones publicadas ni
los estados y políticas de retry de execution_attempt.

Las fases distinguirán rechazo conocido anterior al envío del intento actual,
resultado incierto, efecto observado aplicado con confirmación/auditoría fallida,
e interrupción de coordinación. El rechazo no declara NO_EFFECT_CONFIRMED de la
operación lógica ni reintenta automáticamente. La evidencia y UNKNOWN/IN_PROGRESS
de IR-009 se conservan cuando no procede una transición segura.

Los fallos secundarios de auditoría se adjuntan al fallo original. Si la base no
permite registrar el incidente, se informa mediante correlación y código saneado;
no se anuncia una auditoría durable que no pudo guardarse. Las APIs no exponen las
causas internas. No se implementa E08 ni reconciliación remota en estos cuatro pasos.

## Validaciones previstas antes de escribir pruebas

- Vencimiento y revocación durante lookup: cero invocaciones de execute.
- Cambio de alcance/solicitud/huella: rechazo sin envío.
- Lease del intento vencido con coordinador vigente, propietario/token diferentes:
  rechazo sin envío ni renovación implícita.
- Heartbeat fallido durante lookup: rechazo aunque el adaptador ignore interrupción.
- Respuesta perdida después del envío: incertidumbre, sin falsa ausencia de efecto.
- Efecto observado seguido de fallo de persistencia: incidente diferenciado y retry bloqueado.
- Fallos secundarios conservados, incidentes saneados por HTTP y migración histórica.
- Regresión completa y pruebas existentes del JAR entre JVM.

## Implementación y lectura por API

DispatchAdmission es una frontera interna de un solo envío. La controla el núcleo,
no el adaptador, y comprueba el supervisor antes y después de validar bajo locks.
Si falla, no entra en execute. Si el callback ya fue invocado, una excepción con
apariencia de rechazo no puede cambiar retroactivamente la fase a «sin envío».
El hook beforeSend se ejecuta antes de la validación final y tiene contrato local
sin IO. La ruta heredada de callback también pasa por esta admisión.

El algoritmo MVP-PLAN-01 se comparte entre aprobación y admisión mediante
PlannedContentFingerprint; no cambian su versión ni las huellas históricas. En la
ruta protegida se comparan clave y solicitud con execution_operation y, cuando
existe, con la definición ejecutable aprobada. No se reformatea el cuerpo.

GET /api/plans/{planId}/execution incluye incidents. Cada elemento contiene
incidentId, relatedIncidentId, runId, executionId, planId, cycleId, actionId,
origin, phase, code, recordedBy, secondaryFailureCount y occurredAt. Los IDs de
contexto se reconstruyen de la persistencia, no de un payload de diagnóstico del
cliente. V014 utiliza FKs compuestas para exigir el mismo contexto del intento y run.

| Fase | Significado y límite |
|---|---|
| REJECTED_BEFORE_SEND | Este intento/paso no entró en el callback de envío; no certifica ausencia global del efecto |
| OUTCOME_UNCERTAIN | Se invocó el adaptador o no se confirmó su resultado; no habilita retry automático |
| APPLIED_CONFIRMATION_FAILED | Se observó efecto aplicado, pero falló confirmar o registrar progreso; la fase no sustituye reconciliación |
| COORDINATION_INTERRUPTED | No se pudo confirmar el progreso de coordinación; no se inventa un resultado |

Ejemplos de code: AUTHORIZATION_INVALID, APPROVED_CONTENT_CHANGED, REQUEST_CHANGED,
ATTEMPT_NOT_RUNNING, ATTEMPT_OWNER_CHANGED, ATTEMPT_LEASE_EXPIRED,
ATTEMPT_HEARTBEAT_FAILED, THREAD_INTERRUPTED, PRE_SEND_CONTEXT_REJECTED,
ADMISSION_UNAVAILABLE, REMOTE_OUTCOME_UNCERTAIN, APPLIED_CONFIRMATION_FAILED,
PLAN_ADMISSION_REJECTED y PLAN_PROGRESS_NOT_RECORDED. Ninguno se obtiene de
getMessage de la excepción. CORE registra el hecho técnico y COORDINATOR conserva
su propagación con relatedIncidentId cuando existe un incidente previo del núcleo.

Un paso READY sin intento todavía puede registrar PLAN_ADMISSION_REJECTED sin
borrar los efectos aplicados en pasos anteriores. Los incidentes son históricos:
no desaparecen si posteriormente se recupera el run. El contador de secundarios
es una fotografía al registrar, no un estado mutable de la excepción.

Si la auditoría del núcleo o de la coordinación falla, la API responde 500:

```json
{"code":"EXECUTION_AUDIT_UNAVAILABLE","message":"No se pudo confirmar la auditoría de ejecución","fields":{"incidentId":"UUID de correlación"}}
```

Ese UUID puede existir solo en el log si el INSERT no pudo confirmarse. Buscar
`MIEC incident=<UUID>`; el log contiene origen, fase, código, auditUnavailable y
número de secundarios, nunca la excepción cruda ni su mensaje. El fallo primario
conserva los secundarios y sus causas; la respuesta HTTP no los serializa.

Los estados UNKNOWN/IN_PROGRESS de IR-009 se mantienen si no procede finalizarlos,
por ejemplo con lease vencido u otro propietario. Registrar un rechazo de admisión
no fuerza FAILED/NO_EFFECT_CONFIRMED ni habilita un nuevo envío. Las llamadas que
ya estaban en vuelo mantienen el tratamiento conservador de incertidumbre.

La inmutabilidad de incidentes es de aplicación: solo existe INSERT y lectura.
No se afirma restricción de UPDATE/DELETE para un administrador con SQL directo.
Estos cuatro pasos no incorporan nuevas rutas, roles, transición a E08 ni un
procedimiento completo de reconciliación remota.

## Comandos de verificación

```powershell
.\mvnw.cmd '-Dtest=PlanDispatchAdmissionTest,ExecutionDispatchHeartbeatTest,ExecutionIncidentIntegrationTest,PlanExecutionApiTest' test
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

Las pruebas usan bases propias y hooks exclusivos de test, sin modificar permisos,
relojes o efectos de la aplicación del usuario. Para simular lease vencido se
conserva started_at <= heartbeat_at < lease_expires_at en el fixture. Las pruebas
de heartbeat emplean el supervisor real e ignoran deliberadamente la interrupción
en lookup para verificar que la salud retenida siga bloqueando el envío.

## Resultado de la entrega

| Paso | Validación |
|---|---|
| 1. Contrato | Frontera temporal, significado de rechazo y diseño V014 documentados antes de construirlos |
| 2. Autorización | 17 pruebas enfocadas satisfactorias; vencimiento, revocación, alcance, huella y solicitud tras lookup |
| 3. Propiedad | 20 pruebas enfocadas satisfactorias; lease, propietario, token, estado y heartbeat sin envío |
| 4. Incidentes | 7 pruebas de incidentes incluidas en regresión y 6 pruebas HTTP del recurso; fases, saneamiento, correlación y secundarios |
| Regresión clean verify | 809 pruebas; cero fallos, errores u omisiones |
| JAR entre JVM | 5 pruebas; reinicio y terminación forzada tras commit remoto sin reenvío automático |

Los grupos enfocados se superponen con la regresión; no se suman. La comprobación
del JAR confirma la presencia de DispatchAdmission, ExecutionIncidentService,
PlannedContentFingerprint y V014, sin fixtures, clases de test, experimentos ni SDK
de destinatario en producción. Los enlaces locales de documentación fueron verificados.

Logs ignorados por Git: review-mvp03-admission-step2.log,
review-mvp03-admission-step3.log, review-mvp03-admission-step4-core.log,
review-mvp03-admission-step4-incidents.log, review-mvp03-admission-step4-http.log,
review-mvp03-admission-regression.log y review-mvp03-admission-restart.log.
Las pruebas utilizaron bases/procesos propios. La ejecución remota del workflow
de GitHub sigue dependiendo de subir el commit; no se realizó desde esta sesión.

Los pasos 1–4 quedan completados. Reiniciar la aplicación con el nuevo JAR aplica
V014 mediante Flyway; no se modificó ninguna migración anterior. La finalización
hacia E08, la reconciliación remota y los pasos siguientes de la ruta permanecen pendientes.
