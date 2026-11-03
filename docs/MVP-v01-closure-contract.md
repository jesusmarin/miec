# Contrato de cierre del MVP v01 — incremento 1

Actualización de implementación: [decisión, aprendizaje y cierre](MVP-06-decision-closure.md)
habilita el recorrido administrativo hasta E14 con evaluación conservadora, sin
declaración implícita de éxito. Las nuevas rondas y recuperación operativa ampliada
siguen pendientes; las secciones originales conservan diagnóstico histórico.

Actualización MVP-06.1–06.3: [políticas, evaluador puro y guardado interno](MVP-06-evaluation.md)
implementados; quedan creación operativa/E08 → E09, decisión y cierre (06.4–06.6).
Las secciones originales conservan diagnóstico histórico y contrato de destino.

Actualización MVP-05: [mediciones posteriores](MVP-05-post-measurements.md) incorpora
registro E08, revisión humana separada y selección explícita inmutable. Evaluación
productiva y decisión/aprendizaje/cierre siguen pendientes. El contenido original
de diagnóstico conserva contexto histórico, no debe leerse como estado actual.

Estado actualizado: [finalización técnica y recuperación](MVP-03-finalization-recovery.md)
implementan E07/E13 → E08 y consulta/reconciliación APPLIED autorizada. No incorporan
continuación parcial desde E13, NO_EFFECT por HTTP ni suspensión/aborto operativo.
Medición posterior, evaluación y cierre siguen pendientes. Las secciones originales
de este contrato describen el destino y el diagnóstico histórico, no un cierre total.

Estado: contrato base de desarrollo definido para esta secuencia. Este
documento no declara implementadas las capacidades futuras de los incrementos 3–8.
Versión del contrato: `MVP-CLOSURE-01`.

Actualización de implementación: los incrementos 3.1, 3.2 y 3.3 se describen en
[MVP-03-plan-execution.md](MVP-03-plan-execution.md). El estado APPLIED de coordinación
no mueve todavía el ciclo a E08; la finalización, reconciliación remota y evaluación
siguen pendientes de los incrementos 4–8. El resto del documento conserva el contrato
de destino y sus criterios de aceptación, sin declarar cerradas esas capacidades.

## Objetivo y frontera

El MVP v01 debe demostrar un ciclo trazable: sistema → ciclo → objetivo medible →
línea base → diagnóstico → plan → autorización humana → ejecución controlada →
medición posterior → evaluación → decisión → aprendizaje → continuidad o cierre.
El ámbito inicial es una instancia local, con usuarios configurados, H2 y acciones
de un catálogo de adaptadores configurados en el servidor. No acredita un despliegue
productivo, causalidad de la mejora ni idempotencia universal de cualquier destinatario.

Hoy están implementadas la preparación, la medición de línea base y la aprobación
hasta E06, además del núcleo IR-009 independiente. La unión del ciclo con ese núcleo,
la medición posterior y el cierre por API siguen pendientes. El presente incremento
solo fija su contrato; el incremento 2 unifica seguridad y añade CI.

## Responsabilidades y contratos existentes

| Componente | Responsabilidad | Límite |
|---|---|---|
| `LifecycleService` / `Cycle.isValidTransition` | Persistencia del ciclo, versión, eventos y regla F01 | No ejecutan efectos externos |
| `MeasurementWorkflow` | Objetivos numéricos y líneas base; acta de cierre | Un reporte humano conserva UNVERIFIED |
| `InterventionWorkflow` | Plan inmutable, permisos por acción y decisiones humanas | Aprobar no ejecuta ni prueba mejora |
| `IdempotentActionExecutionService` | Operación durable y ejecución idempotente protegida | Necesita un adaptador que cumpla el contrato del destinatario |
| `AuthorizedActionExecutionService` | Intentos, autorización, propiedad y heartbeat | Prohíbe una transacción exterior activa |
| Servicios de evidencia/reconciliación de IR-009 | Resolución trazable de resultados de ejecución | Aplicación del efecto no acredita éxito del objetivo |
| `ResultEvaluator` | Contrato de comparación y clasificación RS | Implementación productiva pendiente; no muta ciclo ni concede permisos |

La futura fachada de ejecución delegará en IR-009. No se creará otra tabla o motor
de intentos, leases, claves idempotentes ni reconciliación. Los objetos PetClinic
permanecen como integración experimental; el catálogo productivo no dependerá de ellos.

## Estados, guardas y evidencias de aceptación

Las transiciones siguientes existen en F01. Sus casos de uso finales se construirán
en los incrementos 3–8; la existencia de una transición de dominio no equivale a una API.

