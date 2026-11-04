# Instalación y operación conjunta — MVP local v0.1.0

Guía operativa del laboratorio Windows/PowerShell. Integra MIEC, el SDK y el
destinatario PetClinic sobre PostgreSQL 14. No modifica el contrato de ejecución
ni acredita un despliegue productivo. Fecha: 2026-11-04.

Esta guía y `tools/operations` son un complemento posterior a la etiqueta
`v0.1.0`: conservar también la revisión de este incremento operativo. No se
modifica la etiqueta ni se presume que sus ZIP históricos ya incluyan estas herramientas.

## 1. Alcance, componentes y disponibilidad

| Componente | Revisión / artefacto | Responsabilidad y acceso |
|---|---|---|
| MIEC | Etiqueta `v0.1.0`, commit `ae1cba1c08b17e4e824a957edc29f81f7c5eae05`; `miec-prototype-0.1.0.jar` | Coordinación y auditoría; `127.0.0.1:8081`; perfil `postgres` |
| SDK | Commit `5cb3b0fa7971070954acaa7fd2e89620b04f2f51`; `com.miec:miec-recipient-sdk:0.1.0-SNAPSHOT` | Biblioteca embebida en PetClinic, sin servidor ni base propios; dependencia de test de MIEC |
| PetClinic | Rama local `miec/experimento-001`; `spring-petclinic-4.0.0-SNAPSHOT.jar` | Negocio y ledger de deduplicación; `127.0.0.1:8082`; perfiles `miec,miec-postgres` |
| PostgreSQL | Laboratorio validado en 14.4; contenedor `postgres14` | Host `127.0.0.1:5532`, puerto interno 5432; bases `miec-v01` y `petclinic` |

**Límite de distribución:** las adaptaciones PostgreSQL de PetClinic siguen siendo
cambios locales sobre `b6ec14272da51bc9a885d2e4f086f07a78c78127`; clonar el upstream
de Spring PetClinic o ese commit por sí solo no las reproduce. Conservar el JAR
saneado y su hash, además de las fuentes adaptadas revisadas, para otra máquina.
Publicar esas adaptaciones en un repositorio propio fijado por commit sigue
pendiente. No hacer push al upstream como parte de esta instalación.

La etiqueta de MIEC no versiona automáticamente PetClinic. Los nombres SNAPSHOT
del SDK y de PetClinic tampoco fijan su contenido: registrar sus SHA-256.
El expediente histórico [MVP-08](MVP-08-local-project-acceptance.md) conserva sus
propios binarios; no sustituirlos ni atribuirles una recompilación nueva.

Requisitos: JDK **21 completo** (`java`, `javac`, attach local), Maven Wrapper y sus
dependencias, PowerShell 5.1 o posterior, Docker Desktop/WSL2 para el procedimiento
de base documentado y Python 3.11+ solo para el demostrador. No se instala otro
PostgreSQL ni se recrea el contenedor existente.

```powershell
Set-Location F:\Dev\AI\MIEC\Desarrollo-MIEC\miec-prototype
java -version
javac -version
.\mvnw.cmd -version
docker inspect --format '{{.State.Status}} {{.Config.Image}}' postgres14
docker port postgres14 5432
docker exec postgres14 pg_isready -U postgres -d postgres
docker exec postgres14 pg_dump --version
docker exec postgres14 pg_restore --version
```

Comprobar espacio libre, reloj de Windows/Docker y disponibilidad de 8081/8082.
No reiniciar Docker/WSL2 automáticamente: coordinar otros servicios. Ante retrocesos
del reloj, seguir [validación temporal](POSTGRESQL-temporal-validation.md) antes de
ejecutar acciones; no relajar fechas ni permisos.

## 2. Credenciales y configuración por sesión

