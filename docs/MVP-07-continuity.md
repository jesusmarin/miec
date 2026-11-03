# MVP-07.4–07.8 — Continuidad entre rondas

## Secuencia y contrato

0. Corregir actividad lógica de planes en ciclos cerrados.
1. V019: vínculos de muestras, observaciones, diagnóstico y plan por ronda.
2. Continuidad con solicitud AUTHOR y aprobación APPROVER independiente.
3. Nuevo plan, acciones y permisos; nada se transfiere automáticamente.
4. V021: admisión y decisión por ronda, con protección del contexto legado.
5. Dos rondas completas por HTTP, concurrencia, rollback y reinicio del JAR.

ROUND-CONTINUITY-V1 permite COLLECT_EVIDENCE hacia E02. ADJUST_PLAN hacia E04
exige selección explícita de muestras y diagnóstico nuevo, aportados en E11 por
AUTHOR y revisados al aprobar. No se copia la línea base anterior. El cliente
no proporciona estados, actores, adaptadores, URLs o credenciales en la continuidad.
Una decisión CLOSE_CYCLE no permite continuar. REQUEST_HUMAN_REVIEW requiere una
resolución humana nueva y auditada que seleccione COLLECT_EVIDENCE o ADJUST_PLAN;
la decisión original permanece inmutable. La resolución administrativa de RS-05
no declara éxito, causalidad ni suficiencia EV y no modifica F02-MVP-V1.

La solicitud referencia la ronda, decisión y aprendizaje originales y conserva
su motivo, tipo y versiones. Al aprobar se revalidan versiones, conclusión del
plan y todos los intentos IR-009 del ciclo; incertidumbre o contradicción bloquean.
El cierre de ronda anterior, nueva ronda, contexto y transición F01 confirman
juntos. Un único sucesor y una sola ronda activa. E14 nunca se reabre.

Las observaciones nuevas no pueden ser anteriores a la finalización técnica de
la intervención precedente. Se exige precisión de microsegundos. La rama E04
registra como autor de las muestras al solicitante y como responsable de la
resolución al aprobador; toda fuente humana conserva UNVERIFIED. El diagnóstico
es nuevo y queda enlazado a la solicitud mediante la ronda aprobada.

## Historial y consultas

Las mediciones y diagnósticos nuevos se vinculan a la ronda actual. Las lecturas
de planes antiguos usan su propia ronda. El contexto anterior se consulta por
roundId y sus evaluaciones se recuperan desde snapshots, sin recalcularlas.
V019 solo asigna antecedentes ya acreditados por round_context de V018.
V021 sustituye unicidad por ciclo por unicidad por ronda y conserva una única
admisión/decisión legada sin ronda por ciclo. No se inventan rondas históricas.

V020 crea solicitudes; V021 es una migración Java de Flyway que identifica las restricciones
autogeneradas de H2 desde el catálogo y retira únicamente las de cycle_id.
Primero instala las restricciones de reemplazo. PostgreSQL requiere una validación
propia y no está acreditado por esta migración. V001–V018 no se modifican.

Al registrar explícitamente la ronda inicial se asigna el ámbito de sus recibos;
sus actores, motivos, fechas, eventos, decisiones y snapshots originales se conservan.
Aprender en una ronda sucesora congela automáticamente su round_context; no se debe
volver a llamar a rounds/initial. La conclusión del plan sigue siendo un acto
APPROVER separado. El cierre E14 exige el acta de ese plan y concluye su ronda.

## API autenticada

Swagger conserva miecBasic. Los actores provienen exclusivamente de la identidad
del servidor. Las respuestas de creación/aprobación administrativa son HTTP 200.

| Ruta bajo /api/cycles/{cycleId} | Rol | Responsabilidad |
|---|---|---|
| POST /rounds/{roundId}/continuations | AUTHOR | Congelar solicitud; incrementar versión del ciclo sin salir de E11 |
| GET /rounds/continuations/{requestId} | AUTHOR/APPROVER | Recuperar entrada, política, resolución y sucesora |
| POST /rounds/continuations/{requestId}/approve | APPROVER independiente | Revalidar contexto y abrir la siguiente ronda |
| GET /rounds/{roundId}/measurement | AUTHOR/APPROVER | Consultar muestras de esa ronda |
| GET /rounds/{roundId}/completion | AUTHOR/APPROVER | Consultar sus recibos de evaluación, decisión, aprendizaje y cierre |

