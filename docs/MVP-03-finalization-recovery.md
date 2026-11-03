# MVP-03 — pasos 5–8: finalización y recuperación verificable

Continúa los [pasos 1–4](MVP-03-send-admission.md). Esta entrega completa la
finalización técnica hacia E08. No declara terminado el MVP v01: medición posterior,
evaluación, decisión y aprendizaje conservan sus incrementos del contrato de cierre.

## Secuencia y criterios

| Paso | Desarrollo | Puerta de validación |
|---|---|---|
| 5 | Finalización explícita, atómica y versionada | Todas las acciones APPLIED, referencias válidas, sin contradicciones ni coordinador vigente; rollback si falla auditoría |
| 6 | Permiso RECONCILE_ACTION y consulta confiable | Solicitante distinto del aprobador; consulta sin POST, verificación de identidad y revalidación del permiso después de la red |
| 7 | APIs, seguridad y contratos | HTTP real, roles, entrada estricta, Swagger autenticado y errores saneados |
| 8 | Regresión y demostración entre JVM | Reinicio conserva E08; caída tras commit remoto se resuelve por consulta autorizada sin duplicación |

## Finalización y límites temporales

`start` continúa dejando el run APPLIED y el ciclo en E07; `recover`/`reconcile`
conservan E07 o E13. APPROVER debe revisar y solicitar `finish` con versiones del
run y ciclo. Bajo bloqueo sistema → ciclo → plan → run, se comprueba contenido
aprobado, orden completo, operación y solicitud original, último intento y ausencia
de evidencia contradictoria. Se importan referencias de IR-009 y se registran
estado E08, versiones, evento PLAN_EXECUTION_FINALIZED y transición F01 en una
transacción. También admite E13 → E08. No requiere renovar EXECUTE_ACTION vencido.

Las escrituras de evidencia IR-009 bloquean el mismo plan: no pueden intercalarse
entre esa validación y el commit. Una contradicción posterior permanece visible
en `requiresReview`; no borra el evento ni cambia retrospectivamente la historia.
E08 habilita verificar el objetivo: no demuestra mejora, causalidad ni calidad de
la medición. No hay transición genérica ni compensación distribuida.

## Permiso y recuperación

AUTHOR solicita RECONCILE_ACTION para una acción incierta del run. APPROVER de
identidad diferente lo aprueba o rechaza. Se reutilizan `authorizations` y
`authorization_decision`; cada solicitud/decisión incrementa run y ciclo, con
evento atómico. La vigencia es de 30 a 3600 segundos desde la solicitud. Una
solicitud vencida requiere otra explícita, nunca una prórroga implícita.
El aprobador registrado es quien puede consultar/reconciliar con ese permiso.
El alcance no permite ejecutar acciones y no modifica el permiso EXECUTE_ACTION.

La recuperación exige E07/E13, plan aprobado sin cambios, coordinador ausente o
vencido e intento UNKNOWN o IN_PROGRESS vencido. El catálogo revalida el enlace
aprobado antes y después de consultar. No acepta URLs, credenciales, resultados,
referencias de evidencia ni resoluciones declaradas por el cliente.

El adaptador HTTP instalado también implementa `DestinationEvidenceSource`.
Comparte destino, autenticación, TLS, timeout y límite de respuesta con ejecución,
pero esta ruta solo usa GET. `DestinationEvidenceVerificationService` reconstruye
el snapshot persistido y valida clave, destino, huella y referencia APPLIED.
La fecha se asigna al observar localmente usando reloj de base; no se inventa la
fecha remota del efecto. Se vuelve a autorizar antes de guardar la evidencia.

Después, `ExecutionReconciliationService` revalida permiso y evidencia bajo lock
del plan, conserva la decisión APPLIED y el estado original del intento. Finalmente
se recupera la proyección del run con su versión esperada. Son commits separados:
una interrupción puede dejar evidencia o decisión durable sin actualizar el run.
Reconsultar el run y recuperar hechos locales; no volver a enviar el efecto.
El ID de evidencia es determinista por intento/permiso. Repetir la misma revisión
con versiones actuales y el mismo motivo conserva evidencia y decisión únicas.
Si ya existe una decisión con otro contenido, no se reescribe.

NOT_FOUND, UNKNOWN, timeout, fuente ausente, cambio de enlace o contradicción
conservan el bloqueo. NOT_FOUND no es NO_EFFECT_CONFIRMED. Las acciones READY
posteriores no se despachan desde E13. Esta entrega no agrega retry automático,
continuación parcial en E13, renovación de ejecución, suspensión/aborto operativo
ni reconciliación NO_EFFECT por HTTP; requieren un contrato separado.

## API y recorrido reproducible

Todas las rutas requieren Basic; Swagger autenticado está en
`/swagger-ui/index.html`. Los cuerpos desconocidos se rechazan y el actor procede
exclusivamente de Spring Security.

| Método y ruta | Rol | Cuerpo |
|---|---|---|
| POST `/api/plans/{planId}/execution/finish` | APPROVER | expectedVersion (run), expectedCycleVersion, reason |
| POST `/api/plans/{planId}/execution/reconciliation-requests` | AUTHOR | expectedVersion (run), actionId, validForSeconds, reason |
| POST `/api/execution/reconciliation-authorizations/{id}/approve` | APPROVER distinto | expectedVersion (permiso), reason |
| POST `/api/execution/reconciliation-authorizations/{id}/reject` | APPROVER distinto | expectedVersion (permiso), reason |
| POST `/api/plans/{planId}/execution/reconcile` | APPROVER del permiso | expectedVersion (run), actionId, authorizationId, reason |
| GET `/api/authorizations/{id}` | Ambos | Sin cuerpo; consulta solicitud y decisión |
| GET `/api/plans/{planId}/execution` | Ambos | Sin cuerpo; consulta pasos, resultados e incidentes |