| Uso | Variables / regla |
|---|---|
| JDBC de MIEC | `MIEC_POSTGRES_URL`, `MIEC_POSTGRES_USERNAME`, `MIEC_POSTGRES_PASSWORD` |
| JDBC de PetClinic | `MIEC_PETCLINIC_POSTGRES_URL`, `MIEC_PETCLINIC_POSTGRES_USERNAME`, `MIEC_PETCLINIC_POSTGRES_PASSWORD` |
| Identidades MIEC | `MIEC_AUTHOR_USERNAME/PASSWORD`, `MIEC_APPROVER_USERNAME/PASSWORD`; nombres diferentes y claves distintas, mínimo 12 caracteres |
| Destinatario | `MIEC_PETCLINIC_API_KEY` y `MIEC_RECIPIENT_API_KEY`: misma clave, mínimo 16 caracteres |
| Identidad lógica | `MIEC_PETCLINIC_DESTINATION` y `MIEC_RECIPIENT_DESTINATION_ID`: `petclinic-experiment-001` |
| Transporte instalado | `MIEC_RECIPIENT_ENABLED=true`, `MIEC_RECIPIENT_BASE_URL=http://127.0.0.1:8082`, `MIEC_RECIPIENT_EXECUTE_PATH=/miec/visits`, adaptador `http-recipient-v1`, versión `1`, timeout `PT10S` |

El laboratorio usa **la misma cuenta PostgreSQL `postgres`** en ambas bases;
no son claves SQL independientes para ese mismo usuario. AUTHOR, APPROVER y API
key son secretos distintos. Los roles SQL mínimos quedan fuera de este alcance.

Desde la consola que iniciará las dos JVM:

```powershell
.\tools\operations\Set-LocalEnvironment.ps1
```

El script pregunta sin eco, valida antes de instalar variables y no inicia procesos
ni escribe archivos. Sus valores solo afectan esta consola y sus futuros procesos
hijos. No es necesario dot-source. No establece variables persistentes de Windows.
Usar las mismas credenciales entre reinicios; generar otra API key para una sola
aplicación rompe la consulta y la reconciliación. El script configura los nombres
predeterminados de las bases; después de ejecutarlo, aplicar las URLs de restauración
si corresponde. No volver a ejecutarlo después de ese cambio sin revisar las URLs.

No imprimir el entorno completo, usar `setx`, guardar `.env` privados en Git ni
incluir secretos en argumentos Java, capturas, logs o respaldos de configuración.
Revisar overrides `SPRING_DATASOURCE_*`, `SPRING_APPLICATION_JSON`, perfiles o
argumentos de ejecuciones previas: pueden prevalecer sobre estas variables. Retirar
solo los overrides identificados y volver a iniciar; no mostrar su contenido.
El perfil versionado conserva `xxx`, que es un marcador, no una credencial válida.

El entorno contiene cadenas en memoria, no una bóveda de secretos; procesos hijos
de esta consola las heredan. Cerrar la sesión al terminar. Para retirar las variables:

```powershell
.\tools\operations\Set-LocalEnvironment.ps1 -Clear
```

Esto no borra copias heredadas por JVM ya iniciadas ni rota cuentas en PostgreSQL.
Para rotar una clave SQL, cambiarla por el canal administrativo y actualizar ambas
aplicaciones durante una ventana detenida. Para rotar la API key, detener envíos,
cambiar ambos extremos y reiniciar ambos; la implementación actual no admite una
ventana con dos claves activas. Para AUTHOR/APPROVER, actualizar el entorno y
reiniciar MIEC. Conservar los nombres para no perder continuidad de atribución.
Nunca borrar operaciones del ledger para resolver una incidencia de autenticación.

## 3. Preparar artefactos e instalar esquemas

La estructura local esperada contiene las tres carpetas hermanas:

```text
Desarrollo-MIEC/
  miec-prototype/
  miec-recipient-sdk/
  spring-petclinic/
```

Verificar `git rev-parse HEAD` y estado de cada repositorio sin publicar diffs de
properties. El SDK debe estar en la revisión fijada antes de instalarlo; si hay
cambios locales o una revisión diferente, usar un checkout separado revisado,
sin descartar trabajo para cambiar de versión.

```powershell
.\mvnw.cmd -f ..\miec-recipient-sdk\pom.xml clean install
if ($LASTEXITCODE -ne 0) { throw 'Fallo SDK' }
.\mvnw.cmd clean verify
if ($LASTEXITCODE -ne 0) { throw 'Fallo regresion MIEC' }
# Ambos proyectos: compila y empaqueta perfiles JDBC saneados en target/classes.
.\tools\pilot\prepare-artifacts.ps1
Get-FileHash target\miec-prototype-0.1.0.jar -Algorithm SHA256
Get-FileHash ..\spring-petclinic\target\spring-petclinic-4.0.0-SNAPSHOT.jar -Algorithm SHA256
```

