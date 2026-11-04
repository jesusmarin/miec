# PostgreSQL 14 local — MIEC

## Alcance y configuración

MIEC incorpora 21 migraciones SQL independientes en `src/main/resources/db/postgresql`
para una base vacía. Las migraciones H2 publicadas y su V021 Java permanecen intactas.
No se importan datos históricos desde H2.

| Proyecto | Base | Dirección | Perfil | Estado |
|---|---|---|---|---|
| MIEC | miec-v01 | 127.0.0.1:5532 | postgres | Migraciones y consultas adaptadas |
| PetClinic | petclinic | 127.0.0.1:5532 | miec,miec-postgres | Esquema del experimento pendiente de portar |

Servidor validado: PostgreSQL 14.4. El puerto 5532 es el acceso local; no sustituirlo
por el puerto interno del servidor. El usuario `postgres` se utiliza por indicación
del laboratorio, sin acreditar endurecimiento productivo.

Archivo: `src/main/resources/application-postgres.properties`. Activa Flyway,
desactiva el inicializador SQL y la consola H2, y fija UTC en las sesiones del pool.
No desactivar Flyway ni habilitar `ddl-auto=create/update` para iniciar.

Definir el secreto mediante entorno, sin escribirlo en comandos o commits:

```powershell
$dbCredential = Get-Credential -UserName 'postgres' -Message 'PostgreSQL: miec-v01'
$env:MIEC_POSTGRES_PASSWORD = $dbCredential.GetNetworkCredential().Password
```

La copia versionada usa `${MIEC_POSTGRES_PASSWORD:xxx}`. Una contraseña introducida
localmente debe permanecer fuera del índice de Git. Las cuentas AUTHOR/APPROVER y
credenciales del destinatario son independientes; seguir [MVP-08](MVP-08-local-project.md).

## Decisiones de portabilidad

- Fechas: `TIMESTAMP(6) WITH TIME ZONE`, convertidas a `Instant` por JDBC.
- Vigencia: `miec_wall_clock()` usa `clock_timestamp()`, que avanza dentro de la
  transacción. `CURRENT_TIMESTAMP` de PostgreSQL conserva el inicio de la transacción
  y no sirve para revalidar un permiso tras esperar bloqueos.
- Las autorizaciones de IR-009 usan el reloj de la base. Los reportes internos
  fechan su observación dentro de su escritura y conservan esa fecha al deduplicar.
  Siguen siendo `EXECUTOR_REPORT`, nunca verificaciones del destinatario.
- Los reportes externos mantienen su fecha explícita y rechazan fechas futuras.
  Sincronizar los relojes del entorno; no se introduce tolerancia artificial.
- JSON preparado: `CAST(? AS JSON)` en PostgreSQL y `? FORMAT JSON` en H2.
  `DatabaseSql` identifica el motor por metadatos JDBC y conserva los parámetros.
  JPA declara explícitamente el tipo JSON, sin convertir documentos en cadenas JSON.
- Fencing: `nextval` funciona en ambos motores y no reutiliza tokens tras rollback.
- V021 PostgreSQL conserva el alcance por ronda y las restricciones del historial
  H2 sin ejecutar migraciones Java dependientes de metadatos H2.

## Pruebas reproducibles

Requieren JDK 21, dependencias Maven y SDK disponibles, PostgreSQL local y permiso
`CREATE DATABASE`. Crean nombres aleatorios `miec_pg_*`, cierran sus JVM y eliminan
exclusivamente sus bases. No escriben en `miec-v01` ni `petclinic`.
No se ejecutan en la regresión predeterminada ni requieren secretos en CI.

**Estado: esquema instalado; validación temporal del entorno pendiente.** No se
declara cerrado el incremento PostgreSQL ni habilitado el piloto conjunto.

Resultados locales del 2026-11-04:

