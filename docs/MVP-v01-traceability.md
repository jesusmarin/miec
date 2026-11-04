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
| MVP-06.4–06.6: admisión, decisión y cierre | CycleCompletionWorkflow, MvpAlternativePolicy, JdbcDecisionRecorder, V017 | CycleCompletionWorkflowTest; CycleCompletionApiTest; MvpLifecycleRestartIT | [Cierre](MVP-06-decision-closure.md) |
| MVP-07.1–07.3: ronda inicial y conclusión operativa | RoundWorkflow, PlanActivityQuery, RoundController, V018 | RoundMigrationTest; RoundWorkflowTest; CycleCompletionApiTest; MvpLifecycleRestartIT | [Rondas](MVP-07-rounds.md) |
| MVP-07.4–07.8: continuidad y conclusiones por ronda | RoundScope, RoundContinuationWorkflow, V019–V021 | RoundIsolationMigrationTest; RoundReceiptMigrationTest; RoundContinuationTest; CycleCompletionApiTest; MvpLifecycleRestartIT | [Continuidad](MVP-07-continuity.md) |
| Identidad y API segura local | MvpSecurityConfiguration, InterventionIdentity, LifecycleApiErrors | PostMeasurementApiTest y pruebas HTTP de cada incremento | [Seguridad y CI](MVP-v01-security-ci.md) |
| Regresión remota | .github/workflows/mvp-ci.yml | clean verify y MvpLifecycleRestartIT en Actions después del push | [Seguridad y CI](MVP-v01-security-ci.md) |
| Persistencia PostgreSQL local | DatabaseSql, migraciones PostgreSQL V001–V021 y esquema experimental PetClinic | PostgresRoundContinuationIT; PostgresApplicationRestartIT; MiecVisitPostgresIT; MiecVisitPostgresRestartIT | [PostgreSQL](POSTGRESQL-local.md) |
| MVP-08: primer uso local completo | JAR MIEC, SDK embebido en PetClinic, PostgreSQL y tools/pilot | LP-01 a LP-12 aprobados; siete JVM, dos rondas y E14; respaldo/restauración | [Acta técnica](MVP-08-local-project-acceptance.md) |

## Estado y próximos pasos

1. Recorrido administrativo E14 implementado y probado. La fuente humana conserva
   EV-0/EV-1 y no acredita conclusiones causales ni EV-2/EV-3 automáticamente.
2. Ronda inicial, acta, aislamiento, continuidad E02/E04 y admisión/decisión por
   ronda disponibles. E14 no se reabre. Ver [alcance y aceptación](MVP-07-continuity.md).
3. P0: revisión humana del expediente MVP-08, integración de cambios, CI sobre el
   commit resultante y entrega formal versionada del MVP v0.1. El piloto tiene
   12/12 casos técnicos aprobados, no una firma humana o un tag de entrega.
4. P1: continuación parcial, suspensión, aborto operativo y permisos vencidos;
   fuentes verificables de medición más allá de la atestación humana del MVP.
5. P1: PostgreSQL 14 y restauración local están acreditados; quedan evolución
   versionada del esquema PetClinic, roles SQL e inmutabilidad frente a acceso directo.
6. P2: normalización restante de pruebas históricas, paginación, TLS, identidades,
   política operativa de respaldos, monitoreo y despliegue. La restauración de un
   checkpoint del piloto no acredita toda la operación productiva.

La medición posterior queda separada de evaluación y decisión. Un conjunto cerrado
con muestras UNVERIFIED o revisiones rechazadas puede ser insuficiente; el evaluador
deberá conservar esa conclusión. La revisión de evidencia técnica IR-009 no sustituye
la revisión de calidad de mediciones y viceversa.