`prepare-artifacts.ps1` admite `-MavenExecutable`, `-LocalRepository C:/R` y
`-Offline` solo si la caché está completa. No ejecutar otro `package`/`verify`
después de sanear y antes de compartir un JAR: puede reincorporar defaults privados
del árbol local. No distribuir `.jar.original`. Conservar artefactos saneados,
hashes y fuentes revisadas fuera de Git; comparar hashes antes de cada restauración.

Si las bases ya tienen datos, **conservarlas**. Para una instalación inicial,
crear únicamente las bases que no existan, con la herramienta administrativa o
`createdb -T template0`, y verificar nombres antes de continuar. No duplicar la
instalación si existen. MIEC aplica/valida las 21 migraciones PostgreSQL al iniciar;
PetClinic aplica su inicializador experimental aditivo de ocho tablas y fixtures
900001, sin visitas. No usar los inicializadores genéricos de PetClinic/H2.

Las IT `PostgresInstallationIT` y `MiecPostgresInstallationIT` son para instalación
vacía/no utilizada; no ejecutarlas contra las bases del piloto con negocio.
No usar Flyway clean, `ddl-auto=create/update`, DROP ni TRUNCATE para arrancar.
Una restauración debe arrancar primero con los mismos JAR, antes de evaluar una
actualización que pueda aplicar nuevas migraciones.

## 4. Iniciar, comprobar y detener

Orden de arranque: PostgreSQL → PetClinic → MIEC. Ejecutar este bloque en una
consola dedicada con el entorno del paso 2; sus futuros procesos hijos lo heredan.
`$run` es nuevo en cada arranque y queda fuera del repositorio.

```powershell
$root = (Get-Location).Path
$run = Join-Path $env:LOCALAPPDATA ('MIEC\operations\run-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$miecJar = (Resolve-Path 'target\miec-prototype-0.1.0.jar').Path
$petJar = (Resolve-Path '..\spring-petclinic\target\spring-petclinic-4.0.0-SNAPSHOT.jar').Path
function Start-LocalJar {
    param([string]$Jar, [string]$Profiles, [int]$Port, [string]$Name)
    $jvm = Start-Process -FilePath (Get-Command java.exe).Source -WindowStyle Hidden -PassThru `
        -WorkingDirectory $run -RedirectStandardOutput (Join-Path $run "$Name.log") `
        -RedirectStandardError (Join-Path $run "$Name.err.log") -ArgumentList @(
            '-jar', ('"' + $Jar + '"'), ('--spring.profiles.active=' + $Profiles),
            '--server.address=127.0.0.1', ('--server.port=' + $Port),
            '--spring.application.admin.enabled=true', '--spring.jmx.enabled=true')
    $jvm.Id | Set-Content (Join-Path $run "$Name.pid")
    return $jvm
}
$petProcess = Start-LocalJar $petJar 'miec,miec-postgres' 8082 'petclinic'
# Esperar HTTP 200 de health; si falla, revisar el log antes de iniciar MIEC.
Invoke-RestMethod http://127.0.0.1:8082/actuator/health
$miecProcess = Start-LocalJar $miecJar 'postgres' 8081 'miec'
```

El arranque es asíncrono: reintentar **solo lecturas de disponibilidad** hasta un
plazo acotado (por ejemplo 120 s), comprobando que el PID sigue vivo. No reintentar
POST de negocio como sonda. No usar los leases de cinco segundos del harness
experimental para operación normal.

MIEC: abrir `http://127.0.0.1:8081/swagger-ui/index.html`, autenticarse y comprobar
`GET /api/systems?limit=10`. OpenAPI está en `/v3/api-docs`. Sin credencial debe
responder 401; AUTHOR o APPROVER permiten lectura. El detalle de un ID inexistente
responde 404 después de autenticar. PetClinic: health 200 y formularios `/owners`;
su contrato MIEC requiere `X-MIEC-Api-Key`. Un GET de operación con UUID nuevo debe
responder 404 con la API key y 401 sin ella, sin crear visita.

