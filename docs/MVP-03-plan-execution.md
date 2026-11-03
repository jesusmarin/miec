# MVP v01 — incrementos 3.1, 3.2 y 3.3

Actualización posterior: [pasos 5–8 de finalización y recuperación](MVP-03-finalization-recovery.md)
incorporan finish hacia E08 y reconciliación APPLIED por consulta autorizada.
Los límites y conteos siguientes corresponden al incremento original.

## Alcance

Actualización de guardas: [admisión previa al envío e incidentes](MVP-03-send-admission.md)
implementa los pasos 1–4 posteriores. Revalida permiso y propiedad después de lookup,
añade V014 y amplía el recurso con incidents. Los conteos de esta guía corresponden
al incremento original; los resultados actualizados se registran en esa ampliación.

Se conecta un plan aprobado en E06 con `IdempotentActionExecutionService` e IR-009.
Las acciones se ejecutan secuencialmente en el orden persistido, fuera de las
transacciones del ciclo. La coordinación y los intentos tienen propietarios y
vigencias diferentes: la primera gobierna la secuencia, IR-009 gobierna cada intento.

Se implementa recuperación mínima de hechos locales, no reconciliación remota por
API. Al registrar todos los efectos el run queda APPLIED y el ciclo permanece en E07,
o conserva E13 si estaba recuperándose. E08, finalización completa de fallos, nuevas
mediciones y evaluación quedan para los incrementos 4–8. APPLIED no demuestra mejora,
calidad de evidencia de medición, causalidad ni rollback distribuido.

## 3.1 — Definición ejecutable y aprobación

Una acción puede incluir `execution` al **proponer** el plan:

```json
{
  "actionType": "PROCESS_EXECUTION",
  "description": "Aplicar el cambio del proyecto destinatario",
  "execution": {
    "adapterId": "http-recipient-v1",
    "adapterVersion": "1",
    "destinationId": "recipient-project",
    "mediaType": "application/json",
    "body": "{\"change\":1}"
  }
}
```

El ejemplo de body es ilustrativo: el destinatario define y valida su contrato de
negocio. PROCESS_EXECUTION conserva la clasificación existente de IR-009; su
descripción no se interpreta como shell ni como un programa ejecutable.

El servidor valida el catálogo y captura schemaVersion 01 y bindingFingerprint.
La definición queda dentro del JSON v01 de acciones y, por tanto, en la huella del
plan que se solicita y aprueba. El parser existente conserva UUID/tipo compatibles;
la nueva propiedad opcional no altera el comportamiento de los planes anteriores.
No existe un endpoint para añadir o cambiar execution después de aprobar.

El snapshot contiene destino lógico, versión, mediaType y **cuerpo exacto**. El
formato UTF8-V1 de ExecutionRequest distingue espacios y orden del JSON. Máximo
64 KiB UTF-8 por acción. La clave de operación se calcula con la función compartida
`ExecutionOperationRegistry.operationKey(planId, actionId)`. Calcularla no implica
haber reservado una operación ni haber aplicado el efecto.

El enlace instalado incluye rutas, protocolo, ID y versión en su huella no secreta.
Cambiar esos datos después de aprobar impide ejecutar el plan. Rotar la clave del
servidor no modifica la definición de negocio ni su huella. Las credenciales no
forman parte del comando ni de la respuesta del catálogo. No guardar secretos en body.

Planes históricos descriptivos permanecen consultables y aprobables por compatibilidad,
pero la ejecución exige definición para **todas** las acciones. No se convierten ni
se completan automáticamente; debe crearse una propuesta nueva con contenido revisable.

## Catálogo y adaptador HTTP genérico

`ExecutableActionAdapter` es un puerto reutilizable de servidor. Sus beans se
registran en `ExecutableActionCatalog`; IDs duplicados impiden arrancar. El catálogo
vacío permite iniciar MIEC y consultar datos, pero no ejecutar acciones. El puerto
bind valida/captura sin hacer IO; sus implementaciones son código confiable del servidor.
Ni la API ni el JSON del plan pueden instalar clases, suministrar URLs o elegir secretos.

El JAR incluye un adaptador genérico, desactivado por defecto. Se habilita mediante:

