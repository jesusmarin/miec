# MVP-02: preparación, plan y autorización humana

## Alcance implementado

El flujo parte de un ciclo iniciado mediante MVP-01. Un autor autenticado registra
objetivo, observación y diagnóstico manual; propone un plan y solicita permisos de
sus acciones. Un aprobador autenticado consulta el plan y su contexto y decide.
El resultado es E06_PREPARADO o E12_SUSPENDIDO. No se ejecutan acciones externas.

| Paso | Transición o efecto |
|---|---|
| Completar inicialización | E01_INICIALIZANDO → E02_OBSERVANDO; objetivo y criterio de éxito. |
| Observar | E02_OBSERVANDO → E03_DIAGNOSTICANDO; HUMAN_REPORT, calidad UNVERIFIED. |
| Diagnosticar | E03_DIAGNOSTICANDO → E04_PLANIFICANDO; diagnóstico humano y evidencia del ciclo. |
| Proponer | Permanece E04; plan inmutable por API y revisión 0. |
| Solicitar autorización | E04 → E05_ESPERANDO_AUTORIZACION; una solicitud por acción. |
| Aprobar parcialmente | Permanece E05; APPROVED_PENDING_PLAN no permite ejecutar. |
| Completar aprobaciones | E05 → E06_PREPARADO; todas las autorizaciones pasan a APPROVED juntas. |
| Rechazar | E05 → E12_SUSPENDIDO; plan REJECTED y solicitudes relacionadas invalidadas. |

Cada comando cambia la versión del agregado y conserva evento y motivo. Las
transiciones usan la regla F01 compartida con Cycle. No existe un comando genérico
para imponer un estado ni una evaluación automática de éxito basada en el reporte.
La descripción del diagnóstico no convierte evidencia manual en evidencia verificada.

## Rutas

| Método/ruta | Rol | Entrada |
|---|---|---|
| POST /api/cycles/{id}/initialize | AUTHOR | expectedVersion, objective, successCriteria, reason. |
| POST /api/cycles/{id}/observations | AUTHOR | expectedVersion, description, sourceReference opcional, reason. |
| POST /api/cycles/{id}/diagnosis | AUTHOR | expectedVersion, evidenceId, summary, reason. |
| POST /api/cycles/{id}/plans | AUTHOR | expectedVersion, objectiveId, expectedOutcomes, rollbackStrategy, actions. |
| GET /api/plans/{id} | AUTHOR o APPROVER | Definición, huella, revisión y acciones. |
| GET /api/plans/{id}/context | AUTHOR o APPROVER | Objetivo, criterio, diagnóstico, fuente, calidad y limitaciones. |
| POST /api/plans/{id}/authorization-requests | AUTHOR | expectedPlanVersion, actionId, validForSeconds. |
| GET /api/authorizations/{id} | AUTHOR o APPROVER | Estado actual y decisión humana conservada. |
| POST /api/authorizations/{id}/approve | APPROVER | expectedVersion y reason. |
| POST /api/authorizations/{id}/reject | APPROVER | expectedVersion y reason. |

Las tres operaciones de preparación devuelven cycle y resourceId: objetivo,
evidencia o diagnóstico según la ruta. Los POST de plan y solicitud devuelven 201
y Location; el resto de mutaciones, 200. Las entradas no admiten actor, roles,
approvedBy, status o scope impuestos por el cliente. El alcance se fija en
EXECUTE_ACTION. El catálogo inicial admite PROCESS_EXECUTION; no interpreta
descripciones como comandos del sistema operativo.

Un plan admite de una a veinte acciones. Los UUID se generan en el servidor. El
documento de acciones utiliza schemaVersion 01 y se valida con PlannedActionJsonV01.
El objetivo debe estar vinculado al ciclo y el plan referencia su diagnóstico.

## Autenticación local

Las rutas nuevas usan HTTP Basic por solicitud, con sesiones, formulario de login
y caché de solicitudes deshabilitados. Las mutaciones exigen application/json y
no se habilita CORS. El uso previsto es local, en 127.0.0.1. El despliegue remoto
necesitará TLS, administración de identidades y revisión de seguridad adicionales.

Variables de entorno, antes de iniciar MIEC:

| Variable | Valor por defecto |
|---|---|
| MIEC_AUTHOR_USERNAME | author |
| MIEC_AUTHOR_PASSWORD | Vacío: cuenta deshabilitada. |
| MIEC_APPROVER_USERNAME | approver |
| MIEC_APPROVER_PASSWORD | Vacío: cuenta deshabilitada. |

Las claves configuradas requieren al menos doce caracteres; autor y aprobador no
pueden compartir identidad, sin distinguir mayúsculas, ni contraseña. No hay claves
de acceso incorporadas. El manager de usuarios conserva hashes BCrypt en memoria;
las cuentas se recrean desde configuración al reiniciar y no se guardan en la BD.
Los nombres de principal de las decisiones sí quedan persistidos para auditoría.
La separación es entre identidades configuradas; no acredita identidad civil.

Ejemplo de configuración en la consola que iniciará la aplicación, sin escribir
claves literales en el historial de comandos:

```powershell
$authorKey = Read-Host 'Clave del autor' -AsSecureString
$approverKey = Read-Host 'Clave del aprobador' -AsSecureString
$env:MIEC_AUTHOR_PASSWORD = [System.Net.NetworkCredential]::new('', $authorKey).Password
$env:MIEC_APPROVER_PASSWORD = [System.Net.NetworkCredential]::new('', $approverKey).Password
.\mvnw.cmd spring-boot:run
```

