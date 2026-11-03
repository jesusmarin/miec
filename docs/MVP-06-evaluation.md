# MVP-06 — evaluación, pasos 06.1–06.3

Actualización: [06.4–06.6](MVP-06-decision-closure.md) incorpora creación operativa,
decisión humana, aprendizaje y cierre. El resto conserva el alcance histórico de
06.1–06.3; sus referencias a funciones pendientes se interpretan con esta actualización.

## 06.0 Base validada

MVP-05 está integrado en master 90172c4. El workflow 41699066895 terminó success.
El commit local 23d57ce tiene el mismo contenido de archivos que ese commit de
integración. Se conserva la rama de trabajo y la base H2 del usuario.

## 06.1 EVALUATION-POLICY-V1

Se implementan política, evaluación pura y persistencia interna/consulta. La API
de creación operativa y E08 → E09 corresponden a 06.4; recomendación/decisión y
aprendizaje/cierre a 06.5–06.6. Ninguno de estos pasos ejecuta cambios externos.

Las entradas son exclusivamente las muestras y revisiones fijadas por
measurement_set EXPLICIT_PAIR_V1, con criterio, unidad, método, ventanas y fechas.
No se eligen mediciones nuevas ni la última revisión. Snapshot completo y huella
canónica quedan persistidos para reproducir la conclusión aunque después cambien
otros datos. Antes de guardar se revalidan ciclo E08, versiones, plan, ejecución,
contenido y hechos IR-009. Los datos inconsistentes se rechazan, sin repararlos.

### Comparación decimal y agregación

BigDecimal.compareTo compara sin epsilon y sin depender de escala. GT/GTE/LT/LTE/EQ
conservan su significado exacto. Por indicador se informa separadamente:
cumplimiento base/post, delta post−base y cambio favorable/neutro/desfavorable.
Para GT/GTE es favorable aumentar; para LT/LTE disminuir; para EQ se compara la
distancia absoluta al umbral. No se calculan porcentajes (base cero o negativa),
conversiones ni agregaciones estadísticas. Un par es una comparación de muestras,
no prueba causal ni estimación de efecto poblacional.

Con evidencia suficiente, la clasificación numérica se define así, por orden:

1. Todos los indicadores cumplen el umbral posterior: RS-01 FAVORABLE.
2. Alguno cumple o alguno cambia favorablemente: RS-02 PARTIAL.
3. Ninguno cumple/mejora y alguno empeora: RS-04 UNFAVORABLE.
4. Restantes, sin cambio: RS-03 NEUTRAL.

RS-01 significa cumplimiento de criterios, no necesariamente mejora: los detalles
conservan si antes ya cumplían o si hubo deterioro dentro del umbral. Para conjuntos
mixtos, PARTIAL conserva todos los cambios adversos, sin ocultarlos en promedios.
Sin evidencia evaluable/suficiente se impone RS-05 INCONCLUSIVE, aunque se puedan
calcular diferencias. RS-06 requiere fundamentos de riesgo verificables: V1 no
recibe datos de riesgo suficientes y no lo infiere de un resultado desfavorable.
No se inventa un indicador de riesgo aportado por el cliente.

### SOURCE-QUALITY-V1: suficiencia explícita

| Situación | Suficiencia |
|---|---|
| No hay pares o una revisión seleccionada rechaza una muestra | EV-0 NOT_EVALUABLE |
| Muestras humanas UNVERIFIED, con revisiones ausentes o aceptadas con limitaciones | EV-1 INSUFFICIENT |
| Fuente instrumental autenticada y método validado | No acreditados por MVP-05; requieren otro contrato/política |

No hay asignación automática EV-2/EV-3/EV-4 por tener dos firmas, cerrar el conjunto,
aprobar una revisión o completar IR-009. El clasificador numérico contempla las
categorías concluyentes para suficiencia acreditada; el adaptador productivo V1
revalida SOURCE-QUALITY-V1 y no acepta una suficiencia superior declarada por otro
llamador. Por tanto, los conjuntos humanos actuales producen RS-05 con métricas
numéricas disponibles y confianza LOW (EV-1) o UNDETERMINED (EV-0).

Limitaciones originales y de revisión se conservan junto con la falta de
comparabilidad metodológica acreditada y ausencia de evaluación causal.
La confianza nunca eleva la suficiencia. La política no interpreta texto libre
como prueba instrumental, autorización o reglas ejecutables.

### Determinismo, identidad y reloj

El evaluador puro no accede a Spring, JDBC, red, reloj ni UUID aleatorios. Recibe
evaluatedAt explícito; el servicio interno lo toma del reloj de base. La identidad
es estable por conjunto y versiones de ambas políticas. La huella de entrada usa
campos tipados, orden de indicadores, decimales normalizados y texto UTF-8 con
longitudes explícitas. El instante de evaluación es metadata y no cambia esa huella.
Mismas entradas, políticas e instante producen el mismo reporte completo.

