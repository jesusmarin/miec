# MVP-08 — Primer uso local completo con PostgreSQL

Contrato: **MVP08-LOCAL-02**. Fecha: 2026-11-04.

**Aceptación técnica ejecutada: LP-01 a LP-12 aprobados.** El contrato anterior
MVP08-LOCAL-01 describía un piloto H2 pendiente; este alcance PostgreSQL lo sustituye.
El [informe del piloto](MVP-08-local-project-acceptance.md) identifica los artefactos,
los registros y las bases originales y restauradas. La firma humana y una entrega
formal versionada permanecen separadas de esta aceptación técnica.

## 1. Propósito y alcance

Demostrar con aplicaciones independientes el recorrido sistema → ciclo → objetivo
→ línea base → diagnóstico → plan → autorización → ejecución → medición posterior
→ evaluación → decisión → aprendizaje → continuidad y cierre. El mismo ciclo realiza
dos rondas completas y termina en E14.

Se ejecutan JAR reales de MIEC y PetClinic, con sus bases PostgreSQL locales
`miec-v01` y `petclinic`, mediante HTTP loopback. No se incorporan
HttpRecipientFixture, beans de prueba o clases PetClinic al JAR de MIEC.
Un proxy local reenvía a PetClinic real y corta una respuesta únicamente después
de recibir el éxito y comprobar el commit en PostgreSQL; no fabrica resultados.

| Proyecto | Responsabilidad |
|---|---|
| miec-prototype | Ciclo, rondas, plan, autorizaciones, adaptador HTTP genérico, intentos, reconciliación, medición, evaluación y auditoría |
| spring-petclinic | API, reglas de visita y transacción de visita/clave/resultado |
| miec-recipient-sdk | Contratos y huella UTF8-V1 consumidos dentro del JAR PetClinic |

El SDK es una biblioteca, no un tercer servidor HTTP. Su JAR embebido en PetClinic
debe tener el mismo SHA-256 que el artefacto fijado en el expediente.

Registrar visitas acredita integración e integridad, no evolución del código,
mejora causal ni suficiencia instrumental EV-2/EV-3. AUTHOR y APPROVER son
identidades distintas operadas automáticamente; no equivalen a dos personas.

## 2. Destinatario y datos propios

PetClinic utiliza los perfiles `miec,miec-postgres`. El destino lógico es
`petclinic-experiment-001`, instalado en el servidor MIEC mediante configuración
confiable. El plan contiene el ID del adaptador, versión, destino, media type y
cuerpo aprobado; no admite URLs ni credenciales elegidas por el cliente.

El demostrador crea propietario y mascota nuevos mediante los formularios HTTP
de PetClinic. No utiliza ownerId/petId=900001. Reutiliza el catálogo de tipos del
laboratorio y selecciona una fecha siete días posterior al día del servidor.

Cada ronda fija una sola vez sus bytes JSON UTF-8:

```json
{"ownerId":"ID_NUEVO","petId":"ID_NUEVO","date":"FECHA_FUTURA","description":"RUNID-R1"}
```

Los IDs reales son numéricos. R2 tiene otro marcador, plan, acción, autorización,
línea base y clave. Los datos sintéticos quedan conservados para inspección; no
se eliminan visitas o filas del ledger para repetir una prueba.

## 3. Ejecución, consulta y recuperación

| Operación PetClinic | Contrato |
|---|---|
| POST /miec/visits | Primera confirmación 201; replay 200 con el resultado original |
| GET /miec/operations/{key} | Consulta sin efecto; resultado 200 o ausencia contractual 404 |
| Misma clave y otro contenido con huella válida | 409, sin sobrescribir el resultado |
| Huella/UTF-8/propietario incorrectos | 400, sin nueva visita ni reserva permanente |
| API key incorrecta | 401 |

Las rutas requieren X-MIEC-Api-Key. MIEC genera la clave estable y los encabezados
de ejecución. Las sondas directas utilizan OperationPayload del SDK real para
calcular la huella; no duplican su algoritmo.