X-MIEC-Actor se ignora como fuente de autoridad en estas rutas. Se utiliza el
Authentication de Spring Security también en la fachada pública de servicios.
El flujo transaccional de bajo nivel es interno al paquete de aplicación.
La actualización de [seguridad uniforme](MVP-v01-security-ci.md) protege también
las rutas de MVP-01 y resuelve su identidad en el servidor. No se introducen
permisos de ejecución externa mediante estas rutas.

Referencia de configuración: [Spring Security, Java Configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html).

## Demostración

Con MIEC reiniciado para cargar V011 y las credenciales, en otra consola:

```powershell
$author = Get-Credential -UserName author -Message 'Autor configurado en MIEC'
$approver = Get-Credential -UserName approver -Message 'Aprobador configurado en MIEC'
.\docs\mvp02-demo.ps1 -AuthorCredential $author -ApproverCredential $approver -Actions 2
```

El guion crea recursos nuevos en cada ejecución, prepara el ciclo, propone un plan,
consulta su contexto y registra solicitudes y decisiones. No imprime contraseñas
ni cabeceras Basic. Utilizar -Reject para rechazar la última acción y observar la
suspensión; -BaseUrl permite otra instancia local. Es un guion de demostración,
no una interfaz de aprobación que sustituya la revisión humana del contexto.

## Consistencia y autorización

- El orden de bloqueo de las escrituras del nuevo flujo es sistema, ciclo, plan,
  autorización. Versiones viejas, duplicados o etapas incorrectas producen 409.
- El plan no tiene rutas de edición. Las solicitudes guardan una huella versionada
  de sus campos relevantes, acciones y referencias de contexto. Estado y revisión
  administrativa no forman parte de esa huella. Se comprueba antes de decidir.
- El aprobador no puede ser el solicitante, tampoco con diferencias de mayúsculas.
- La vigencia empieza al solicitar y admite de 60 a 86400 segundos. Aprobar una
  solicitud vencida devuelve 409; aún puede rechazarse para cerrar la revisión.
- Una aprobación humana parcial se conserva en authorization_decision y permanece
  APPROVED_PENDING_PLAN: AuthorizationValidityPolicy y ExecutionAttemptService
  la rechazan. Al completar el plan se comprueba IR-010 para todos sus candidatos
  antes de activarlos atómicamente y pasar a E06. La ejecución futura revalidará
  nuevamente el permiso, porque puede expirar después de su aprobación.
- Rechazar cancela las solicitudes relacionadas y revoca las que pudieran estar
  activas, sin borrar la decisión original. No deshace efectos ya ejecutados.
- Estado, versiones, solicitud, decisión y eventos confirman o revierten juntos.
  Un error al escribir la decisión final no deja permisos activos sin auditoría.
- Los errores 400/401/403/404/409/500 son estructurados y no exponen SQL o secretos.

V011 añade el diagnóstico, metadatos de plan/solicitud y el registro de decisiones.
Los planes históricos conservan creador y diagnóstico desconocidos y no se editan
desde este flujo. La inmutabilidad es de aplicación; esta entrega no restringe
por roles SQL el acceso administrativo directo a las tablas.

## Validación y avance

Los seis pasos están completados. Los pasos 1 a 5 pasaron respectivamente 4, 3, 4,
10 y 10 pruebas. Los grupos se superponen: no deben sumarse al total. La regresión
final pasó 741 pruebas, cero fallos, errores u omisiones, y clean package terminó
correctamente. Las dos pruebas explícitas entre JVM también pasaron con el JAR
final, incluida la lectura del contexto del plan después del reinicio.

Se comprobó que el artefacto incluye API, seguridad y V011, y excluye experimentos,
SDK de test y clases de pruebas. El guion PowerShell se analizó sin errores de
sintaxis. Logs locales ignorados por Git: review-mvp02-step1.log a
review-mvp02-step5.log, review-mvp02-regression-final.log y
review-mvp02-restart-final.log. Los resultados de cada paso se imprimieron en consola.

```powershell
.\mvnw.cmd clean package
.\mvnw.cmd "-Dtest=MvpLifecycleRestartIT" "-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar" test
```

La prueba explícita levanta únicamente JVM propias con base temporal, puertos y
credenciales efímeras; conserva plan, contexto, aprobación, versión e historial al
reiniciar. Las pruebas de rollback inyectan errores SQL intencionales; sus logs no
son fallos de la suite. El guion PowerShell se valida sintácticamente y no se ejecuta
automáticamente contra la base del usuario.

## Próximo incremento

MVP-03: ejecución controlada y verificación desde la API. Deberá comprobar el plan
aprobado y su huella, el ciclo preparado y las autorizaciones aún vigentes, conservar
el orden de bloqueos compatible con ejecución y utilizar únicamente adaptadores
instalados en el servidor. No aceptará comandos arbitrarios o ejecutores en JSON.

Persisten como trabajo posterior reanudación/reformulación tras rechazo, renovación
de solicitudes vencidas, administración de usuarios, controles por objeto/múltiples
operadores, integridad a nivel SQL, otros motores y endurecimiento productivo.

## Objetivos medibles del MVP v01

La ampliacion compatible de objetivos numericos, lineas base y cierre explicito
se documenta en [MVP-02-measurements.md](MVP-02-measurements.md). El contrato
anterior de observacion sigue disponible para ciclos historicos sin criterios
estructurados; los ciclos medibles requieren su cierre explicito.
