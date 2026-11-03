# MVP v01 — trazabilidad central de componentes y aceptación

Esta matriz ubica lo implementado y los límites pendientes; no declara completo
el MVP v01 ni acredita producción. Cada guía conserva sus conteos históricos;
la entrega nueva registra su regresión en su propia sección de resultados.

| Capacidad / contrato | Componentes principales | Evidencia de aceptación | Documento |
|---|---|---|---|
| F01 y ciclo versionado | LifecycleService, InterventionSupport, Cycle | LifecycleMigrationTest; MvpLifecycleRestartIT | [API base](MVP-01-api.md) |
| Objetivos y línea base | MeasurementContracts, MeasurementWorkflow, MeasurementQueries | MeasurementWorkflowTest | [Mediciones base](MVP-02-measurements.md) |
| Plan y autorización humana | InterventionWorkflow, InterventionPlans, AuthorizationValidityPolicy | InterventionPreparationTest | [Preparación](MVP-02-intervention.md) |
| IR-009 e idempotencia | IdempotentActionExecutionService, ExecutionAttemptService | AuthorizedActionUnifiedIntegrationTest | [Contrato IR-009](IR-009-integration-contract.md) |
| Coordinación y admisión | PlanExecutionApplicationService/Store, ExecutionDispatchAdmission | PlanExecutionApplicationTest; PlanDispatchAdmissionTest | [Ejecución](MVP-03-plan-execution.md), [admisión](MVP-03-send-admission.md) |
| Finalización E08 y recuperación APPLIED | PlanRecoveryApplicationService/Store, DestinationEvidenceVerificationService | PlanRecoveryApplicationTest; PlanExecutionFinalizationTest; MvpLifecycleRestartIT | [Finalización](MVP-03-finalization-recovery.md) |
| MVP-05: resultado, revisión y conjunto fijo | PostMeasurementWorkflow, MeasurementQueries, PostMeasurementController, V015 | PostMeasurement*Test; MvpLifecycleRestartIT | [Mediciones posteriores](MVP-05-post-measurements.md) |
| MVP-06.1–06.3: políticas y conclusión interna | DeterministicResultEvaluator, MeasurementEvidencePolicy, ClosedSetEvaluationStore, V016 | DeterministicResultEvaluatorTest; ClosedSetEvaluationStoreTest; EvaluationApiTest; ClosedSetEvaluationRestartIT | [Evaluación](MVP-06-evaluation.md) |
| Identidad y API segura local | MvpSecurityConfiguration, InterventionIdentity, LifecycleApiErrors | PostMeasurementApiTest y pruebas HTTP de cada incremento | [Seguridad y CI](MVP-v01-security-ci.md) |
| Regresión remota | .github/workflows/mvp-ci.yml | clean verify y MvpLifecycleRestartIT en Actions después del push | [Seguridad y CI](MVP-v01-security-ci.md) |

## Pendientes para continuar

1. P0: creación operativa de evaluación y E08 → E09 atómicos (06.4). El evaluador
   V1 y el guardado interno están implementados; la fuente humana conserva EV-0/EV-1
   y no acredita conclusiones causales ni EV-2/EV-3 automáticamente.
2. P0: decisión humana, aprendizaje y cierre con F02/F01, versiones e historial.
3. P1: continuación parcial, suspensión, aborto operativo y permisos vencidos;
   fuentes verificables de medición más allá de la atestación humana del MVP.
4. P1: PostgreSQL/migraciones, roles SQL e inmutabilidad frente a acceso directo.
5. P2: normalización restante de pruebas históricas, paginación, TLS, identidades,
   respaldos/restauración, monitoreo y despliegue. Basic local y H2 no los sustituyen.

La medición posterior queda separada de evaluación y decisión. Un conjunto cerrado
con muestras UNVERIFIED o revisiones rechazadas puede ser insuficiente; el evaluador
deberá conservar esa conclusión. La revisión de evidencia técnica IR-009 no sustituye
la revisión de calidad de mediciones y viceversa.