PetClinic confirma reserva, visita y resultado en su propia transacción. La
deduplicación cubre la misma clave/cuerpo: claves distintas pueden crear efectos
distintos. El fencing remoto solo se exige positivo y se registra; no se acredita
rechazo general de generaciones obsoletas.

En el caso de respuesta perdida, MIEC conserva UNKNOWN y rechaza finish y un
reenvío ciego. Tras reiniciar ambos servidores, AUTHOR solicita RECONCILE_ACTION
y APPROVER lo aprueba. La reconciliación consulta al destinatario autenticado,
registra evidencia y resolución APPLIED y permite finalizar en E08. El estado
técnico original UNKNOWN permanece en el historial: no es una incertidumbre
efectiva pendiente cuando existe su resolución autorizada.

NOT_FOUND, fallo de autenticación, timeout o contradicción no acreditan NO_EFFECT.
Ninguna observación SQL del harness modifica estados, permisos o resoluciones MIEC.

## 4. Indicador y evaluación

Indicador: **visitas adicionales**. Unidad `count`, operador `EQ`, objetivo **0**.
Para cada marcador de ronda:

- V: visitas con mascota, fecha y descripción exactos.
- K: filas del ledger para la operationKey.
- Valor registrado: `max(0, V - 1)`.
- Aceptación de efecto: **V=1 y K=1**, además de conservar ID, huella y referencia.

La línea base observa realmente V=0 antes de ejecutar; no se inventa una línea
base 1 para simular mejora. La medición posterior ocurre después de finish/E08.
Las fechas se obtienen del reloj PostgreSQL, en UTC y con precisión de microsegundos.
Cada observación conserva consulta, parámetros efectivos, conteos y fecha.

Método: `MVP08-LOCAL-COUNT-V1`. Las dos muestras y sus revisiones se seleccionan
explícitamente antes de cerrar cada conjunto. Las evidencias permanecen
HUMAN_REPORT/UNVERIFIED; SOURCE-QUALITY-V1 produce RS-05 INCONCLUSIVE.
Conservar el criterio 0 → 0 no demuestra una mejora causal del sistema.

## 5. Casos y expediente

| ID | Validación requerida |
|---|---|
| LP-01 | Dos JAR PostgreSQL, SDK fijado y propietario/mascota nuevos por HTTP |
| LP-02 | Plan autorizado, ejecución APPLIED, E08 y V=1/K=1 |
| LP-03 | Consultas repetidas sin cambiar resultado ni conteos |
| LP-04 | Dos starts MIEC concurrentes: 201/409; ocho replays idénticos: 200 y una visita |
| LP-05 | Conflicto de contenido 409, conservando visita y resultado originales |
| LP-06 | Rechazos de propietario, huella, UTF-8 y credencial sin efectos nuevos |
| LP-07 | Corte después de commit, UNKNOWN durable y reconciliación autorizada |
| LP-08 | Reinicio independiente de ambos JAR conserva identidad y resultados |
| LP-09 | Continuidad pendiente conservada al reiniciar; E11 → E02 y contexto nuevo |
| LP-10 | Dos rondas, snapshots anteriores intactos y cierre E14 |
| LP-11 | Rol 403, versión/permisos 409 y referencia de ciclo ajeno 400 |
| LP-12 | Respaldo del par y restauración en dos bases nuevas; mismos recursos y conteos |

El expediente queda fuera del repositorio, por RUNID, en
`%LOCALAPPDATA%/MIEC/pilots/RUNID`:

- `manifest.json`: commits, estado Git y diffs sin properties privados; coordenadas
  Maven y SHA-256 de los tres artefactos; datos propios; puertos; siete procesos;
  IDs de ciclo, rondas, acciones, permisos, operaciones y conclusiones.
