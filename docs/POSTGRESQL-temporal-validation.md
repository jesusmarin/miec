# Validación temporal de Windows, Docker/WSL2 y PostgreSQL

Fecha: 2026-11-04. Entorno local de MIEC, PostgreSQL 14.4, puerto 5532.

Estado del procedimiento: diez pasos completados. Control temporal y funcional
local aprobado durante la ventana observada; permanece un desfase absoluto
variable entre JVM y PostgreSQL, documentado abajo.

## Incidente y alcance

El diagnóstico anterior detectó un retroceso de aproximadamente 664 ms en
`miec_wall_clock()`. La validación conjunta terminó entonces con 19 de 20 casos
aprobados. Windows informaba indicador de salto 3 y capa 0, sin sincronizar.

La intervención fue autorizada para ejecutar los diez pasos de estabilización.
No se modificaron las políticas de vigencia, los límites de fechas, la calidad de
evidencia ni `PostMeasurementWorkflow`. No se añadieron tolerancias o reintentos
para convertir una prueba fallida en exitosa.

## Ejecución por pasos

| Paso | Acción | Resultado |
|---|---|---|
| 1 | Preparación del mantenimiento | No había aplicaciones MIEC/PetClinic activas; los dos procesos Java identificados eran herramientas del IDE. Se identificaron ambos contenedores y sus volúmenes persistentes. |
| 2 | Consulta del reloj Windows | W32Time estaba activo, pero el estado era sin sincronizar; origen `time.windows.com,0x9`. |
| 3 | Resincronización | La consola ordinaria recibió acceso denegado; la ejecución elevada mediante UAC completó `w32tm /resync /rediscover` con código 0. |
| 4 | Confirmación Windows | Indicador de salto 0, ninguna advertencia; capa 5; última sincronización correcta a las 11:06:00, hora local. |
| 5 | Comparación host/contenedor | La comparación visible seguía mostrando desfase. Después del reinicio se midieron por JDBC desfases residuales de unos 151 ms y 727 ms, en momentos distintos. |
| 6 | Reinicio del entorno Linux | Se detuvieron ambos contenedores ordenadamente, se cerró Docker Desktop, se ejecutó `wsl --shutdown`, se abrió Docker Desktop y se restauraron ambos contenedores. |
| 7 | Diagnóstico JDBC repetido | Tres JVM independientes aprobaron 2.000 comparaciones consecutivas cada una, sin retrocesos. |
| 8 | Escenarios PostgreSQL | 13 casos aprobados, sin fallos, errores ni omisiones. |
| 9 | Regresión y control conjunto | Regresión y empaquetado: 905 aprobados. Control conjunto: 20 aprobados. Cero fallos, errores u omisiones. |
| 10 | Evidencia y documentación | Informe y guía local actualizados y preparados en Git; registros de ejecución excluidos del repositorio. |

Contenedores restaurados:

| Contenedor | Imagen | Puerto local | Persistencia |
|---|---|---:|---|
| postgres14 | postgres:14-alpine | 5532 | Volumen en `/var/lib/postgresql/data` |
| postgres-18-alphine | postgres:18-alpine | 5832 | Volumen en `/var/lib/postgresql` |

Se comprobó con consultas de solo lectura que `miec-v01` conserva las 21
migraciones aplicadas y cero ciclos. Ambos PostgreSQL aceptan conexiones.
No se eliminaron volúmenes ni bases del usuario. Las pruebas PostgreSQL usan
exclusivamente bases temporales propias y las eliminan al terminar.

## Resultados de aceptación

Todas las horas siguientes son locales, America/Bogota, el 2026-11-04.

| Validación | Finalización | Casos | Fallos / errores / omitidos |
|---|---|---:|---|
| Diagnóstico de reloj, JVM 1 | 11:10:12 | 1 | 0 / 0 / 0 |
| Diagnóstico de reloj, JVM 2 | 11:10:53 | 1 | 0 / 0 / 0 |
| Diagnóstico de reloj, JVM 3 | 11:11:18 | 1 | 0 / 0 / 0 |
| Suite PostgreSQL | 11:12:28 | 13 | 0 / 0 / 0 |
| Regresión H2 y empaquetado | 11:16:20 | 905 | 0 / 0 / 0 |
| Control conjunto de reinicios y PostgreSQL | 11:21:36 | 20 | 0 / 0 / 0 |