| Origen → destino | Guarda requerida para el MVP | Evidencia auditable |
|---|---|---|
| E06_PREPARADO → E07_EJECUTANDO | Plan aprobado del mismo ciclo, versión vigente, catálogo disponible y permisos válidos para sus acciones | Identidad autenticada, plan/huella, orden y registro durable de coordinación |
| E07_EJECUTANDO → E08_VERIFICANDO | Todas las acciones con efecto aplicado confirmado y sin contradicciones | Operaciones, intentos y referencias de resultado recuperables |
| E07_EJECUTANDO → E13_RECUPERANDO | Resultado incierto, interrupción o discrepancia entre ejecución y coordinación | Último estado durable, motivo y referencias de IR-009 |
| E07_EJECUTANDO → E12_SUSPENDIDO | Fallo definitivo conocido o impedimento que exige intervención humana | Hechos conocidos y acciones ya aplicadas, sin presumir rollback externo |
| E13_RECUPERANDO → E08_VERIFICANDO | Reconciliación confirma todas las acciones aplicadas | Resoluciones y ausencia de contradicciones |
| E13_RECUPERANDO → E12_SUSPENDIDO / E15_ABORTADO | No es seguro continuar; decisión explícita autorizada | Motivo, identidad y resultado parcial preservado |
| E08_VERIFICANDO → E09_DECIDIENDO | Evaluación reproducible con sus limitaciones; admite conclusión de insuficiencia | Mediciones, evaluación de evidencia, política y clasificación RS |
| E08_VERIFICANDO → E02_OBSERVANDO | Se requieren nuevas observaciones | Motivo y nueva ronda identificable; contexto previo intacto |
| E09_DECIDIENDO → E10_APRENDIENDO | Decisión humana registrada sobre la evaluación | Recomendación, decisión, motivo e identidad |
| E10_APRENDIENDO → E11_CONTINUANDO | Aprendizaje explícito, incluso si no hubo mejora | Conclusión y limitaciones vinculadas al ciclo |
| E11_CONTINUANDO → E14_FINALIZADO | Cierre humano explícito y sin operaciones inciertas pendientes | Resumen final y evidencia de cierre |
| E11_CONTINUANDO → E02_OBSERVANDO / E04_PLANIFICANDO | Continuidad elegida explícitamente | Nueva ronda o planificación, conservando versiones anteriores |

F01 no permite E06 → E12: un rechazo de precondiciones antes de iniciar deja el
ciclo en E06 sin efecto externo. Tampoco permite E09 → E14 directamente. Cualquier
necesidad distinta requiere revisar el contrato y F01 antes de programar la transición.

## Ejecución y recuperación — requisitos para incrementos 3 y 4

1. El comando de aplicación referenciará ciclo y plan; el servidor resolverá acciones,
   permisos, tipo de adaptador y parámetros de una definición aprobada y validada.
   No se interpretará el texto descriptivo como shell, URL o instrucciones ejecutables.
   El esquema actual solo contiene tipo y descripción: la definición ejecutable,
   su versión y su vínculo con la huella aprobada deben diseñarse antes de implementar.
2. Las acciones se ejecutarán secuencialmente en el orden persistido del plan. El MVP
   no ofrecerá ejecución paralela, compensación automática ni promesa de transacción
   distribuida. Ante fallo se detendrán las acciones siguientes y se conservará el parcial.
3. Un registro durable de coordinación relacionará ciclo, plan/huella, orden y claves
   de operación. Su esquema y migración se definirán en el incremento 3, antes de construirlos.
   Dos comandos concurrentes no podrán iniciar dos coordinaciones de la misma ejecución.
4. Las transacciones breves validarán/reservarán y después confirmarán la coordinación.
   La llamada a IR-009 y al destinatario ocurrirá fuera de esas transacciones. No se
   mantendrán bloqueos de ciclo durante llamadas externas, ni se envolverá IR-009 en
   una transacción de fachada, porque su coordinador lo prohíbe.
5. La autorización se revalidará inmediatamente antes de cada efecto por IR-009. Una
   aprobación inicial no garantiza que las siguientes acciones sigan autorizadas.
6. Si el proceso cae tras aplicar el efecto y antes de actualizar el ciclo, el reinicio
   consultará el registro, las operaciones y los resultados conocidos. Un efecto confirmado
   se reutiliza; un resultado UNKNOWN bloquea el reintento automático y exige reconciliación.
   Un timeout HTTP no se interpreta como «no ejecutado».