No habilitar exposición remota, consola H2 o logs de cabeceras/SQL para comprobar
credenciales. HTTP Basic local no sustituye TLS ni gestión de identidades.

Parada planificada: bloquear nuevas solicitudes del operador, esperar envíos en
curso y registrar IDs/estado incierto antes de detener MIEC y luego PetClinic.
Desde la misma cuenta del SO y con los procesos de este arranque identificados:

```powershell
java --add-modules jdk.attach tools/pilot/PilotShutdown.java "$($miecProcess.Id)"
if ($LASTEXITCODE -ne 0) { throw 'Revisar cierre MIEC' }
if (-not $miecProcess.WaitForExit(30000)) { throw 'MIEC no termino dentro del plazo' }
java --add-modules jdk.attach tools/pilot/PilotShutdown.java "$($petProcess.Id)"
if ($LASTEXITCODE -ne 0) { throw 'Revisar cierre PetClinic' }
if (-not $petProcess.WaitForExit(30000)) { throw 'PetClinic no termino dentro del plazo' }
```

Si una JVM ya había terminado antes del attach, verificar el objeto de proceso y su log. El helper
requiere admin/JMX **local** habilitado por los argumentos de arranque anteriores;
no abre puerto JMX remoto. Confirmar PID y comando de la JVM antes de attach:
un PID guardado de otra sesión podría haberse reutilizado. Nunca terminar todos
los procesos Java o todos los contenedores. Ante un cierre fallido conservar logs,
declarar el checkpoint no apto y no simular una parada correcta. No forzar el cierre
para obtener un respaldo supuestamente limpio.

## 5. Política de respaldo local

Responsable: operador del laboratorio; registrar nombre, fecha UTC y motivo.
Respaldar **el par completo**, con todos sus historiales y ledger, antes de cambios
de esquema/configuración, después de una sesión aceptada y al finalizar cada día
con actividad. Mantener al menos siete pares diarios y cuatro semanales, incluyendo
el último par restaurado satisfactoriamente. Es una política propuesta para el
laboratorio, no una tarea programada ya instalada.

Objetivo local: no perder trabajo anterior al último checkpoint aprobado; el RPO
real es la antigüedad de ese checkpoint. Medir tiempo de restauración en cada
ensayo; no se promete un RTO. Programar un ensayo mensual y tras cambios de esquema.
No eliminar automáticamente copias ni bases para aplicar retención.

Dos `pg_dump` independientes no forman una transacción distribuida. Detener ambas
aplicaciones y **todos los otros escritores**, incluidas ediciones SQL, desde antes
del primer checkpoint hasta terminar los dos dumps y verificar de nuevo. Revisar
`pg_stat_activity`; los pools de las JVM deben desaparecer. Una sesión idle no
prueba ausencia futura de escrituras: coordinarla con su operador. Consultas de
sesiones y checkpoints son controles auxiliares, no un bloqueo de acceso SQL.

No copiar archivos del volumen PostgreSQL activo. Usar formato custom con los
clientes 14 del contenedor, sin transmitir bytes de dump por un pipeline textual
de PowerShell. Los comandos Docker usan el acceso administrativo local ya validado
por socket dentro de `postgres14`, no la contraseña JDBC. No cambiar `pg_hba.conf`
para permitir trust. Si `-w` falla, detenerse y usar autenticación administrativa
previamente configurada; no añadir contraseñas a la línea de comandos.

Los dumps contienen negocio y datos personales. Guardarlos fuera de Git en una
ubicación con acceso restringido y cifrado de disco; copiar el par verificado a
otro medio privado protegido. SHA-256 detecta cambios respecto del manifiesto,
pero no reemplaza firma, cifrado ni control de acceso. Revisar manifiesto y dumps
juntos; no publicar el expediente ni subirlo a un release público.

## 6. Crear un respaldo coordinado

Ejecutar desde la raíz MIEC **después de la parada y coordinación** del paso 4.
Los comandos fallan si el directorio ya existe o una herramienta devuelve error.
No cerrar conexiones ajenas desde SQL para forzar este paso.