ResultEvaluator se reutiliza con un snapshot numérico opcional en su request,
conservando el constructor histórico. La implementación nueva rechaza solicitudes
sin snapshot: no interpreta observedValue o baselineReference como comandos/SQL/JSON
ejecutable. La versión compuesta de criterios es una huella de sus definiciones.

## Persistencia prevista para 06.3

V016 ampliará evaluation, no creará otro motor de conclusiones. Las extensiones
serán nulas para filas históricas. Una conclusión por set y versiones de política;
FK compuesta al set/run/plan/ciclo; snapshot completo, huella, responsable y fecha.
evaluation_evidence conserva referencias a las evidencias seleccionadas.
El guardado interno exige una transacción exterior, para que 06.4 pueda confirmar
evaluación/evento/transición/versión conjuntamente. No se publica POST de creación
ni se cambia E08 en esta entrega. Solo habrá consulta autenticada y de solo lectura.
La inmutabilidad es de aplicación; restricciones SQL de producción siguen pendientes.

## Pruebas previstas antes del desarrollo

06.2: operadores y límites decimales, escala, base cero/negativa, EQ por distancia,
mezcla de indicadores, política EV conservadora, revisión rechazada/ausente,
rechazo de suficiencia inventada, orden estable y repetición sin reloj.
06.3: V015 → V016 sin modificar historia; snapshot/evidencia recuperables;
repetición y concurrencia sin duplicar; rollback de escritura; rechazo de contexto,
versión o ejecución contradictoria; API GET autenticada y OpenAPI; reinicio del JAR.

## 06.2 Implementación

ClosedSetEvaluation contiene snapshots tipados y codificación canónica;
MeasurementEvidencePolicy aplica SOURCE-QUALITY-V1; DeterministicResultEvaluator
implementa ResultEvaluator y produce Report con EvaluationResult y métricas.
ResultEvaluationRequest conserva su constructor histórico y añade numericSnapshot.
El clasificador numérico se ensaya separadamente con suficiencia hipotética
acreditada; esa prueba no habilita EV-2/EV-3 para fuentes humanas en producción.

No se cambia DefaultDecisionEngine ni se corrigen sus propuestas de transición
en estos pasos: recomendación estratégica y transición inmediata se separarán en
06.5. F01 no se amplía. RS-06 no se fabrica sin una política de riesgo respaldada.

## 06.3 Persistencia y API interna

EvaluationSnapshotLoader reconstruye solo las referencias fijadas por el conjunto,
usando MeasurementQueries. Comprueba objetivo, criterios, unidades, evidencias y
revisiones seleccionadas. ClosedSetEvaluationStore bloquea sistema → ciclo → plan
→ run, exige E08/versiones y confirma hechos IR-009 vigentes. El snapshot captura
valores, fechas, autoría, origen, calidad original, método y revisiones completas.
La lectura histórica no consulta esos valores otra vez ni actualiza la conclusión.

V016 amplía evaluation con measurement_set_id/run_id/plan_id, versiones de política,
input_fingerprint, evaluation_snapshot y recorded_by. La FK compuesta exige el mismo
set/run/plan/ciclo; las restricciones anteriores mantienen ciclo/objetivo/evidencia.
La relación evaluation_evidence registra todas las evidencias seleccionadas. No se
elige un único baseline_id para representar un conjunto de varios indicadores.

La extensión es toda nula (historia anterior) o completa. Se conserva una fila por
set y ambas políticas. Repetir el guardado devuelve la fila original y su fecha;
un cambio de huella se rechaza. Cada política futura debe conservar la posibilidad
de leer snapshots originales sin reinterpretarlos con sus reglas nuevas.

persist es interno al paquete y al servicio, con propagación MANDATORY: requiere
una transacción existente; no es un método de creación en la fachada pública.
El timeout y la auditoría de la transacción operativa quedarán bajo el caso de uso
06.4. La transacción del llamador puede revertir evaluación y relaciones si falla
cualquier paso posterior. Aquí no se registra una transición ni un evento de
evaluación operativa antes de implementar ese caso de uso completo.

| Ruta | Acceso | Semántica |
|---|---|---|
| GET `/api/evaluations/{evaluationId}` | AUTHOR/APPROVER | Snapshot y conclusión originales |
| GET `/api/measurement-sets/{setId}/evaluations` | AUTHOR/APPROVER | Lista de conclusiones del conjunto; vacía si no se guardaron |