7. Reconciliar no reescribe la historia ni crea una nueva identidad del efecto. Si un
   adaptador no garantiza deduplicación durable, no se anunciará recuperación idempotente
   para ese tipo de acción. La política de retención del destinatario debe ser compatible
   con el período durante el cual MIEC permite recuperación.

## Medición, evaluación y decisión — requisitos para incrementos 5–7

- La medición posterior referencia ciclo, intervención y criterio/versión; registra
  valor decimal, unidad, origen, fecha del hecho, recepción y autor. No se reutiliza
  la línea base como supuesto resultado posterior ni se modifica su contenido.
- Antes de comparar se define la ventana y selección o agregación de ambas muestras.
  Tener varias mediciones no autoriza escoger implícitamente la última o la más favorable.
  Unidad y método deben ser comparables; una conversión requiere política explícita.
- La verificación conserva procedencia, responsable o adaptador, método, fecha y
  limitaciones. UNVERIFIED no asciende automáticamente por haber completado la ejecución.
  La verificación humana debe identificar al verificador y aplicar separación de
  responsabilidades; los permisos concretos se cerrarán antes del incremento 5.
- La evidencia de aplicación del efecto de IR-009 y la evidencia de calidad de una
  medición son conceptos distintos; no se trasladan niveles o estados entre ellos.
- `ResultEvaluator` aplicará F07-R01 y F07-R03: EV-0/EV-1 no permiten conclusiones
  concluyentes y la confianza no sustituye evidencia. Conservará limitaciones y usará
  las categorías RS existentes, sin introducir una equivalencia arbitraria entre calidad
  de evidencia, finalización técnica y cumplimiento del objetivo.
- Se informará por separado cumplimiento de umbral y cambio respecto de la línea base.
  Cumplir un umbral no demuestra mejora; cambiar favorablemente no demuestra causalidad.
- La evaluación será reproducible con entradas, referencias y versión de política
  persistidas. El evaluador no modifica el ciclo ni concede autorizaciones.
- La decisión y el cierre son explícitos y auditados. Un ciclo puede concluir sin
  mejora, conservando la clasificación y limitaciones; finalizar no significa declarar éxito.

## Invariantes transversales

Identidad del servidor; permisos por caso de uso; separación solicitante/aprobador;
versión esperada; F01; orden de bloqueo compatible con IR-009; fechas a precisión de
persistencia; estado/evento/transición atómicos; historial preservado; errores HTTP sin
SQL ni secretos. No se construirá una API genérica de cambio de estado. Los DTO, APIs
y clases nuevos mantendrán Javadoc y documentación OpenAPI.

En esta versión los roles son AUTHOR (preparación) y APPROVER (decisión de permisos),
con lectura para ambos. La política del futuro comando de ejecución debe cerrarse en
el incremento 3; este contrato no concede permisos de ejecución a un rol nuevo por adelantado.

## Puertas de aceptación y secuencia pendiente

| Incremento | Entregable | Validación de cierre |
|---|---|---|
| 1 | Este contrato y mapa de responsabilidades | Correspondencia con F01 y contratos reales; sin APIs futuras presentadas como implementadas |
| 2 | Seguridad uniforme, perfiles y CI | 401/403, identidad no falsificable por cabecera, configuración segura por defecto, regresión y reinicio |
| 3 | Coordinación ciclo/IR-009 y API de ejecución | Una reserva por ejecución, huella aprobada, catálogo seguro y concurrencia |
| 4 | Finalización y recuperación | Respuesta perdida, caída entre efecto y actualización del ciclo, reinicio y UNKNOWN bloqueante |
| 5 | Medición posterior y verificación | Comparabilidad, ventanas y evidencia insuficiente conservada |
| 6 | Evaluador productivo | Clasificación reproducible con umbrales y evidencia real |
| 7 | Decisión, aprendizaje y cierre | F01 completa, identidad, motivo e historial sin saltos |
| 8 | Demostración de aceptación v01 | Recorrido integral, regresión y limitaciones publicadas |

No se requieren PetClinic ni PostgreSQL en los incrementos 1 y 2. El flujo productivo
externo exigirá un adaptador real para su caso de aceptación. Antes de despliegue
compartido se acreditarán PostgreSQL, TLS, gestión de identidades, privilegios SQL,
respaldo/restauración y observabilidad. No se consideran resueltos por usar Basic o CI.

Referencias: [preparación y permisos](MVP-02-intervention.md),
[mediciones](MVP-02-measurements.md), [contrato IR-009](IR-009-integration-contract.md),
[seguridad y CI](MVP-v01-security-ci.md).
