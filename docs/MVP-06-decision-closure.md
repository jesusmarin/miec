# MVP-06.4–06.6 — decisión, aprendizaje y cierre

## Paso 1: contrato CYCLE-CLOSURE-V1

Secuencia autorizada: 1 contrato; 2 congruencia F02/F01; 3 evaluación operativa;
4 decisión humana; 5 aprendizaje; 6 cierre; 7 aceptación integral; 8 diseño de
nuevas rondas. Cada paso exige pruebas y resultado en consola antes del siguiente.
No se habilita reapertura E02/E04 ni se cambia F01 en esta entrega.

AUTHOR registra evaluación desde el conjunto cerrado; E08 → E09, conclusión,
admisión, evento y versión confirman o revierten juntos. Un snapshot interno previo
puede admitirse si sigue siendo coherente; la repetición HTTP con versión vieja
se rechaza y el cliente consulta el recurso. Se conserva una admisión por ciclo
en este contrato de una sola ronda.

APPROVER distinto del autor del plan y del registro operativo de evaluación
decide. La recomendación F02 es una intención estratégica sin autoridad de ejecutar.
La transición inmediata desde E09 siempre es E10. La decisión conserva alternativas,
recomendación, selección humana, motivo, identidad y reconocimiento explícito de
limitaciones. No concede EXECUTE_ACTION, no convierte RS-05 en éxito y no ejecuta
compensaciones o cambios externos.

F02-MVP-V1 recomienda COLLECT_EVIDENCE para RS-05, CLOSE_CYCLE para RS-01,
ADJUST_PLAN para RS-02/RS-04, y REQUEST_HUMAN_REVIEW para RS-03/RS-06.
COLLECT_EVIDENCE y REQUEST_HUMAN_REVIEW son intenciones admisibles. CLOSE_CYCLE
admite cierre administrativo sin declarar éxito salvo riesgo RS-06; ADJUST_PLAN
solo se admite con evaluación concluyente sin riesgo inadmisible. Las restantes
acciones quedan inadmisibles en este MVP: no hay una ruta operativa/permiso que
las haga ejecutables aquí. No se inventan beneficios cuantitativos, costes o riesgos.
El orden de alternativas es explícito y determinista, nunca una puntuación que
compense seguridad o autorización.

AUTHOR registra aprendizaje en E10: conclusión humana, condiciones y limitaciones,
con evaluación/decisión/evidencias y confianza original del evaluador. E10 → E11
es atómico con ese registro. APPROVER cierra solo si la selección humana fue
CLOSE_CYCLE y reconoce limitaciones; otras intenciones permanecen en E11, con
continuidad operativa pendiente del contrato de nuevas rondas.

E11 → E14 registra resumen, motivo, closed_at y cierre auditable en la misma
transacción de estado/evento/transición/versión. La insuficiencia EV permite cerrar
sin éxito; UNKNOWN, PENDING, EXPIRED, RUNNING o CONFLICT técnicos no resueltos no.
Se revalidan resultados del run y todos los intentos IR-009 del ciclo mediante su
proyección efectiva; una reconciliación resuelta no se confunde con incertidumbre.
Una contradicción posterior al cierre sigue visible en la consulta, sin reescribir
el hecho histórico ni reabrir automáticamente el ciclo.

Todas las mutaciones requieren versión del ciclo, identidad de Spring Security y
locks sistema → ciclo → plan → run. No se mantienen transacciones durante llamadas
externas porque este flujo solo consulta/escribe la base. Las tablas y columnas
aditivas preservarán registros históricos; el seguimiento operativo se separa de
las conclusiones y recomendaciones inmutables. No se alterarán migraciones publicadas.

## Validaciones previstas antes del código

- Cada recomendación conserva su acción y propone E09 → E10 válida para F01.
- Evaluación, admisión y transición atómicas, con rollback real de auditoría.
- Decisión única, selección admisible, roles, separación de autores y limitaciones.
- Aprendizaje/versión/evento atómicos, sin elevar confianza o evidencia.
- Cierre exige decisión de cerrar, aprendizaje y hechos técnicos confirmados.
- closed_at, evento y transición coinciden; doble cierre y versiones viejas fallan.
- Recomendaciones distintas de CLOSE_CYCLE no se ejecutan ni se cierran a ciegas.
- Concurrencia, errores HTTP/OpenAPI, regresión y recuperación completa entre JVM.

## Persistencia V017 y responsabilidades

