# MVP v01 — incremento 2: seguridad uniforme y CI

## Alcance y compatibilidad

Todas las rutas `/api/**` y la documentación Swagger/OpenAPI requieren HTTP Basic.
No hay cuentas ni contraseñas habilitadas por defecto. Un servidor sin claves sigue
arrancando, pero sus APIs permanecen cerradas. El ámbito predeterminado es loopback.
Este incremento no implementa ejecución desde el ciclo, medición posterior ni cierre;
los incrementos 3–8 del [contrato de cierre](MVP-v01-closure-contract.md) quedan pendientes.

Cambio deliberado de compatibilidad: los clientes antiguos de MVP-01 deben enviar
credenciales. `X-MIEC-Actor` ya no es necesario ni se utiliza para atribuir eventos.
No se aceptan actor, roles o estados arbitrarios en los cuerpos de comandos.

## Matriz de permisos

| Operación | AUTHOR | APPROVER | Anónimo |
|---|---|---|---|
| Consultar sistemas, ciclos, historial, objetivos, planes y permisos | Sí | Sí | 401 |
| Crear sistemas/ciclos, iniciar y preparar ciclo | Sí | 403 | 401 |
| Registrar criterios, mediciones, diagnóstico y plan | Sí | 403 | 401 |
| Solicitar autorización | Sí | 403 | 401 |
| Aprobar o rechazar | 403 | Sí, con controles de separación existentes | 401 |
| Swagger UI y OpenAPI JSON/YAML | Sí | Sí | 401 |
| Consola H2 | Solo dev explícito y loopback | 403 | 401 |
| Rutas o métodos no previstos | Denegados | Denegados | Sin acceso |

Las fachadas LifecycleApplicationService, InterventionApplicationService y
MeasurementApplicationService comprueban roles también en llamadas directas. Los
servicios internos conservan sus contratos para coordinación y pruebas; no son
una interfaz remota. Los GET son de lectura; solo los POST JSON previstos permiten
mutaciones. DELETE/PUT/PATCH no adquieren permisos por estar autenticados.

Un error de autenticación responde 401 y WWW-Authenticate; un rol insuficiente, 403.
Las APIs conservan 400/404/409/415/500 saneados. `ownerReference` del sistema es un
metadato y no crea propiedad, permisos por recurso ni aislamiento entre tenants.
Crear un sistema exige AUTHOR; su esquema anterior no almacena un nuevo historial
de identidad por sistema. Los eventos de creación/inicio del ciclo sí conservan el
principal autenticado, incluso si el cliente falsifica X-MIEC-Actor.

## Configuración y credenciales

Variables: MIEC_AUTHOR_USERNAME (author), MIEC_AUTHOR_PASSWORD,
MIEC_APPROVER_USERNAME (approver), MIEC_APPROVER_PASSWORD. Claves de al menos doce
caracteres; nombres y claves distintos entre autor y aprobador. Una clave vacía
deshabilita esa cuenta. No hay registro público de usuarios ni recuperación de claves.
Las cuentas se reconstruyen desde configuración y conservan hashes BCrypt en memoria.

Preparar las variables en la consola del proceso que iniciará la aplicación:

```powershell
$authorSecret = Read-Host 'Clave del autor' -AsSecureString
$approverSecret = Read-Host 'Clave del aprobador' -AsSecureString
$env:MIEC_AUTHOR_PASSWORD = [System.Net.NetworkCredential]::new('', $authorSecret).Password
$env:MIEC_APPROVER_PASSWORD = [System.Net.NetworkCredential]::new('', $approverSecret).Password
.\mvnw.cmd spring-boot:run
```

No poner claves en argumentos, URLs, ejemplos publicados o commits. Los scripts de
demostración requieren PSCredential y no imprimen Authorization. HTTP Basic codifica
las credenciales; no cifra el transporte. Este uso local no sustituye TLS ni una
gestión de identidades para un despliegue remoto. El navegador puede recordar Basic
durante su sesión, aunque Swagger tenga persistAuthorization=false.