| Variable | Propósito / valor |
|---|---|
| MIEC_RECIPIENT_ENABLED | true para instalarlo; false por defecto |
| MIEC_RECIPIENT_ADAPTER_ID | http-recipient-v1 por defecto |
| MIEC_RECIPIENT_ADAPTER_VERSION | 1 por defecto |
| MIEC_RECIPIENT_DESTINATION_ID | ID lógico obligatorio, letras/números y . _ : - |
| MIEC_RECIPIENT_BASE_URL | Origen del servidor, sin usuario, query, fragmento ni prefijo de ruta |
| MIEC_RECIPIENT_API_KEY | Clave de servidor obligatoria, mínimo 16 caracteres ASCII visibles |
| MIEC_RECIPIENT_EXECUTE_PATH | /miec/operations por defecto; ruta configurada en servidor |
| MIEC_RECIPIENT_TIMEOUT | PT10S por defecto; de 1 ms a 30 s |

HTTPS es obligatorio fuera de IP loopback literal. HTTP se admite en 127.0.0.1 o
::1 para desarrollo; no se resuelve un nombre arbitrario como supuesto loopback.
El JDK valida TLS con el truststore configurado. No se siguen redirecciones ni
referencias externas devueltas por el destinatario. La respuesta JSON está limitada
a 64 KiB y el plazo cubre el cuerpo completo, no solo las cabeceras.

El adaptador instalado admite application/json y objeto JSON. El destino debe ofrecer:

- GET `/miec/operations/{operationKey}`: APPLIED con identidad completa o 404 JSON
  `{"code":"OPERATION_NOT_FOUND"}`. Un 404 genérico, error, timeout o respuesta ajena
  al contrato produce UNKNOWN. Una ausencia visible no prueba NO_EFFECT.
- POST a execute-path: cuerpo exacto, `Idempotency-Key`, `X-MIEC-Destination`,
  `X-MIEC-Fingerprint` y `X-MIEC-Fencing-Token`. Ambos métodos autentican mediante
  `X-MIEC-Api-Key`. Respuesta 200/201 JSON:

```json
{
  "status": "APPLIED",
  "operationKey": "UUID de la operación",
  "destinationId": "recipient-project",
  "fingerprint": "SHA-256 UTF8-V1 de la solicitud",
  "resultReference": "referencia durable del resultado"
}
```

MIEC comprueba clave, destino, huella, estado y referencia antes de aceptar el
resultado. El destinatario debe confirmar ledger, efecto y resultado atómicamente,
rechazar reutilización con distinto contenido y conservar las claves durante el
horizonte de recuperación. Transmitir fencing no acredita que el destinatario lo
haga cumplir. El adaptador no garantiza por sí solo las propiedades del sistema remoto.
El adaptador integrado configura un destino por instancia; otros proveedores pueden
instalar beans diferentes sin introducir clases de PetClinic en el núcleo.

## 3.2 — Persistencia y propiedad

V013 añade exclusivamente:

- plan_execution: un run por plan, huella aprobada, ciclo, estado, versión, generación,
  propietario, vencimiento, autores y fechas.
- plan_execution_step: orden 0–19, acción, permiso, clave esperada, estado, intento
  observado y referencia de resultado. Referencia el intento original de IR-009.

No sustituye execution_attempt, execution_operation, sus claves, evidencias o
reconciliaciones. La migración conserva planes y autorizaciones existentes sin
fabricar coordinaciones. Se valida en base nueva y al actualizar desde V012.

El inicio bloquea sistema → ciclo → plan → run → permisos. Verifica E06, versiones,
plan aprobado, enlaces instalados, huellas y permisos actuales para todas las
acciones. Rechaza planes con historial IR-009 previo no asociado a este flujo.
Reserva run/pasos y realiza E06 → E07 con auditoría en la misma transacción.
Si falla una inserción, todo se revierte y no se invoca al destinatario.

El heartbeat de coordinación utiliza la misma duración/frecuencia configurada
para actividad (`miec.execution.lease-duration`, `miec.execution.heartbeat-interval`),
pero renueva solo su run. No renueva permisos ni sustituye el heartbeat de IR-009.
La generación cambia al reemplazar o invalidar propiedad. El propietario anterior
no puede registrar progreso o despachar pasos nuevos. Heartbeat no aumenta la versión
de dominio; los cambios de estado/progreso sí la incrementan.