La solicitud responde 201 con Location; decisiones, reconcile y finish responden
200. Entradas inválidas: 400; sin identidad: 401; rol/self-approval: 403;
inexistencia: 404; versiones, etapa, vigencia o resultados incompatibles: 409.
Un fallo de persistencia responde 500 saneado, sin afirmar éxito de la operación.

1. Preparar y ejecutar un plan siguiendo [la guía](MVP-03-plan-execution.md).
2. Consultar el run. Si todos los efectos están APPLIED, revisar y llamar finish.
3. Si hay incertidumbre, esperar fin de la propiedad; no llamar start nuevamente.
4. Como AUTHOR, solicitar permiso para la acción incierta:

```json
{"expectedVersion":12,"actionId":"UUID de la acción","validForSeconds":600,"reason":"Consultar resultado incierto"}
```

5. Como APPROVER distinto, revisar plan/contexto, solicitud e incidentes y aprobar:

```json
{"expectedVersion":0,"reason":"Autorizo la consulta de esta acción"}
```

6. Consultar el run nuevamente (solicitud y aprobación cambiaron versiones), y
   llamar reconcile con su versión actual:

```json
{"expectedVersion":14,"actionId":"UUID de la acción","authorizationId":"UUID del permiso","reason":"Confirmar el efecto observado"}
```

7. Repetir la revisión explícita para otras acciones inciertas si corresponde.
   Si todas están APPLIED, consultar también la versión del ciclo y llamar finish:

```json
{"expectedVersion":15,"expectedCycleVersion":22,"reason":"Todas las acciones aplicadas y revisadas"}
```

Los números son ilustrativos, nunca constantes para copiar sin consultar. Un 409
requiere releer/revisar; no implica reenvío. El guion automático de pruebas usa
bases y procesos propios, sin ejecutar contra PetClinic ni la base del usuario.

## Validaciones

Antes de escribir las pruebas se definieron estos casos: E07/E13 hacia E08 con
hechos completos; UNKNOWN/contradicción bloqueantes; roles y versiones; finalización
concurrente; rollback de auditoría; pérdida de respuesta; permiso separado; revocación
o vencimiento durante GET; contradicción concurrente; NOT_FOUND conservador; ninguna
acción pendiente reenviada; HTTP/OpenAPI; reinicio y caída real de JVM tras commit.

```powershell
.\mvnw.cmd '-Dtest=PlanExecutionFinalizationTest,PlanRecoveryApplicationTest,PlanExecutionApiTest,ConfiguredHttpActionAdapterTest' test
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

No se requiere V015: no se alteran estados ni tablas existentes, y las decisiones
y evidencias de recuperación permanecen en IR-009. Las nuevas constantes de evento
son compatibles con la columna textual. No se modifican migraciones publicadas.
H2 y el fixture HTTP no acreditan PostgreSQL, cortes de energía ni garantías de
deduplicación de un destinatario ajeno a la prueba.

## Resultado de la entrega

| Validación | Resultado |
|---|---|
| Paso 5, finalización | 5 pruebas satisfactorias: auditoría, rollback, incertidumbre, roles/versiones y concurrencia |
| Pasos 5–6, servicios | 12 pruebas satisfactorias; incluye 7 de recuperación remota |
| Paso 7, HTTP y adaptador | 13 pruebas satisfactorias, con OpenAPI y permisos reales |
| Regresión clean verify | 823 pruebas, cero fallos, errores u omisiones; JAR construido |
| JAR entre JVM | 5 pruebas satisfactorias; E08 durable y caída tras commit remoto resuelta sin duplicar el efecto |
| Diagnóstico CI | 6 pruebas sin red satisfactorias; workflow validado con actionlint 1.7.12 |
| Artefacto y documentación | JAR sin SDK/PetClinic/fixtures; enlaces documentales válidos |

Los conteos enfocados se superponen con la regresión y no se suman. Las cinco
pruebas entre JVM se ejecutaron aparte después de construir el JAR. La base del
usuario no se utilizó. Los logs locales, ignorados por Git, son
`review-mvp03-step5.log`, `review-mvp03-step6.log`, `review-mvp03-step7.log`,
`review-mvp03-finalization-regression.log` y `review-mvp03-finalization-restart.log`.

CI utiliza el SDK público por defecto. Para un SDK privado, configurar la variable
`SDK_PRIVATE=true` y el secreto del repositorio ejecutor como indica la
[guía de seguridad](MVP-v01-security-ci.md). La validación de acceso privado solo
se ejecuta en esa rama, sin reemplazar el secreto ausente por otro token.
La nueva ejecución remota de Actions requiere subir estos cambios; no se realizó
commit ni push desde esta entrega.

Quedan completados los pasos 5–8 de esta secuencia. El siguiente incremento del
MVP v01 es la medición posterior comparable y su verificación; después corresponden
evaluación reproducible y decisión/aprendizaje/cierre humano. La continuación
parcial desde E13 y las decisiones operativas pendientes descritas arriba siguen
fuera del alcance y no deben presentarse como recuperación universal resuelta.