Swagger autenticado documenta ambas consultas. Sin Basic: 401; rol insuficiente:
403; identificador inválido: 400; recurso inexistente: 404. Una evaluación histórica
sin snapshot devuelve 409 EVALUATION_SNAPSHOT_UNAVAILABLE; no se le inventa un set.
No existe POST de creación: las pruebas rechazan intentarlo sobre estas rutas.
No hay cuerpo para elegir EV, fuente, criterio o política desde el cliente.

Un conjunto de MVP-05 puede consultarse ahora con lista vacía: guardar una
evaluación mediante HTTP requiere el próximo incremento 06.4. No se recomienda
insertarla manualmente por SQL. La persistencia de esta entrega se demuestra desde
el API interno en pruebas y luego por GET del JAR real en otra JVM.

## Matriz de trazabilidad y comandos

| Invariante | Componente | Prueba |
|---|---|---|
| Decimales y separación umbral/cambio | DeterministicResultEvaluator | DeterministicResultEvaluatorTest |
| EV humano no concluyente | MeasurementEvidencePolicy + revalidación | DeterministicResultEvaluatorTest |
| Identidad/huella/orden sin reloj | ClosedSetEvaluation | DeterministicResultEvaluatorTest |
| Historia intacta | V016 | EvaluationMigrationTest |
| Snapshot único, rollback, concurrencia | ClosedSetEvaluationStore | ClosedSetEvaluationStoreTest |
| Reconstrucción del contexto seleccionado | EvaluationSnapshotLoader | ClosedSetEvaluationStoreTest |
| Lectura original y seguridad/OpenAPI | EvaluationApplicationService/Controller | EvaluationApiTest |
| Cambio de proceso sin reconstruir datos | JAR y H2 propio | ClosedSetEvaluationRestartIT |

```powershell
.\mvnw.cmd '-Dtest=DeterministicResultEvaluatorTest,ResultEvaluationRequestTest,EvaluationResultTest' test
.\mvnw.cmd '-Dtest=EvaluationMigrationTest,ClosedSetEvaluationStoreTest,EvaluationApiTest' test
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

Las pruebas nuevas tienen Javadoc y @DisplayName. El IT nuevo guarda por el servicio
interno dentro de una transacción en un contexto de pruebas, cierra todas sus
conexiones y arranca el JAR en otra JVM contra su archivo aislado. No añade fixtures
ni una ruta de creación al JAR. CI ejecuta ambos IT explícitamente después de verify.
No se usa la base del usuario ni se acreditan PostgreSQL o verificación instrumental.

## Resultado de la entrega 06.1–06.3

Validado el 2026-11-03.

| Puerta | Evidencia |
|---|---|
| 06.0 | master 90172c4 integrado y workflow 41699066895 success; sin diferencias de archivos con la base local |
| 06.1 | EVALUATION-POLICY-V1 y SOURCE-QUALITY-V1 definidos antes del código y pruebas |
| 06.2 | 53 pruebas enfocadas correctas: 10 nuevas y 43 de compatibilidad de contratos |
| 06.3 | 18 pruebas enfocadas correctas, incluidos evaluador, migración, store y HTTP |
| Regresión clean verify | 860 pruebas, cero fallos, errores u omisiones; JAR construido |
| JAR entre JVM | 6 pruebas correctas, incluida recuperación del snapshot de evaluación desde otro proceso |
| Artefacto/documentación | Evaluador/store/controlador/V016 presentes; sin SDK/PetClinic/fixtures; enlaces y DisplayName correctos |
| Workflow | actionlint correcto; incluye ambos IT después de verify |

Los conteos enfocados se superponen con la regresión y no se suman. Se agregaron
18 pruebas a la regresión de 842 y un IT a las cinco pruebas existentes del JAR.
La prueba histórica de V015 se fijó explícitamente a target 015 para conservar
su alcance al introducir V016. No se modificaron migraciones publicadas.

Logs locales ignorados por Git: review-mvp06-step2.log, review-mvp06-step3.log,
review-mvp06-regression.log y review-mvp06-restart.log. La publicación de este
desarrollo y su CI remoto requieren commit/push; no se realizaron desde esta entrega.

Los tres pasos solicitados quedan completados. Con datos humanos actuales, la
conclusión productiva conserva RS-05 con métricas numéricas y limitaciones visibles;
no se promete un resultado concluyente sin nuevas fuentes/políticas acreditadas.

Pendientes expresamente fuera de esta entrega:

- 06.4: creación operativa autenticada y atomicidad evaluación/evento/E08 → E09/versiones.
- 06.5: recomendación estratégica, decisión humana y transición F01 inmediata válida.
- 06.6: aprendizaje, continuidad y cierre E14 con recuperación del historial.