| Validación | Casos | Resultado |
|---|---:|---|
| Regresión H2 y empaquetado (`verify`) | 905 | Sin fallos ni errores |
| Servicios y persistencia PostgreSQL, ejecución aislada | 13 | Pasaron; la repetición conjunta detectó un fallo temporal |
| JAR PostgreSQL y reinicio HTTP | 1 | Sin fallos ni errores |
| Instalación explícita de miec-v01 | 1 | 21 migraciones, cero ciclos |
| Control conjunto de PostgreSQL y reinicios previos | 20 | 19 aprobados; fallo temporal en dos rondas |
| Diagnóstico posterior del reloj PostgreSQL | 1 | Falló: retroceso de aproximadamente 664 ms |

Durante la adaptación se eliminó el uso del reloj JVM en autorizaciones de IR-009
y reportes internos. Una repetición posterior detectó un problema adicional real:
`miec_wall_clock()` retrocedió de `2026-11-04T15:38:45.572378Z` a
`2026-11-04T15:38:44.908839Z` entre dos lecturas consecutivas. Lo capturó
`databaseClockDoesNotRegress` durante la ejecución depurada. Los rechazos de vigencia,
fechas futuras y ventanas de medición deben mantenerse: una ejecución aislada
exitosa no demuestra estabilidad temporal del entorno.

La base está en el contenedor `postgres14` (`postgres:14-alpine`, puerto 5532),
sobre Docker Desktop/WSL2. `w32tm /query /status` informó indicador de salto 3 y
capa 0, ambos **sin sincronizar**, con origen `time.windows.com,0x9`.
Esto señala una condición del entorno que debe corregirse; no identifica por sí
solo el mecanismo exacto que produce el retroceso. No se cambiaron el reloj de
Windows ni el estado de Docker/WSL o de otros contenedores.

Antes de continuar, estabilizar la sincronización de Windows y comprobar el reloj
de Docker/WSL2. La intervención debe coordinarse porque un reinicio de Docker o WSL
puede interrumpir PostgreSQL y otros servicios locales. Después ejecutar primero
el diagnóstico, repetir la suite PostgreSQL y comprobar dos rondas completas.

```powershell
# 13 escenarios: migraciones, dos rondas, concurrencia, permisos, rollback,
# contradicciones, JSON JPA, reloj de pared y fencing.
.\mvnw.cmd '-Dtest=PostgresRoundContinuationIT#databaseClockDoesNotRegress' '-Dmiec.postgres.test=true' test
.\mvnw.cmd '-Dtest=PostgresRoundContinuationIT' '-Dmiec.postgres.test=true' test

# JAR real: HTTP, ciclo versionado, auditoría y dos JVM.
.\mvnw.cmd '-DskipTests' package
.\mvnw.cmd '-Dtest=PostgresApplicationRestartIT' '-Dmiec.postgres.test=true' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test

# Regresión predeterminada H2, sin conexión PostgreSQL.
.\mvnw.cmd verify

# Control conjunto final: seis casos previos de reinicio y catorce PostgreSQL.
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT,PostgresRoundContinuationIT,PostgresApplicationRestartIT' '-Dmiec.postgres.test=true' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

La instalación sobre la base del usuario es separada, explícita y sin limpieza.
Solo admite `miec-v01` vacía o con el historial completo ya aplicado. No introduce
sistemas, ciclos ni visitas de demostración:

```powershell
.\mvnw.cmd '-Dtest=PostgresInstallationIT' '-Dmiec.postgres.install=true' test
```

Una base no vacía con historial incompleto se rechaza sin aplicar migraciones.
No ejecutar esta comprobación de instalación sobre una base con ciclos reales:
su aceptación exige cero ciclos.

## Arranque y siguiente integración

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=postgres'
```

Activar este perfil para usar PostgreSQL; el perfil base conserva H2. El arranque
aplica o valida su propio historial. Las IT del JAR validan el ciclo inicial entre
JVM; las IT de rondas validan servicios completos con un adaptador de laboratorio,
sin acreditar un destinatario externo.

El siguiente paso es portar y validar el esquema propio de PetClinic y ejecutar
[MVP-08](MVP-08-local-project.md). No iniciar aún `miec,miec-postgres`: sigue exigiendo
`db/miec-postgresql/schema.sql` y `data.sql`. El piloto conjunto, reinicios de
PetClinic, privilegios SQL mínimos, respaldo/restauración, TLS e identidades
distribuidas requieren validaciones separadas.