V017 añade evaluation_admission, human_decision y cycle_closure. Extiende
decision_record únicamente con outcome_snapshot y learning con referencias,
responsable, limitaciones y evento. Las columnas nuevas son nulas en historia
anterior; no se fabrican decisiones humanas, aprendizajes o cierres históricos.
FKs compuestas conservan ciclo/evaluación/decisión/aprendizaje/evento coherentes.
Este contrato admite una evaluación y una decisión operativas por ciclo.

ClosedSetEvaluationStore conserva la conclusión original de V016. La admisión
operativa separada permite asociar el evento sin actualizar esa conclusión.
JdbcDecisionRecorder implementa DecisionRecorder y conserva la propuesta F02:
no actualiza estado ni concede permisos. La selección humana se guarda aparte.
CycleCompletionWorkflow realiza los cambios de aplicación en transacciones breves;
CycleCompletionApplicationService exige roles e identidad autenticada.

Al comprobar la incertidumbre, se bloquean todos los planes del ciclo y después
el run; los escritores IR-009 quedan serializados por su plan. Se comprueban todos
los intentos, incluidos históricos, mediante EffectiveOutcome. APPLIED, NO_EFFECT
y CANCELLED_PENDING son hechos resueltos; los demás bloquean. No se exige renovar
EXECUTE_ACTION para revisar hechos ya aplicados y no se habilitan nuevos envíos.

El aprendizaje copia evidencia y confianza de la evaluación; conditions y
limitations son declaraciones humanas adicionales, no verificaciones instrumentales.
El cierre conserva estado E14, closed_at y closure_reason en cycle y su acta
append-only. La fecha es la del mismo evento CYCLE_FINISHED, no un segundo reloj.
Los fallos de INSERT o auditoría revierten todos los pasos y versiones.

La inmutabilidad es de aplicación, no una garantía ante administradores SQL.
Una contradicción técnica posterior al cierre deja requiresReview=true sin borrar
la decisión o el cierre. No existe reapertura automática de E14 ni rollback remoto.

## API, roles y ejemplos reproducibles

Todas las rutas requieren Basic. Swagger autenticado está en
`/swagger-ui/index.html`. X-MIEC-Actor se ignora y los campos desconocidos se
rechazan. expectedVersion pertenece siempre al ciclo.

| Ruta | Rol | Efecto |
|---|---|---|
| POST `/api/cycles/{cycleId}/plans/{planId}/evaluations` | AUTHOR | Admisión y E08 → E09; 201 con Location |
| GET `/api/evaluations/{evaluationId}/recommendation` | Ambos | Vista previa solo en E09, sin autoridad ni efectos |
| POST `/api/cycles/{cycleId}/decisions` | APPROVER distinto del autor | Selección humana y E09 → E10 |
| POST `/api/cycles/{cycleId}/learning` | AUTHOR | Aprendizaje y E10 → E11 |
| POST `/api/cycles/{cycleId}/close` | APPROVER | Acta y E11 → E14 |
| GET `/api/cycles/{cycleId}/completion` | Ambos | Historia operativa completa y requiresReview actual |

Las consultas y demás mutaciones correctas responden 200. Errores: 400 comando,
referencia o reconocimiento de límites inválidos; 401 sin identidad; 403 rol o
autodecisión; 404 inexistencia; 409 versión/etapa/intención/resultado incompatible;
500 fallo interno saneado, sin SQL ni trazas en respuesta. Ante timeout consultar
completion y sus versiones antes de intentar un comando nuevo. No hay reenvío
idempotente de un POST con versión vieja: se conserva una sola transición.

1. Completar la [preparación y ejecución](MVP-03-plan-execution.md) y las
   [mediciones posteriores y conjunto cerrado](MVP-05-post-measurements.md).
2. Como AUTHOR, admitir el conjunto:

```json
{"expectedVersion":20,"setId":"UUID del conjunto cerrado","reason":"Evaluar entradas fijadas"}
```

3. Consultar la recomendación con el evaluationId devuelto y revisar snapshot,
   alternativas, suficiencia y limitaciones. Como APPROVER, seleccionar una intención:

```json
{"expectedVersion":21,"evaluationId":"UUID de evaluación","action":"CLOSE_CYCLE","acknowledgeLimitations":true,"reason":"Finalización administrativa sin éxito acreditado"}
```

4. Como AUTHOR, registrar aprendizaje sobre decisionId de completion:

```json
{"expectedVersion":22,"decisionId":"UUID de decisión","statement":"No hay evidencia suficiente para concluir mejora","conditions":"Muestra de laboratorio","limitations":"Sin verificación instrumental ni atribución causal","reason":"Registrar aprendizaje"}
```

5. Como APPROVER, confirmar el cierre:

```json
{"expectedVersion":23,"learningId":"UUID de aprendizaje","summary":"Ciclo finalizado conservando RS-05 y sus limitaciones","acknowledgeLimitations":true,"reason":"Cierre administrativo"}
```