Antes de cada llamada se confirma DISPATCHING. Una caída entre esa marca y la
creación del intento se considera incierta aunque todavía no aparezca un intento.
La aplicación nunca deduce que es seguro reenviar solo porque no lo encuentra.
Antes de cada IO se comprueban propiedad, contexto y huella; IR-009 revalida permiso
inmediatamente antes de ejecutar. Una pérdida posterior no puede cancelar por sí
sola un efecto que ya está en vuelo: se conserva el parcial y no se inicia el siguiente.

## 3.3 — APIs, roles y respuestas

Todas requieren Basic. Los comandos son exclusivamente JSON. El actor procede del
servidor; no reciben ejecutores, acciones nuevas, URLs o tokens de propiedad.

| Método / ruta | Rol | Entrada / resultado |
|---|---|---|
| GET /api/execution-adapters | AUTHOR o APPROVER | IDs, versión, destino y huella pública |
| POST /api/cycles/{cycleId}/plans/{planId}/execution | AUTHOR | expectedCycleVersion, expectedPlanVersion, reason; 201 y Location |
| GET /api/plans/{planId}/execution | AUTHOR o APPROVER | Run, pasos y proyección actual IR-009; 200 |
| POST /api/plans/{planId}/execution/resume | AUTHOR | expectedVersion del run y reason; 200 |
| POST /api/plans/{planId}/execution/recover | APPROVER | expectedVersion del run y reason; 200; sin envío |

Inicio de ejemplo, usando versiones recién consultadas:

```json
{"expectedCycleVersion":8,"expectedPlanVersion":3,"reason":"Ejecutar el contenido aprobado"}
```

Los números son ilustrativos. El número de acciones y eventos altera las versiones;
no se deben copiar valores fijos sin consultar el recurso. Location apunta a
`/api/plans/{planId}/execution`. Solo hay una coordinación por plan: un segundo
inicio responde 409, nunca crea otra identidad del efecto.

El endpoint de inicio es síncrono y puede durar la suma de los tiempos de sus
acciones. 201 significa que existe coordinación durable; revisar state, steps y
requiresReview. Un fallo registrado puede devolver RECOVERY_REQUIRED. Ante timeout
del cliente consultar Location antes de tomar otra decisión. Si la base falla, puede
responder 500 aunque haya ocurrido un efecto externo; eso tampoco autoriza un reenvío.

Estados de paso: READY, DISPATCHING, APPLIED y RECOVERY_REQUIRED. Estado del run:
RUNNING, APPLIED o RECOVERY_REQUIRED. observedOutcome conserva la proyección de
IR-009 (o NONE/UNCONFIRMED/CONFLICT). Un run históricamente APPLIED puede mostrar
requiresReview=true si posteriormente llegan contradicciones. GET no reescribe el
historial ni oculta esa situación.

Errores: 400 contrato o relación ciclo/plan inválidos; 401 sin credenciales; 403 rol;
404 plan/run inexistente; 409 versión, etapa, permiso, propiedad o enlace incompatible;
415 si no se envía JSON; 500 saneado ante fallos internos. Ejemplos de códigos:
PLAN_EXECUTION_EXISTS, EXECUTABLE_BINDING_INVALID, EXECUTION_AUTHORIZATION_INVALID,
COORDINATOR_BUSY, DISPATCH_UNCERTAIN, COORDINATOR_LOST y RUN_VERSION_CONFLICT.

## Recuperación mínima y límites pendientes

Resume solo puede adquirir una reserva RUNNING vencida, en E07, sin despacho
incierto. Revalida permisos de lo pendiente y resultados anteriores; no roba un
coordinador vigente y no abre nuevamente un run APPLIED o RECOVERY_REQUIRED.

Recover exige APPROVER y no depende de que EXECUTE_ACTION siga vigente: importa
solo hechos locales APPLIED ya vinculados a clave, solicitud e intento correspondientes.
No llama al destinatario, no crea intentos, no renueva permisos, no registra evidencia
DESTINATION_VERIFIED y no emite una autorización RECONCILE_ACTION. Un resultado
UNKNOWN/EXPIRED/CONFLICT no se convierte en aplicado por ausencia de información.

Si se cayó tras el commit IR-009 y antes de actualizar el paso, puede recuperar
el hecho confirmado sin otro envío. Si se cayó tras el efecto remoto pero antes
de registrar el resultado IR-009, conserva incertidumbre y requiere el procedimiento
de verificación/reconciliación del incremento 4. Si quedan acciones READY después
de una recuperación parcial en E13, este incremento no continúa enviándolas.