```powershell
$ErrorActionPreference = 'Stop'
$container = 'postgres14'
$pairId = 'pair-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N')
$backup = Join-Path $env:LOCALAPPDATA ('MIEC\backups\' + $pairId)
New-Item -ItemType Directory -Path $backup | Out-Null
function Invoke-LabDocker {
    param([string[]]$DockerArguments)
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw 'Fallo Docker/PostgreSQL; conservar el expediente y detener el procedimiento.' }
}
function Invoke-LabSql {
    param([string]$Database, [string]$Sql)
    $Sql | & docker exec -i $container psql -X -w -qAt -U postgres -d $Database -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw 'Fallo SQL; conservar el expediente y detener el procedimiento.' }
}
Invoke-LabSql 'postgres' "SELECT datname,pid,application_name,state FROM pg_stat_activity WHERE datname IN ('miec-v01','petclinic');"
$checkpointSql = Get-Content tools/operations/checkpoint.sql -Raw
$sources = [ordered]@{ miec = 'miec-v01'; petclinic = 'petclinic' }
$started = [DateTime]::UtcNow.ToString('o')
foreach ($label in $sources.Keys) {
    Invoke-LabSql $sources[$label] $checkpointSql | Set-Content -Encoding UTF8 (Join-Path $backup "$label.before.txt")
}
foreach ($label in $sources.Keys) {
    $remote = "/tmp/$pairId-$label.dump"
    Invoke-LabDocker @('exec', $container, 'pg_dump', '-w', '-U', 'postgres', '-Fc', '-d', $sources[$label], '-f', $remote)
    Invoke-LabDocker @('exec', $container, 'pg_restore', '--list', $remote) | Out-Null
    Invoke-LabDocker @('cp', ($container + ':' + $remote), (Join-Path $backup "$label.dump"))
}
foreach ($label in $sources.Keys) {
    $after = @(Invoke-LabSql $sources[$label] $checkpointSql)
    $before = @(Get-Content (Join-Path $backup "$label.before.txt"))
    if (Compare-Object $before $after) { throw 'El origen cambio durante el respaldo: no aprobar este par.' }
}
$files = @{}
foreach ($label in $sources.Keys) {
    $files[$label] = @{
        database = $sources[$label]; file = "$label.dump"
        sha256 = (Get-FileHash (Join-Path $backup "$label.dump") -Algorithm SHA256).Hash
        checkpointSha256 = (Get-FileHash (Join-Path $backup "$label.before.txt") -Algorithm SHA256).Hash
    }
}
$manifest = @{
    pairId = $pairId; startedAtUtc = $started; completedAtUtc = [DateTime]::UtcNow.ToString('o')
    operator = [Environment]::UserName; container = $container; databases = $files
    postgresVersion = @(Invoke-LabDocker @('exec', $container, 'pg_dump', '--version'))
    miecJarSha256 = (Get-FileHash 'target/miec-prototype-0.1.0.jar' -Algorithm SHA256).Hash
    petclinicJarSha256 = (Get-FileHash '../spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar' -Algorithm SHA256).Hash
    release = 'v0.1.0'; restoreAcceptance = 'PENDING'
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $backup 'manifest.json')
Write-Output "PAR RESPALDADO, RESTAURACION PENDIENTE: $backup"
```

`checkpoint.sql` solo lee: registra cantidad y resumen MD5 por tabla de `public`,
incluido Flyway, y estado de secuencias. Compara datos sin imprimir su contenido;
MD5 no es prueba criptográfica de autenticidad. La agregación está pensada para el
volumen local, no grandes bases. Conservar también los dos JAR saneados exactos,
la revisión/hash del SDK y configuración **sin secretos**; los secretos deben poder
recuperarse por el canal privado de credenciales. `pg_dump` no incluye roles de
clúster ni una configuración completa del servidor.

Un hash correcto o `pg_restore --list` no acredita restauración. Si un paso falla,
conservar la carpeta como intento fallido, no escribir un manifiesto aprobado ni
mezclar el dump de una base con otro par. Crear un nuevo pairId para repetir.
Los dumps temporales permanecen en `/tmp` del contenedor. Tras confirmar la copia
externa y su hash, retirar únicamente los archivos de ese pairId y los temporales
de esa restauración; no borrar directorios ni usar patrones que afecten otros pares.
Hasta retirarlos, tratarlos como otra copia privada del respaldo.

## 7. Restaurar en un par nuevo y verificar