Las versiones son ilustrativas: usar las devueltas por cada comando o GET.
Si la selección fue COLLECT_EVIDENCE, ADJUST_PLAN o REQUEST_HUMAN_REVIEW, el sistema
conserva esa intención y el aprendizaje, pero no permite cerrar ni ejecutar su
continuidad desde E11. Las nuevas rondas requieren el diseño separado del paso 8.
No seleccionar CLOSE_CYCLE para simular una continuidad o compensación.

Los esquemas OpenAPI de CycleHumanDecisionCommand y MeasurementComparisonOperator
tienen identidades distintas de la aprobación de permisos y las métricas numéricas.

## Matriz de aceptación

| Regla | Componente | Prueba |
|---|---|---|
| Estrategia separada de transición | DefaultDecisionEngine | DefaultDecisionEngineTest, cada DecisionAction contra F01 |
| Política y admisibilidad | MvpAlternativePolicy | MvpAlternativePolicyTest |
| Historia V016 conservada | V017 | CycleCompletionMigrationTest |
| Admisión atómica y concurrente | CycleCompletionWorkflow.evaluate | CycleCompletionWorkflowTest |
| Decisión humana y propuesta original | JdbcDecisionRecorder + decide | CycleCompletionWorkflowTest |
| Aprendizaje sin elevar confianza | learn | CycleCompletionWorkflowTest |
| Cierre, fecha, rollback e incertidumbre | close + requireHealthy | CycleCompletionWorkflowTest |
| Roles, entrada estricta, errores y OpenAPI | Controladores/fachadas | CycleCompletionApiTest |
| Historia completa recuperada del JAR | HTTP real y H2 propio | MvpLifecycleRestartIT |

```powershell
.\mvnw.cmd '-Dtest=DefaultDecisionEngineTest,MvpAlternativePolicyTest,CycleCompletionMigrationTest,CycleCompletionWorkflowTest,CycleCompletionApiTest' test
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

Los IT usan procesos y archivos propios; no operan sobre PetClinic ni la base del
usuario. El escenario integral del JAR pasa por APIs desde objetivo/línea base hasta
E14 y compara las respuestas históricas después del reinicio. Mantiene un solo
efecto en el destinatario. H2 no acredita PostgreSQL, cortes de energía o producción.

## Resultado de la secuencia

Validado el 2026-11-03. Pasos 1–7 implementados/documentados y validados; paso 8
entregado como [diseño de nuevas rondas](MVP-next-rounds-design.md), conforme al
alcance previsto, sin habilitar reapertura ni publicar una migración futura.

| Paso | Resultado |
|---|---|
| 1 Contrato | CYCLE-CLOSURE-V1, roles, guardas y límites definidos antes del código |
| 2 F02/F01 | 24 pruebas enfocadas satisfactorias, incluidas todas las acciones contra F01 |
| 3 Evaluación | 4 pruebas enfocadas: admisión, concurrencia, rollback y V017 histórica |
| 4 Decisión | 18 pruebas enfocadas de política, motor y decisión humana |
| 5 Aprendizaje | 7 pruebas del workflow satisfactorias |
| 6 Cierre | 12 pruebas del workflow; closed_at, fallo del acta, otras incertidumbres y concurrencia |
| 7 HTTP/aceptación | 2 pruebas HTTP, regresión de 878 y 6 pruebas del JAR entre JVM; cero fallos, errores u omisiones |
| 8 Nuevas rondas | Modelo, guardas, ruta de desarrollo y criterios de aceptación documentados; sin código de reapertura |

Los grupos enfocados se superponen con la regresión y no se suman. Se agregaron
18 pruebas a la base de 860; se amplió uno de los seis IT existentes para recorrer
las APIs hasta E14 y verificar su historia después del reinicio.

El JAR contiene workflow, controladores, política, registrador y V017, sin SDK,
PetClinic o fixtures de prueba. Los enlaces y DisplayName de clases nuevas fueron
verificados. Los logs locales ignorados por Git son review-mvp06-closure-step2.log,
review-mvp06-closure-step3.log, review-mvp06-closure-step4.log,
review-mvp06-closure-step5.log, review-mvp06-closure-step6.log,
review-mvp06-closure-step7-http.log, review-mvp06-closure-regression.log y
review-mvp06-closure-restart.log.

No se operó contra bases o destinatarios del usuario. El próximo CI remoto requiere
subir los cambios; no se realizó commit ni push. El recorrido administrativo hasta
E14 está acreditado en el alcance local documentado; no implica evaluación causal,
verificación instrumental, continuidad implementada o certificación productiva.
