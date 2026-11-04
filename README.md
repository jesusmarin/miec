# MIEC Prototype — hacia MVP v01

Aplicación Java 21 / Spring Boot para ciclos de mejora trazables. Incluye sistemas,
ciclos, objetivos medibles, líneas base, diagnóstico, planes y aprobación humana.
El núcleo IR-009 gestiona intentos e idempotencia y está conectado al ciclo preparado
mediante coordinación durable y APIs de ejecución. Incluye finalización técnica
hacia E08 y reconciliación remota autorizada de efectos aplicados. Registra mediciones
posteriores, revisiones humanas separadas y conjuntos fijos de comparación.
Incluye evaluación operativa conservadora, decisión humana, aprendizaje y cierre
administrativo hasta E14. Incluye continuidad aprobada E11 → E02/E04, nuevas fuentes,
planes y permisos por ronda, con evaluación, decisión y aprendizaje separados.
Las fuentes instrumentales verificadas y la preparación productiva siguen pendientes.
La entrega técnica local v0.1.0 está etiquetada; la aceptación funcional y la preparación productiva permanecen separadas.

El primer uso local con PetClinic y PostgreSQL ya tiene aceptación técnica:
12 casos aprobados, dos rondas hasta E14, respuesta perdida, concurrencia,
reinicios y restauración del par de bases. La entrega técnica `v0.1.0` está publicada; la firma funcional sigue pendiente.

- [Contrato de cierre y secuencia del MVP v01](docs/MVP-v01-closure-contract.md).
- [Seguridad uniforme, configuración y CI](docs/MVP-v01-security-ci.md).
- [API de sistemas y ciclos](docs/MVP-01-api.md).
- [Objetivos medibles y líneas base](docs/MVP-02-measurements.md).
- [Planes y permisos](docs/MVP-02-intervention.md).
- [Integración IR-009](docs/IR-009-integration-contract.md).
- [Ejecución del plan: 3.1, 3.2 y 3.3](docs/MVP-03-plan-execution.md).
- [Admisión final del envío e incidentes](docs/MVP-03-send-admission.md).
- [Finalización y recuperación: pasos 5–8](docs/MVP-03-finalization-recovery.md).
- [Mediciones posteriores y revisión: MVP-05](docs/MVP-05-post-measurements.md).
- [Trazabilidad central y pendientes](docs/MVP-v01-traceability.md).
- [Evaluación: políticas, evaluador y persistencia, 06.1–06.3](docs/MVP-06-evaluation.md).
- [Decisión, aprendizaje y cierre E14, 06.4–06.6](docs/MVP-06-decision-closure.md).
- [Diseño del siguiente incremento: nuevas rondas](docs/MVP-next-rounds-design.md).
- [Ronda inicial y conclusión operativa: MVP-07.1–07.3](docs/MVP-07-rounds.md).
- [Continuidad, dos rondas y aceptación: MVP-07.4–07.8](docs/MVP-07-continuity.md).
- [Contrato del primer destinatario local: MVP-08](docs/MVP-08-local-project.md).
- [Resultado y evidencia del piloto completo: 12/12](docs/MVP-08-local-project-acceptance.md).
- [Consulta y navegación inicial: MVP-09](docs/MVP-09-navigation.md).
- [Instalación y operación conjunta: credenciales, arranque y respaldo/restauración](docs/LOCAL-installation-operations.md).
- [Recuperación operativa: parciales, permisos vencidos e incertidumbre](docs/LOCAL-operational-recovery.md).
- [Instalación y validación de PostgreSQL 14 local](docs/POSTGRESQL-local.md).

Configurar las cuentas antes de usar las APIs; sin claves no hay acceso. Por defecto
la aplicación escucha en 127.0.0.1:8081 y mantiene apagada la consola H2. Swagger,
también autenticado, está en `/swagger-ui/index.html`. Ver la guía de seguridad para
arranque y demostraciones con credenciales. Este prototipo local no es un despliegue
productivo certificado.

Las pruebas requieren el SDK hermano en la revisión fijada por CI. Ejecutar:

```powershell
.\mvnw.cmd -f ../miec-recipient-sdk/pom.xml clean install
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.1.0.jar' test
```

El workflow de GitHub Actions compila la dependencia y realiza estas validaciones.
Las pruebas usan bases aisladas; no requieren PetClinic ni PostgreSQL para los
incrementos 1 y 2.

## Entrega MVP v0.1.0

Alcance local, instalación, validaciones y limitaciones: [acta técnica de entrega](docs/MVP-v0.1.0-release.md).