- `cases.csv` y `acceptance.md`: los doce resultados técnicos y sus límites.
- `requests/`, `responses/`, `measurements/`, `contexts/`, `faults/`, `logs/`:
  cuerpos exactos y pruebas, sin encabezados Authorization ni API keys.
- `artifacts/`, `harness/`, `sources/`: binarios sin passwords privados
  predeterminados, programa ejecutado y evidencia de versiones/cambios. Los
  profiles saneados se extraen del JAR a `sources/packaged-config/`; los properties
  privados del árbol de trabajo no se exportan.
- `backups/`, `checkpoint/`: dumps del par y recursos antes/después de restaurar.
- `maven-version.txt`, `java-version.txt`, `inventory.json` y
  `inventory-sha256.txt`: herramientas e integridad de los archivos conservados.

Los hashes permiten detectar cambios; no constituyen una firma digital ni
almacenamiento inmutable. La fecha añadida a cada caso es la última escritura de
su evidencia HTTP, no una hora de commit de negocio inferida.

## 6. Reproducción y restauración

Requisitos: JDK 21 completo, Python 3.11+, Maven/wrappers, SDK instalado, Docker
Desktop/WSL2 y contenedor `postgres14` con PostgreSQL 14 en 127.0.0.1:5532.
Las bases y esquemas deben estar previamente preparados. Coordinar una ventana
sin otras aplicaciones escribiendo en estas bases.

Las credenciales locales se leen en memoria desde los profiles PostgreSQL o sus
variables de entorno. El harness genera secretos de transporte y dos cuentas
temporales, los entrega por entorno y no los publica en el expediente.
Ver [configuración PostgreSQL](POSTGRESQL-local.md).

Desde la raíz MIEC:

```powershell
# Compila y empaqueta recursos de build saneados; no cambia los properties locales.
.\tools\pilot\prepare-artifacts.ps1 -LocalRepository C:/R -Offline

# Crea datos de negocio reales y un expediente nuevo; conserva los originales.
python tools/pilot/run_local_project.py --run --sdk-jar C:/R/com/miec/miec-recipient-sdk/0.1.0-SNAPSHOT/miec-recipient-sdk-0.1.0-SNAPSHOT.jar

# Usar la carpeta EVIDENCE que imprimió el comando anterior.
python tools/pilot/finalize_evidence.py "RUTA_DEL_EXPEDIENTE" --maven-executable ".\mvnw.cmd"
```

Puede indicarse `-MavenExecutable` al preparador y `--java` al demostrador.
No ejecutar otro package/verify entre preparar y ejecutar: podría volver a copiar
defaults privados a los recursos del JAR. Si se reconstruye, repetir el preparador.
El demostrador rechaza artefactos con passwords PostgreSQL privados predeterminados.

La restauración LP-12 cierra ambas aplicaciones mediante el MBean administrativo
local de Spring, accesible por attach de la misma cuenta del sistema, sin puerto
JMX remoto. Ejecuta pg_dump de las dos bases del checkpoint, registra sus hashes
y usa pg_restore sobre dos bases nuevas con nombres `mvp08_restore_*`.
No restaura sobre las originales ni borra tablas o datos para volver a enviar.

Reinicia los mismos JAR con las bases restauradas y el mismo enlace del destinatario.
Compara ciclo, rondas, ejecución, mediciones, evaluaciones e historial consultado;
consulta y replay mantienen los recibos y V=1/K=1. Al terminar cierra todas las
JVM que creó. Las bases originales, las restauradas y el expediente permanecen
disponibles para revisión del operador.

Ante cualquier fallo, conservar el expediente FALLIDO y el estado alcanzado.
No convertir casos pendientes/bloqueados en aprobados ni editar SQL para avanzar.
La entrega técnica posterior es `v0.1.0`; la aceptación funcional permanece
pendiente de firma. Para operación ordinaria y recuperación fuera del harness,
seguir la [guía conjunta](LOCAL-installation-operations.md).