Leer únicamente un respaldo propio confiable. Restaurar un dump ejecuta objetos
SQL del origen. En una consola nueva volver a definir las funciones del paso 6,
`$container`, `$checkpointSql` y **elegir la carpeta existente `$backup`**; no repetir
su creación ni volver a generar dumps. Mantener ambas aplicaciones detenidas.

```powershell
$manifest = Get-Content (Join-Path $backup 'manifest.json') -Raw | ConvertFrom-Json
foreach ($label in @('miec', 'petclinic')) {
    $record = $manifest.databases.$label
    if ($record.file -cne "$label.dump") { throw 'Nombre de dump inesperado' }
    if ((Get-FileHash (Join-Path $backup "$label.dump") -Algorithm SHA256).Hash -ne $record.sha256) { throw 'Hash de dump invalido' }
    if ((Get-FileHash (Join-Path $backup "$label.before.txt") -Algorithm SHA256).Hash -ne $record.checkpointSha256) { throw 'Hash de checkpoint invalido' }
}
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 16)
$targets = [ordered]@{ miec = "miec_ops_restore_miec_$suffix"; petclinic = "miec_ops_restore_petclinic_$suffix" }
foreach ($label in $targets.Keys) {
    $db = $targets[$label]
    if ($db -cnotmatch '^miec_ops_restore_(miec|petclinic)_[0-9a-f]{16}$') { throw 'Destino fuera del laboratorio' }
    $remote = "/tmp/restore-$suffix-$label.dump"
    Invoke-LabDocker @('cp', (Join-Path $backup "$label.dump"), ($container + ':' + $remote))
    Invoke-LabDocker @('exec', $container, 'createdb', '-w', '-U', 'postgres', '-T', 'template0', $db)
    Invoke-LabDocker @('exec', $container, 'pg_restore', '-w', '-U', 'postgres', '--exit-on-error', '--single-transaction', '--no-owner', '--no-privileges', '-d', $db, $remote)
    $actual = @(Invoke-LabSql $db $checkpointSql)
    $expected = @(Get-Content (Join-Path $backup "$label.before.txt"))
    $actual | Set-Content -Encoding UTF8 (Join-Path $backup "$label.restored-$suffix.txt")
    if (Compare-Object $expected $actual) { throw 'Restauracion diferente del checkpoint: no iniciar ni cambiar el destino.' }
    Invoke-LabSql $db 'ANALYZE;'
}
$targets | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $backup "restore-$suffix.json")
Write-Output 'DATOS Y SECUENCIAS VERIFICADOS; validacion HTTP aun pendiente.'
```

No se usa `--clean`, `--create`, DROP ni una base original como destino. `createdb`
debe fallar si el nombre ya existe. Un error deja las bases nuevas para inspección;
no limpiar ni promover automáticamente el par parcialmente restaurado.
`--no-owner --no-privileges` corresponde al laboratorio con `postgres`: no restaura
una política productiva de propietarios y privilegios. Prepararla por separado
cuando existan roles distintos; no dar por acreditados permisos SQL.

Configurar las credenciales por el paso 2 si es necesario, luego cambiar **ambas**
URLs antes de arrancar. Iniciar PetClinic y MIEC con los mismos JAR verificados
contra los hashes del manifiesto, perfiles y puertos del paso 4:

```powershell
$env:MIEC_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:5532/$($targets['miec'])?currentSchema=public"
$env:MIEC_PETCLINIC_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:5532/$($targets['petclinic'])?currentSchema=public"
```

Antes de volver a habilitar envíos, comparar por HTTP los sistemas/ciclos del
checkpoint, estados/versiones, rondas, planes, mediciones y conclusiones; consultar
operaciones conocidas en PetClinic y comprobar sus claves, huellas y referencias.
Verificar que no aparecieron migraciones inesperadas. Registrar las respuestas
sin cabeceras de autenticación, tiempos de recuperación y URLs de bases utilizadas.
Un replay de negocio no es una comprobación de solo lectura: si se ensaya, usar
únicamente los bytes, clave y huella originales aprobados, dentro del procedimiento
MVP-08, y comprobar V=1/K=1. No inventar otra clave para recuperar una respuesta.

