# MVP-07 — Rondas, contrato y conclusión operativa

Actualización: [07.4–07.8](MVP-07-continuity.md) implementa continuidad aprobada y
contextos separados por ronda. Las secciones siguientes describen el alcance
histórico de 07.1–07.3; sus pendientes de continuidad se desarrollan en esa guía.

## Entrega 07.1–07.3

Base: commit 0d07ed5, CI 41718813491 terminado correctamente. Esta entrega
no habilita continuidad ni una segunda ronda. E14 sigue siendo terminal.

ROUND-CONTEXT-V1 identifica explícitamente una ronda inicial en E11 a partir de
una evaluación admitida, decisión y aprendizaje operativos inequívocos. AUTHOR
registra el contexto con expectedCycleVersion. No hay asignación automática de
históricos: V018 deja sus tablas vacías y conserva V001–V017 sin cambios.

El contexto conserva la evaluación completa: objetivos, versiones de criterios,
valores decimales, unidades, muestras, revisiones y políticas originales. También
conserva decisión y aprendizaje; no reconstruye criterios históricos desde el
indicador actual. Las referencias relacionales validan ciclo, plan, ejecución,
conjunto, evaluación, decisión y aprendizaje. Una sola ronda activa por ciclo.

PLAN-COMPLETION-V1 registra, mediante APPROVER distinto del autor, la conclusión
operativa del plan. Requiere E11, versiones vigentes del ciclo/ronda, ejecución
aplicada y ausencia de incertidumbres IR-009. Conclusión, auditoría y versiones
se confirman o revierten juntas. Conserva APPROVED, huella, permisos e historia;
no equivale a éxito, evidencia suficiente, autorización o cierre del ciclo.
Un plan concluido deja de contar como activo y no admite nuevos despachos MVP.
Su ronda permanece activa hasta el cierre E14 o el futuro comando atómico de continuidad.

Si se elige el cierre E14 existente, una ronda explícita exige antes su acta de
plan. El cierre concluye también esa ronda y elimina su marcador activo, en la
misma transacción que closed_at y la auditoría. Ciclos sin ronda conservan el
contrato de cierre previo.

## APIs y errores

- POST /api/cycles/{cycleId}/rounds/initial — AUTHOR, versión del ciclo y motivo.
- GET /api/cycles/{cycleId}/rounds — AUTHOR/APPROVER, contexto histórico.
- POST /api/cycles/{cycleId}/rounds/{roundId}/plan-completion — APPROVER,
  versiones del ciclo/ronda y motivo.

400: comando inválido. 401/403: identidad o separación de funciones. 404:
referencia ausente. 409: versión, etapa, ronda existente o ejecución incierta.
Los actores y las políticas provienen del servidor. OpenAPI exige miecBasic.

## Continuidad pendiente (07.4–07.8)

ROUND-CONTINUITY-V1 deberá distinguir intención estratégica de transición F01:
COLLECT_EVIDENCE → E02; ADJUST_PLAN → E04 con diagnóstico elegible; revisión
humana no inventa E05; CLOSE_CYCLE usa el cierre E14 existente. Ajustar con RS-05
requerirá decisión humana explícita, limitaciones y política versionada; nunca
elevará EV o confianza. F02-MVP-V1 permanece sin cambios en esta entrega.

Contrato reservado de continuidad: AUTHOR solicitará una intención cerrada con
motivo, decisión/aprendizaje y versiones de ciclo/ronda; APPROVER distinto del
autor confirmará con reconocimiento explícito de limitaciones. No se aceptarán
estados arbitrarios. Una decisión previa CLOSE_CYCLE no autorizará continuar:
cambiar intención exigirá una nueva resolución auditada, nunca editar la anterior.
La excepción administrativa ADJUST_PLAN con RS-05 requerirá su propia política
instalada y diagnóstico elegible; no cambiará retroactivamente F02-MVP-V1 ni
convertirá una alternativa actualmente inadmisible en permiso de ejecución.
Ante incertidumbre IR-009 o ausencia de contexto elegible se rechazará continuar.
Cerrar la ronda anterior, crear la siguiente y transicionar se confirmarán juntos;
si existe acta previa del plan se revalidará, sin duplicarla. No se reutilizarán
automáticamente líneas base, permisos, claves idempotentes u ownership.

Antes de abrir otra ronda: aislar observaciones/diagnósticos, sustituir unicidades
por ciclo por unicidades por ronda y probar continuidad, autorizaciones nuevas,
evaluación y dos rondas completas con reinicio. No retirar restricciones primero.

## Validación reproducible

```powershell
.\mvnw.cmd '-Dtest=RoundMigrationTest,RoundWorkflowTest,CycleCompletionApiTest' test
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

RoundMigrationTest valida actualización V017→V018, ausencia de asignaciones
implícitas y rechazo de referencias inexistentes. RoundWorkflowTest valida
conservación de aprobación/huella/contexto, versiones, separación de identidades,
concurrencia de registro y conclusión, reversión de auditoría/acta, restricciones
SQL, incertidumbre tardía, criterios históricos y cierre atómico de la ronda.
CycleCompletionApiTest prueba Basic, roles, entrada estricta, OpenAPI y recorrido
HTTP. MvpLifecycleRestartIT conserva ronda, acta y contexto entre JVM distintas,
sin duplicar el efecto remoto. Las bases son aisladas; no se usa data/miecdb.mv.db.

La inmutabilidad aquí es contractual de aplicación. Los privilegios SQL, el
endurecimiento productivo y la equivalencia PostgreSQL siguen pendientes.

Resultado local del incremento: 889 pruebas de regresión y 6 pruebas entre JVM,
sin fallos ni errores. Se comprobó recuperación de ronda/acta/contexto y un único
efecto remoto. Los esquemas OpenAPI nuevos usan RoundContextV1 y
RoundHistoricalSnapshotV1 para conservar los modelos previos de medición y evaluación.
La validación de las rutas HTTP y de esos esquemas se ejecuta también por separado.