Tampoco se añade una vía para renovar permisos vencidos de un plan aprobado o
cancelarlo desde E06: F01 no permite E06 → E12. Ese caso se conserva bloqueado y
debe diseñarse explícitamente en el incremento 4, sin cambiar F01 o prolongar permisos
de manera implícita. No hay compensación automática ni paralelismo de acciones.

## Demostración y verificación

Swagger autenticado documenta las rutas en `/swagger-ui/index.html`. Para un
destinatario real compatible, configurar el enlace en el proceso de MIEC, reiniciar,
consultar el catálogo y proponer execution antes de solicitar/aprobar permisos.
La definición de negocio debe ser compatible con el proyecto destinatario.

Las pruebas usan adaptadores solo de test y un servidor HTTP con ledger/efecto JDBC
atómicos. No ejecutan cambios contra PetClinic ni contra la base del usuario. Las
pruebas entre JVM del JAR se ejecutan expresamente después de construirlo:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

La prueba de caída mata únicamente su proceso hijo después del commit remoto.
Se usa WRITE_DELAY=0 y archivos aislados; no acredita tolerancia a cortes de energía,
PostgreSQL ni garantías de destinatarios ajenos al fixture. El servidor fixture se
ejecuta en la JVM de pruebas; su ledger se cierra y reabre para verificar persistencia.
El JAR incluye solo el adaptador genérico y los puertos, nunca ese servidor de prueba.

## Guion de laboratorio

[mvp03-demo.ps1](mvp03-demo.ps1) descubre el catálogo instalado, crea un sistema/ciclo
nuevos y utiliza la ruta medible: objetivo → línea base → cierre de observación →
diagnóstico → plan ejecutable → solicitud → aprobación. Sin -Execute termina en E06.
Usa las dos cuentas suministradas como ejercicio automatizado; no sustituye la revisión
humana real del plan y de sus bytes de negocio.

```powershell
$author = Get-Credential -UserName author
$approver = Get-Credential -UserName approver
.\docs\mvp03-demo.ps1 -AuthorCredential $author -ApproverCredential $approver -Body '{"change":1}'
```

El body del ejemplo debe reemplazarse por el contrato del destinatario instalado.
Añadir -Execute solicita un efecto externo real. El guion se validó sintácticamente;
no se ejecutó contra aplicaciones ni bases del usuario. La demostración equivalente
se ejecuta automáticamente por HTTP en bases y procesos propios.

## Resultados de validación

| Parte | Evidencia |
|---|---|
| 3.1 | 12 pruebas enfocadas satisfactorias de definición, aprobación y compatibilidad |
| 3.2 | 15 pruebas enfocadas satisfactorias de migración, coordinación y mediciones previas |
| 3.3, servicio | 8 pruebas: ejecución secuencial, incertidumbre, permiso vencido, pérdida de propietario, revocación, concurrencia, contradicciones y roles/transacciones |
| HTTP de aplicación y seguridad | 10 pruebas enfocadas satisfactorias |
| Adaptador HTTP | 5 pruebas con transporte real, replay, respuesta perdida, límites y configuración |
| Regresión clean verify | 789 pruebas; cero fallos, errores u omisiones |
| JAR entre JVM | 5 pruebas; incluye ejecución durable y terminación forzada tras commit remoto |

Los conteos enfocados se superponen con la regresión y no se suman. Las cinco pruebas
del JAR se ejecutan aparte: ciclo original, autorización, medición, ejecución durable y
caída real de la JVM de MIEC. El workflow existente selecciona MvpLifecycleRestartIT,
por lo que incorpora también los dos nuevos escenarios al subir el commit. La ejecución
remota de GitHub Actions no se realizó desde esta sesión.

Logs locales ignorados por Git: review-mvp03-step31.log, review-mvp03-step32.log,
review-mvp03-step33-service.log, review-mvp03-step33-http.log,
review-mvp03-http-adapter.log, review-mvp03-regression.log y review-mvp03-restart.log.

Los incrementos 3.1, 3.2 y 3.3 quedan implementados y validados. El incremento 4
deberá cerrar la finalización del ciclo y la recuperación/reconciliación completa
antes de habilitar la medición posterior y evaluación de los incrementos 5–8.