Swagger: `/swagger-ui/index.html`; documento `/v3/api-docs` o `/v3/api-docs.yaml`.
Ambos exigen las mismas cuentas. OpenAPI declara Basic en todas las operaciones.
No se habilita CORS. Los POST de API requieren JSON; CSRF solo se omite en `/api/**`.
El resto conserva CSRF y denegación por defecto; la consola dev tiene una cadena
independiente y excepcional. No se habilitan sesiones de autenticación, formulario
de login o logout. La estrategia de cabeceras reenviadas está desactivada.

## Perfiles

| Configuración base | Perfil dev |
|---|---|
| Consola H2 deshabilitada | Sigue deshabilitada salvo MIEC_DEV_H2_CONSOLE=true |
| Logging de MIEC INFO | MIEC DEBUG |
| SQL WARN; parámetros OFF | SQL WARN; parámetros OFF |
| Detalles de errores y solicitudes ocultos | Mismos límites; no se activan trazas de parámetros |
| API autenticada | Misma autenticación y roles |

Para desarrollo explícito:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
# Opcional y solo en una estación de desarrollo local:
$env:MIEC_DEV_H2_CONSOLE = 'true'
.\mvnw.cmd spring-boot:run
```

Solo el perfil dev con la propiedad de consola activada instala la cadena que
admite `/h2-console/**`. Además exige conexión loopback y AUTHOR. Las cabeceras
de proxy no conceden acceso. Frames same-origin y excepción CSRF se limitan a esa
consola, sin modificar la API. El navegador puede requerir también credenciales
JDBC en el formulario de H2; eso es independiente del acceso HTTP Basic.
Habilitar únicamente spring.h2.console.enabled fuera de dev no concede acceso:
la cadena general lo deniega. No exponer dev mediante un proxy o túnel.

Actualización MVP-03: PlanExecutionApplicationService también comprueba roles en
llamadas directas. AUTHOR inicia/reanuda, APPROVER recupera hechos locales y ambos
consultan. POST /api/plans/{id}/execution/recover está reservado explícitamente a
APPROVER en los filtros. La recuperación no concede ni renueva EXECUTE_ACTION.
Ver [contrato de ejecución](MVP-03-plan-execution.md).

La base sigue siendo H2 local y acepta MIEC_DB_URL, MIEC_DB_USERNAME y MIEC_DB_PASSWORD.
La compatibilidad local conserva `sa` y clave vacía si no se configuran: esto no es
un endurecimiento productivo de SQL. No existe aún un perfil productivo certificado,
ni soporte PostgreSQL acreditado, ni privilegios SQL mínimos o respaldo operativo
resueltos por este incremento. Tampoco se migra ni cambia la contraseña de una base
existente automáticamente al configurar una variable.

## CI reproducible

Workflow: [mvp-ci.yml](../.github/workflows/mvp-ci.yml), para push, pull_request y
ejecución manual. No despliega, publica artefactos Maven ni envía mensajes.

1. Checkout del prototipo y del SDK de test en directorios independientes.
2. SDK fijado a `5cb3b0fa7971070954acaa7fd2e89620b04f2f51`, no a una rama móvil.
3. Java 21, Maven Wrapper 3.10.0 y caché Maven.
4. Compilación, pruebas e instalación del SDK.
5. `clean verify` del prototipo.
6. `MvpLifecycleRestartIT` explícita sobre el JAR recién construido.
7. Reportes de pruebas como artefacto de siete días, incluso si falla una prueba.

Las acciones oficiales checkout/setup-java/upload-artifact están fijadas por SHA;
el token del workflow solo dispone de contents:read y no queda persistido en Git.
El SDK es público: por defecto el checkout no requiere un PAT adicional ni
ejecuta la validación obligatoria de secreto. Para una integración privada,
configurar la variable de repositorio `SDK_PRIVATE=true` y el Repository secret
`RECIPIENT_SDK_READ_TOKEN` en el repositorio que ejecuta Actions, con lectura de
Contents del SDK seleccionado. La rama privada del workflow no sustituye un
secreto ausente por github.token. La rama pública y privada son excluyentes.
La etapa `Validate SDK credential and pinned source` comprueba el secreto recibido,
el acceso al repositorio, el commit fijado y el transporte Git, sin registrar el token.
`SDK_SECRET_MISSING` indica que el runner no recibió el secreto; `SDK_TOKEN_REJECTED`
indica HTTP 401; `SDK_ACCESS_FORBIDDEN` identifica HTTP 403 y la etapa afectada.
Un 403 no demuestra que se necesite permiso de escritura. Si las tres comprobaciones
pasan y checkout falla, investigar cómo checkout aplica la autenticación.
Estos cambios requieren un nuevo commit y push: repetir una ejecución antigua
no incorpora el diagnóstico nuevo. El funcionamiento del token en el PC no acredita
qué credencial recibió el runner. Nunca copiar tokens en logs ni en incidencias.
En forks los secretos no se suministran: el SDK debe ser público
o debe acordarse un mecanismo de acceso; nunca usar pull_request_target para ejecutar
código no confiable con ese secreto. No se debe retirar la dependencia ni omitir sus
pruebas para ocultar un fallo de checkout.

Los tests usan bases y cuentas ficticias propias; no necesitan secretos de la
aplicación ni el archivo H2 del usuario. Las pruebas PetClinic *IT siguen fuera del
patrón normal de Surefire y no se anuncian como ejecutadas por este workflow. La
dependencia SDK sigue siendo de test y no se incorpora al JAR de la aplicación.

Equivalente local, con los dos repositorios presentes:

```powershell
.\mvnw.cmd -f ../miec-recipient-sdk/pom.xml clean install
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

CI conserva los reportes de regresión y de reinicio. La validación local del workflow
no equivale a una ejecución remota en GitHub; esta ocurrirá al subir el commit o
ejecutar workflow_dispatch. No se modifica la protección de ramas automáticamente.

Referencias oficiales: [autorización Spring Security](https://docs.spring.io/spring-security/reference/7.0/servlet/authorization/authorize-http-requests.html),
[CI Maven](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven),
[setup-java](https://github.com/actions/setup-java).

## Validación del incremento

| Comprobación local | Resultado |
|---|---|
| Seguridad, fachadas y regresión HTTP enfocada | 26 pruebas satisfactorias |
| `clean verify` del prototipo | 763 pruebas, sin fallos, errores ni omisiones |
| JAR entre JVM con autenticación | 3 pruebas satisfactorias: ciclo, aprobación y medición/línea base |
| SDK de la revisión fijada, exportado a una copia aislada | 24 pruebas satisfactorias mediante `verify`; repositorio hermano sin modificaciones |
| Workflow | actionlint 1.7.12 sin errores; binario oficial con SHA-256 verificado |
| Demostraciones | Sintaxis válida de los tres guiones; credenciales requeridas |
| JAR | Fachada y perfiles incluidos; sin SDK, adaptadores experimentales ni clases de prueba; consola apagada en configuración base |

Las 26 pruebas enfocadas están incluidas en las 763 y no se suman. Los conteos
de SDK y reinicio corresponden a ejecuciones separadas. No se ejecutaron las
demostraciones contra la base H2 del usuario ni se ejecutó el workflow en GitHub.
Se conserva la base local fuera del staging. No se requieren nuevas migraciones
para estos dos incrementos.

Logs locales ignorados por Git: review-mvp01-increments12-security.log,
review-mvp01-increments12-regression.log, review-mvp01-increments12-restart.log y
review-mvp01-increments12-sdk.log. Incrementos 1 y 2 terminados; se espera instrucción
antes de desarrollar los incrementos 3–8.