Las rutas existentes de medición y completion consultan la ronda actual. Las de
planes y resultados conservan su plan/run, y la consulta de contexto del plan usa
su ronda histórica. Un plan anterior no admite solicitudes ni despachos nuevos.
La versión de ciclo en estas respuestas es la actual; el snapshot en rounds es
la copia histórica inmutable. requiresReview es una proyección técnica actual.

Ejemplo de solicitud de observación (tomar IDs y versiones de GET /rounds):

```json
{
  "expectedCycleVersion": 20,
  "expectedRoundVersion": 1,
  "decisionId": "UUID-decision-de-esta-ronda",
  "learningId": "UUID-aprendizaje-de-esta-ronda",
  "mode": "COLLECT_EVIDENCE",
  "acknowledgeLimitations": true,
  "reason": "Recoger nuevas observaciones sin declarar éxito",
  "baselines": []
}
```

La aprobación lleva expectedCycleVersion y expectedRoundVersion de la solicitud,
expectedRequestVersion, acknowledgeLimitations=true y reason. Si alguien cambia
el contexto después de solicitar, el aprobador debe rechazar la captura obsoleta y
el autor crear una solicitud nueva. PENDING es historial, no garantía de elegibilidad.

Para ADJUST_PLAN se proporcionan diagnosis no vacío y baselines con exactamente
un objeto por indicador: indicatorId, value decimal, unit, source y measuredAt.
No se aceptan UUIDs de muestras anteriores. Los hechos nuevos y el diagnóstico
se instalan al aprobar dentro de la misma transacción que E04 y la nueva ronda.

401/403: identidad, rol o separación de funciones. 400: unidades, fechas, campos
desconocidos, referencias o entradas incompletas. 404: ciclo/ronda/solicitud ajenos.
409: versiones, solicitud obsoleta, intención de cierre, ronda concluida o
incertidumbre IR-009. Un fallo de persistencia devuelve error saneado y revierte
estado, versiones, evento, sucesora y resolución. GET permite recuperar el resultado
de una aprobación cuya respuesta se haya perdido, sin repetirla.

## Pruebas de aceptación

Pruebas para actividad de ciclos cerrados, migración con historia, aislamiento de
muestras y diagnóstico, permisos nuevos, rechazo de referencias cruzadas, roles,
versiones, idempotencia administrativa, concurrencia, incertidumbre tardía,
rollback después de abrir ronda y conservación de dos contextos al reiniciar.

## Ejecución reproducible y alcance

```powershell
.\mvnw.cmd '-Dtest=RoundWorkflowTest,RoundIsolationMigrationTest,RoundReceiptMigrationTest,RoundContinuationTest,CycleCompletionApiTest' test
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

La prueba del JAR usa bases en target y un destinatario HTTP de contrato con
deduplicación durable. Reinicia con una solicitud PENDING, la aprueba en otra JVM,
completa la segunda ronda y vuelve a reiniciar. Debe conservar ambos contextos
y exactamente dos efectos, uno por intervención, rechazando repeticiones.
El caso de caída real tras commit remoto sigue comprobando recuperación sin reenvío.
No se usa la base local data/miecdb.mv.db ni se modifica SDK o PetClinic.

Esta entrega amplía el MVP local. No acredita causalidad, fuentes instrumentales,
continuación parcial de un plan fallido, suspensión/aborto operativos completos,
PostgreSQL, TLS, privilegios SQL, copias de respaldo o preparación productiva.

## Resultado local de la entrega

- Regresión completa: **903 pruebas**, sin errores, fallos ni omisiones.
- Comprobación adicional posterior: **1 prueba** de actualización V018 → V021 con
  el contexto de una ronda real, conservando snapshot, plan y recibos originales.
- JAR: **6 pruebas entre JVM**, todas satisfactorias. El recorrido de continuidad
  usa tres JVM: solicitud pendiente, aprobación/segunda ronda y consulta final;
  conserva dos efectos remotos, uno por intervención, sin duplicados.
- HTTP/OpenAPI: tres pruebas satisfactorias, incluidas en la regresión; roles,
  entrada estricta, esquemas sin colisiones y cierre integral de dos rondas.

La prueba adicional se ejecuta con
`-Dtest=RoundContinuationTest#upgradesRegisteredContext`. El conjunto de pruebas
ahora contiene 904 casos de regresión; la ejecución completa anterior fue de 903
y el caso agregado después se validó por separado. Los archivos están preparados
para revisión y commit; esta validación local no sustituye el CI posterior al push.