Marcar aceptación del ensayo solo después de revisar datos **y HTTP**. Conservar
el par original, los dumps y los dos destinos del ensayo. El cambio operativo
al par restaurado es una decisión explícita del operador registrada en el acta;
no mezclar MIEC restaurado con PetClinic original. Para volver atrás, detener ambas
JVM y seleccionar un par coherente; si hubo escrituras posteriores al cambio,
no volver al anterior sin analizar sus efectos y conciliar el negocio.
Nunca hacer retroceder solo el ledger para repetir efectos ya realizados.

## 8. Incidentes y controles diarios

Para clasificar antes de intervenir, seguir la
[guía de recuperación operativa](LOCAL-operational-recovery.md). Distingue resume
(puede enviar), recover (hechos locales), reconcile (consulta autorizada) y finish.
No todos los parciales tienen continuación disponible en el MVP.

Para registrar y presentar mediciones, aplicar los
[límites de calidad de evidencia humana](LOCAL-measurement-quality.md): una revisión
aceptada o una ejecución APPLIED no acreditan suficiencia instrumental ni causalidad.

| Señal | Acción |
|---|---|
| SQL no disponible / conexión rechazada | Verificar contenedor, 5532 y credencial por canal privado; no crear otra base vacía para ocultarlo |
| API MIEC 401 / 403 | Revisar identidad habilitada, clave y rol; la clave SQL o la API key del destinatario no sustituyen Basic |
| PetClinic 401 | Revisar API key en ambos extremos y reiniciar con el entorno correcto; no reenviar con otra operationKey |
| 409 de versión / autorización vencida | Volver a leer el recurso y seguir el flujo de autorización vigente; no modificar la versión por SQL |
| Timeout / UNKNOWN | Conservar el intento y clasificar según [recuperación operativa](LOCAL-operational-recovery.md), sin presumir NO_EFFECT |
| Flyway / checksum / esquema incompatible | Detener el avance, guardar logs y usar artefacto compatible; no ejecutar repair/clean ni editar historial como solución genérica |
| Reloj retrocede | Suspender nuevos envíos y repetir diagnóstico temporal; no desactivar controles de vigencia |
| Hash distinto / restore incompleto | No iniciar el par restaurado; conservar diagnóstico y seleccionar un respaldo íntegro |

Al iniciar cada sesión: salud PostgreSQL/PetClinic, acceso Basic MIEC, reloj,
destino lógico y espacio. Al terminar: revisar incidentes e incertidumbre, respaldar
el par coordinado, registrar hashes/ubicación/operador y comprobar la segunda copia.
Revisar logs sin publicar datos personales. No hay scheduler, monitoreo central,
cifrado de backups ni política de retención automatizados implementados por esta guía.

## 9. Criterios de aceptación y referencias

- Artefactos/SDK identificados y perfiles sin contraseñas privadas en el paquete.
- Arranque PostgreSQL → PetClinic → MIEC; salud, autenticación y lectura verificadas.
- Par respaldado sin escritores, manifiesto y hashes; originales preservados.
- Restauración en dos bases nuevas; checkpoints de datos y secuencias iguales.
- Arranque del par restaurado y consultas HTTP equivalentes; acta del operador.
- Fallos conservan evidencia y no se presentan como aceptación.

Las validaciones realizadas al consolidar esta guía se registran en
[informe operativo](LOCAL-operations-validation.md). La entrega técnica v0.1.0 y la
aceptación funcional son asuntos separados: [acta de entrega](MVP-v0.1.0-release.md).

El asistente de credenciales puede verificarse sin diálogos ni secretos reales
con siete escenarios simulados, en un proceso hijo para aislar el entorno operativo:

```powershell
powershell -NoProfile -File tools/operations/tests/LocalEnvironment.Tests.ps1
```

No dot-sourcear el archivo de pruebas ni ejecutarlo dentro de una consola que
deba conservar credenciales de operación: las pruebas validan también su retirada.

Referencias: [SQL dump PostgreSQL 14](https://www.postgresql.org/docs/14/backup-dump.html),
[pg_restore PostgreSQL 14](https://www.postgresql.org/docs/14/app-pgrestore.html),
[seguridad local](MVP-v01-security-ci.md), [PostgreSQL](POSTGRESQL-local.md),
[contrato y demostrador conjunto](MVP-08-local-project.md).
