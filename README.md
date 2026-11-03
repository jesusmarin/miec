# MIEC Prototype — hacia MVP v01

Aplicación Java 21 / Spring Boot para ciclos de mejora trazables. Incluye sistemas,
ciclos, objetivos medibles, líneas base, diagnóstico, planes y aprobación humana.
El núcleo IR-009 gestiona intentos e idempotencia y está conectado al ciclo preparado
mediante coordinación durable y APIs de ejecución. Incluye finalización técnica
hacia E08 y reconciliación remota autorizada de efectos aplicados. Registra mediciones
posteriores, revisiones humanas separadas y conjuntos fijos de comparación.
Incluye evaluador numérico conservador y persistencia interna/consulta de conclusiones.
La creación operativa de evaluaciones, E08 → E09 y el cierre humano siguen pendientes.
El MVP v01 completo no se declara cerrado.

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

Configurar las cuentas antes de usar las APIs; sin claves no hay acceso. Por defecto
la aplicación escucha en 127.0.0.1:8081 y mantiene apagada la consola H2. Swagger,
también autenticado, está en `/swagger-ui/index.html`. Ver la guía de seguridad para
arranque y demostraciones con credenciales. Este prototipo local no es un despliegue
productivo certificado.

Las pruebas requieren el SDK hermano en la revisión fijada por CI. Ejecutar:

```powershell
.\mvnw.cmd -f ../miec-recipient-sdk/pom.xml clean install
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

El workflow de GitHub Actions compila la dependencia y realiza estas validaciones.
Las pruebas usan bases aisladas; no requieren PetClinic ni PostgreSQL para los
incrementos 1 y 2.