Cada diagnóstico realiza 2.000 comparaciones consecutivas. Las tres ejecuciones
independientes aportan 6.000 comparaciones sin retrocesos. El mismo diagnóstico
también pasó dentro de la suite PostgreSQL y del control conjunto: 10.000
comparaciones en total en esta secuencia. Los 13 casos PostgreSQL están incluidos
también en el control de 20; no representan 33 escenarios distintos.

El control conjunto acredita cinco casos previos de reinicio del MVP, un caso de
evaluación de conjunto cerrado tras reinicio, el caso HTTP del JAR PostgreSQL y
los trece escenarios PostgreSQL. Todos finalizaron con `BUILD SUCCESS`.

## Comparación precisa de relojes

Una sonda JDBC de solo lectura tomó 25 muestras y seleccionó la de menor latencia:

```text
host_before = 2026-11-04T16:11:34.196203600Z
database    = 2026-11-04T16:11:34.045061Z
host_after  = 2026-11-04T16:11:34.196724300Z
database_minus_host_ms = [-151.6633, -151.1426]
```

Una segunda muestra durante el control de reinicios registró:

```text
host_before = 2026-11-04T16:17:32.887816Z
database    = 2026-11-04T16:17:32.161433Z
host_after  = 2026-11-04T16:17:32.890006100Z
database_minus_host_ms = [-728.5731, -726.383]
```

Las muestras dejan constancia de un desfase residual variable; no declaran relojes idénticos.
El criterio del diagnóstico es ausencia de retrocesos entre lecturas consecutivas.
Una evidencia externa fechada en el futuro respecto del receptor sigue siendo
inválida. Las fuentes deben gestionar la sincronización de sus observaciones.

## Comandos reproducibles de aceptación

Desde la raíz de `miec-prototype`, con las credenciales locales configuradas:

```powershell
# Ejecutar tres veces en procesos independientes, conservando todos los resultados.
.\mvnw.cmd '-Dtest=PostgresRoundContinuationIT#databaseClockDoesNotRegress' '-Dmiec.postgres.test=true' test

# Servicios reales, dos rondas, permisos, concurrencia, rollback, JSON y fencing.
.\mvnw.cmd '-Dtest=PostgresRoundContinuationIT' '-Dmiec.postgres.test=true' test

# Regresión y construcción del JAR.
.\mvnw.cmd verify

# Reinicios anteriores y PostgreSQL sobre el JAR recién construido.
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT,PostgresRoundContinuationIT,PostgresApplicationRestartIT' '-Dmiec.postgres.test=true' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test
```

La ejecución de mantenimiento utilizó Maven local en modo offline con el repositorio
`C:/R`; los comandos del wrapper anteriores expresan las mismas selecciones de pruebas.

Registros locales, excluidos de Git por `*.log`:

- `review-temporal-windows-resync.log`.
- `review-temporal-step05-offset.log`.
- `review-temporal-final-offset.log`.
- `review-temporal-step07-clock-01.log`, `-02.log` y `-03.log`.
- `review-temporal-step08-postgres.log`.
- `review-temporal-step09-regression.log`.
- `review-temporal-step09-jvm-postgres.log`.

## Límite de la aceptación

El procedimiento comprueba una ventana de funcionamiento local, no garantiza
estabilidad permanente ni demuestra la causa exacta del salto original. Después
de suspensiones, reinicios o cambios de hora, repetir el diagnóstico antes de
retomar la aceptación del piloto. Un nuevo retroceso debe reabrir esta deuda.

La integración PostgreSQL de PetClinic y el piloto conjunto MVP-08 son trabajos
posteriores y no quedan acreditados por este procedimiento.
