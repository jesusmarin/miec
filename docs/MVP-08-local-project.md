# MVP-08 — Contrato y validación del primer destinatario local

Contrato: **MVP08-LOCAL-01**. Fecha: 2026-11-04.

Estado: **contrato propuesto para aceptación; piloto integral pendiente de ejecución**.
Este documento no acredita una ejecución nueva ni publica una versión definitiva.
La aceptación requerirá completar las evidencias y el acta descritas abajo.

## 1. Propósito y alcance del experimento

Demostrar que el MVP actual puede coordinar una operación de un proyecto local
independiente: preparar objetivo y plan, obtener autorización, ejecutar sobre el
destinatario real, consultar el resultado, medir, evaluar conservadoramente y
decidir continuidad o cierre. Se realizarán dos rondas del mismo ciclo.

Se ejecutan los JAR de MIEC y PetClinic en procesos independientes, con H2 en
archivos separados, HTTP únicamente en loopback y datos sintéticos. El destinatario
es PetClinic real con su perfil de integración; no HttpRecipientFixture, mocks,
adaptadores experimentales de test o beans de prueba incorporados al JAR de MIEC.
Los datos de laboratorio del perfil PetClinic no sustituyen su servidor ni su
transacción de negocio: solo proporcionan un propietario y una mascota válidos.

Esta primera aceptación demuestra integración e integridad de ejecución. Registrar
visitas no constituye por sí mismo evolución del código ni mejora del proyecto.
Una validación posterior de evolución deberá fijar un cambio de código y comparar
versiones, casos de aceptación y mediciones antes/después. Aquí no se ejecutan
scripts, compilaciones o despliegues arbitrarios desde una acción MIEC.

Fuera de alcance: producción, PostgreSQL, usuarios remotos, suficiencia instrumental
EV-2/EV-3, causalidad, compensación distribuida y continuación avanzada de planes
parcialmente fallidos. UNKNOWN bloquea: no se completa el piloto inventando un éxito.

### Responsabilidades

| Proyecto | Responsabilidad en este contrato |
|---|---|
| miec-prototype | Ciclo, ronda, plan, permisos, operación estable, intentos, consulta/reconciliación, medición, evaluación y auditoría |
| spring-petclinic | Validar visita, autenticar transporte, deduplicar y confirmar visita/resultado en una transacción propia |
| miec-recipient-sdk | Modelos y huella UTF8-V1 consumidos por PetClinic; no instala persistencia ni servidor |

MIEC usa su adaptador HTTP genérico configurado en el servidor. No se trasladan
clases PetClinic desde src/test a src/main. AUTHOR y APPROVER son identidades
distintas; registrar si representan dos personas o una simulación local de roles.

## 2. Proyecto destinatario y operación seleccionada

Destinatario: `spring-petclinic`, rama prevista `miec/experimento-001`, perfil `miec`.
La rama es una referencia de trabajo: fijar el commit exacto al preparar el piloto.
Nombre lógico del destino: `petclinic-experiment-001` en ambos servidores.

Operación: **registrar una visita futura para una mascota de un propietario válido**.
El servicio exige ownerId/petId positivos y correspondientes, fecha posterior al
día actual de PetClinic y descripción no vacía de hasta 255 caracteres.

El perfil existente contiene ownerId=900001 y petId=900001. Antes de utilizarlos,
verificar su relación en la base aislada; no asumir esos IDs en otra instalación.
Generar una fecha siete días posterior al día del servidor y registrar zona horaria.

Ejemplo de cuerpo, que se genera una sola vez por ronda:

```json
{"ownerId":900001,"petId":900001,"date":"FECHA-FUTURA-YYYY-MM-DD","description":"MVP08-RUNID-R1"}
```

Sustituir fecha y RUNID antes de aprobar. La descripción contiene un marcador
único por caso/ronda para contar efectos de negocio, incluidos posibles duplicados
que no quedaran enlazados al ledger. La segunda ronda usa otro marcador y otra
operación, con plan, acciones y permisos nuevos.

## 3. Contrato de ejecución, consulta, deduplicación y recuperación

### Transporte e identidad

| Operación | Ruta de PetClinic | Resultado esperado |
|---|---|---|
| Ejecutar primera vez | POST /miec/visits | 201, resultado APPLIED confirmado |
| Repetir misma operación/cuerpo | POST /miec/visits | 200, mismo resultado durable |
| Consultar sin efecto | GET /miec/operations/{operationKey} | 200, resultado original; 404 OPERATION_NOT_FOUND si no se observa resultado confirmado |

Ambas rutas exigen X-MIEC-Api-Key. POST añade Content-Type: application/json,
Idempotency-Key, X-MIEC-Destination, X-MIEC-Fingerprint y X-MIEC-Fencing-Token.
La clave y los encabezados de ejecución los prepara MIEC, no el operador del plan.
Una respuesta confirmada debe conservar status, operationKey, destinationId,
fingerprint y resultReference; PetClinic añade visitId y recordedAt.

El cuerpo aprobado se conserva exactamente como UTF-8. La huella corresponde a
destino, media type y cuerpo bajo UTF8-V1; no es simplemente SHA-256 del JSON ni
se recalcula normalizando sus espacios. Para sondas directas de contrato, utilizar
ExecutionRequest o OperationPayload del SDK para calcularla correctamente. Estas
sondas se registran separadas de las operaciones del ciclo MVP.

Misma clave con otro cuerpo y su huella correcta: 409 OPERATION_CONFLICT. Una
huella declarada falsa: 400 FINGERPRINT_MISMATCH. JSON/UTF-8/campos incompatibles
se rechazan; no se permite crear una visita o reservar permanentemente una clave
como consecuencia del rechazo. La respuesta de error no acredita por sí sola
ausencia de efecto: comprobar los conteos del caso.

### Persistencia y límites

PetClinic confirma la fila de miec_visit_operations, la visita y su resultado en
una transacción de la misma base. La clave es única y el resultado no se reescribe.
Dos solicitudes concurrentes con la misma clave/cuerpo deben recuperar la misma
visita; no hay transacción distribuida entre MIEC y PetClinic.

La garantía se limita a la misma clave y cuerpo. Dos claves distintas con contenido
igual representan dos operaciones y pueden crear dos visitas. No hay deduplicación
semántica por propietario, mascota o fecha. El fencing actual se exige positivo y
se registra; no se acredita rechazo remoto general de generaciones obsoletas.
No extender esta garantía de visitas a un futuro adaptador de despliegue de código.

Las filas de deduplicación se conservarán durante todo el experimento y sus
reintentos. Borrarlas para volver a ejecutar invalidaría la prueba.

### Recuperación

Ante timeout, desconexión o caída, consultar primero
`GET /api/plans/{planId}/execution`. Conservar planId, runId, actionId,
operationKey, executionId y las versiones observadas. No solicitar otro start ni
generar una clave distinta para intentar resolver incertidumbre.

- Si IR-009 ya tiene el resultado confirmado, usar recover y revisar finish.
- Si falta confirmación, esperar fin de propiedad del coordinador, solicitar
  RECONCILE_ACTION, aprobarlo con otra identidad y usar reconcile. El adaptador
  instalado consulta PetClinic mediante GET; después se recupera la proyección.
- finish requiere todas las acciones APPLIED sin contradicciones; mueve a E08.
  La recuperación de un efecto confirmado no exige renovar EXECUTE_ACTION.
- NOT_FOUND, UNKNOWN, fallo de autenticación, timeout o contradicción mantienen
  el bloqueo. Un 404 no equivale a NO_EFFECT_CONFIRMED.
- Si los permisos necesarios vencieron, seguir las capacidades existentes o
  detener el caso como limitación; no editar SQL, prorrogar permisos ni reenviar
  efectos pendientes fuera del procedimiento autorizado.

Rutas, roles y DTO de recuperación: [MVP-03](MVP-03-finalization-recovery.md).
El resultado recuperado debe mantener clave, huella, visitId y resultReference.

## 4. Indicador inicial, criterio de éxito y medición

Indicador MIEC: **visitas adicionales para la operación del caso**.
Unidad: `count`. Comparación: `EQ`. Umbral: **0**.

Definir V como el número de visitas con el petId, fecha y marcador exactos del
caso. Medición: `max(0, V - 1)`. Para aceptar una ejecución APPLIED también se
exigen **V=1, K=1** y correspondencia de visitId/referencia, donde K es el número
de filas del ledger para la operationKey. El indicador cero por sí solo no basta:
también sería cero si la operación no hubiera creado ninguna visita.

1. Antes de ejecutar, fijar cuerpo y marcador y comprobar V=0 en PetClinic.
   Registrar línea base 0 con la consulta, resultado, autor y hora UTC de observación.
   La clave aún puede no estar asignada; se incorpora al manifiesto cuando MIEC
   reserve el run. No inventar un valor inicial 1 para simular una mejora.
2. Después de finish/E08, consultar PetClinic y medir V y K. Guardar los resultados
   crudos y registrar en MIEC el valor calculado con el mismo método y unidad.
3. Después de concurrencia, repetición o reinicio, volver a medir: deben conservarse
   V=1 y K=1 para la operación exitosa. Casos inválidos nuevos: V=0, K=0.
4. En cada ronda seleccionar explícitamente baselineId/measurementId, ventanas y
   revisiones; cerrar el conjunto y evaluar sin escoger muestras implícitas.

Consultas parametrizadas sobre la base de PetClinic:

```sql
SELECT COUNT(*) AS visit_count
FROM visits WHERE pet_id = ? AND visit_date = ? AND description = ?;

SELECT COUNT(*) AS operation_count
FROM miec_visit_operations WHERE operation_key = ?;

SELECT o.operation_key, o.destination_id, o.fingerprint, o.visit_id,
       o.result_reference, v.pet_id, v.visit_date, v.description
FROM miec_visit_operations o JOIN visits v ON v.id = o.visit_id
WHERE o.operation_key = ?;
```

Para medir durante la ejecución, usar una sesión autorizada del mismo servidor H2
de PetClinic si está disponible. Si el archivo está bloqueado para otro proceso,
detener PetClinic de forma controlada, consultar el archivo sin concurrencia y
cerrar esa conexión antes de reiniciar con el mismo archivo. MIEC permanece
disponible para registrar la medición. No añadir AUTO_SERVER ni abrir una consola
SQL remota improvisada para evitar el bloqueo. Una copia en frío permite verificar
conteos finales, pero no representa una observación nueva del sistema activo.

Método: `MVP08-LOCAL-COUNT-V1`. Cada evidencia identifica RUNID, ronda, base,
consulta/parámetros, cuerpo aprobado, versión/artefacto, observación y recepción.
Fechas UTC de máximo seis decimales; la medición posterior se observa después de
la finalización técnica. Registrar reinicios usados para medir y conservar el
estado del archivo. Las ventanas deben incluir las muestras efectivamente elegidas.

La recolección manual se registra como HUMAN_REPORT/UNVERIFIED. Una consulta SQL
o una revisión humana no convierte automáticamente la fuente en instrumental
verificada. Bajo SOURCE-QUALITY-V1 se espera RS-05 INCONCLUSIVE. La aceptación
de integridad de este piloto es independiente de esa evaluación conservadora.
Una línea base 0 y resultado 0 demuestran conservación del criterio, no mejora.

## 5. Casos de prueba y evidencias esperadas

Todos los casos empiezan en estado **PENDIENTE**. No reutilizar marcadores entre
casos de rechazo, sondas directas y operaciones de las rondas MVP.

| ID | Caso | Evidencia mínima para aprobar |
|---|---|---|
| LP-01 | Configuración y datos | Dos JAR reales, cuentas y destino comprobados; V=0; propietario/mascota válidos |
| LP-02 | Primer plan completo | Autorización revisada, run APPLIED, E08, visita/referencia correspondientes y V=1/K=1 |
| LP-03 | Consulta repetida | GET no cambia conteos ni resultado |
| LP-04 | Repetición y concurrencia | Misma clave/cuerpo; un único resultado y V=1/K=1; conservar todas las respuestas |
| LP-05 | Conflicto de contenido | Misma clave con otro cuerpo y huella válida: 409; visita y resultado originales intactos |
| LP-06 | Validación de negocio y transporte | Cuerpo/huella inválidos, mascota ajena o credencial inválida: rechazo esperado y cero efectos nuevos |
| LP-07 | Respuesta perdida tras commit | Visita confirmada antes de perder respuesta; MIEC conserva incertidumbre hasta consultar/reconciliar; V=1/K=1 al resolver |
| LP-08 | Reinicio independiente | Reiniciar cada aplicación con su archivo; conservar identidades, resultado, evidencia y conteos |
| LP-09 | Continuidad de segunda ronda | Solicitud y aprobación independientes; E11→E02; nuevas muestras, diagnóstico, plan, acciones, permisos y clave |
| LP-10 | Dos rondas y cierre | Dos visitas deliberadas con marcadores distintos, una por operación; snapshots anteriores intactos; E14 sin incertidumbres pendientes |
| LP-11 | Controles negativos MIEC | Rol incorrecto 403, referencia ajena y versión obsoleta rechazadas sin efectos adicionales |
| LP-12 | Restauración | Par de bases del mismo checkpoint restaurado en otra carpeta; resultados y conteos conservados sin nuevos envíos |

LP-04 y LP-07 necesitan un cliente/harness de contrato y un mecanismo de fallo
controlado. Para respuesta perdida, el corte debe ocurrir **después de observar
el commit** en PetClinic. Un timeout anterior al commit no prueba este caso.
Puede usarse un proxy de fallo local documentado; el destino y sus datos siguen
siendo PetClinic real. Conservar configuración y binario/versión del harness.
Las pruebas históricas no sustituyen la ejecución de estos casos con los JAR del
manifiesto. Hasta disponer del mecanismo, marcar esos casos PENDIENTE, no APROBADO.

### Expediente y acta

Fuera del repositorio, conservar un directorio por RUNID con:

- `manifest.json`: commits de los tres proyectos, árboles limpios o diff adjunto,
  versiones Maven reales, SHA-256 de JAR/SDK, JDK/Maven, zona horaria, puertos,
  rutas de bases, esquema Flyway y responsables. No incluir secretos.
- `cases.csv`: ID, estado PENDIENTE/APROBADO/FALLIDO/BLOQUEADO, resultado esperado,
  observado, fecha y archivos de evidencia. Casos bloqueados no cuentan como aprobados.
- `requests/`, `responses/`, `measurements/`, `logs/`: cuerpos exactos, resultados,
  consultas, conteos y registros saneados, sin Authorization ni API keys.
- IDs de sistema, ciclo, ronda, plan, acción, autorización, run, operación, intento,
  evidencia, conjunto, evaluación, decisión, aprendizaje y solicitud de continuidad.
- `acceptance.md`: escenario, casos aprobados, pendientes, defectos, limitaciones,
  restauración y aceptación por los responsables del experimento.

Aceptar el contrato cuando LP-01–LP-12 estén aprobados con evidencia coherente,
sin incertidumbres abiertas y con restauración probada. Las exclusiones de alcance
se conservan en el acta. La aceptación parcial se denomina parcial y no cierra el
contrato ni habilita etiquetar una entrega definitiva como aceptada.

## 6. Procedimiento de ejecución y restauración

### 6.1 Fijar versiones y construir

Desde la raíz de MIEC, registrar los commits de los tres repositorios, estado Git
y cambios locales antes de construir. Conservar versiones reales: actualmente
MIEC y PetClinic usan SNAPSHOT; este documento no cambia sus POM ni crea un tag.
Usar Java 21 y el mismo repositorio Maven local para instalar/consumir el SDK.

```powershell
.\mvnw.cmd -f ../miec-recipient-sdk/pom.xml clean install
.\mvnw.cmd -f ../spring-petclinic/pom.xml '-Dtest=MiecVisitIntegrationTests' package
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar' test

$MiecJar = (Resolve-Path './target/miec-prototype-0.0.1-SNAPSHOT.jar').Path
$PetClinicJar = (Resolve-Path '../spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar').Path
$SdkJar = (Resolve-Path '../miec-recipient-sdk/target/miec-recipient-sdk-0.1.0-SNAPSHOT.jar').Path
Get-FileHash -Algorithm SHA256 -LiteralPath $MiecJar, $PetClinicJar, $SdkJar
```

Si cambia una versión, actualizar las rutas con el nombre real del artefacto y
registrarlo. Los comandos son preparación y regresión; no ejecutan LP-01–LP-12
por sí solos. Los IT antiguos de PetClinic siguen disponibles como antecedentes:
[validación SDK/PetClinic](IR-009-C09-sdk-validation.md).

### 6.2 Preparar archivos y credenciales

Elegir una carpeta nueva, fuera de data, target y los datos habituales de PetClinic.
No usar el directorio target como almacenamiento durable: Maven clean lo elimina.

```powershell
$RunId = 'MVP08-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$PilotRoot = Join-Path $env:LOCALAPPDATA "MIEC/pilots/$RunId"
New-Item -ItemType Directory -Path $PilotRoot -Force | Out-Null
$PilotDbPath = $PilotRoot.Replace('\','/')
```

Conservar RUNID/PilotRoot para las dos consolas y para todos los reinicios. Las
claves se introducen fuera del historial, por ejemplo mediante Get-Credential;
solo se copian sus contraseñas a variables de entorno de los procesos del piloto.
La API key compartida debe tener al menos 16 caracteres ASCII visibles, sin espacios.
No exportar el entorno completo al manifiesto ni incluir credenciales en URLs.

| Consola | Variables a proporcionar de forma segura |
|---|---|
| MIEC | MIEC_AUTHOR_USERNAME, MIEC_AUTHOR_PASSWORD, MIEC_APPROVER_USERNAME, MIEC_APPROVER_PASSWORD, MIEC_RECIPIENT_API_KEY |
| PetClinic | MIEC_PETCLINIC_API_KEY, con la misma clave de transporte |

Las cuentas AUTHOR/APPROVER deben tener nombres distintos. No registrar como
revisión independiente por dos personas una sesión en la que una sola persona
opera ambas cuentas. Las variables de transporte no sustituyen esas identidades.

Ejemplo de carga interactiva en la consola de MIEC; no escribir contraseñas
literales en los comandos ni imprimir estas variables:

```powershell
$AuthorCredential = Get-Credential -UserName 'author' -Message 'Cuenta AUTHOR del piloto'
$ApproverCredential = Get-Credential -UserName 'approver' -Message 'Cuenta APPROVER del piloto'
$TransportCredential = Get-Credential -UserName 'mvp08-transport' -Message 'Clave compartida ASCII de al menos 16 caracteres'
$env:MIEC_AUTHOR_USERNAME = $AuthorCredential.UserName
$env:MIEC_AUTHOR_PASSWORD = $AuthorCredential.GetNetworkCredential().Password
$env:MIEC_APPROVER_USERNAME = $ApproverCredential.UserName
$env:MIEC_APPROVER_PASSWORD = $ApproverCredential.GetNetworkCredential().Password
$env:MIEC_RECIPIENT_API_KEY = $TransportCredential.GetNetworkCredential().Password
```

En la consola de PetClinic, introducir la misma contraseña de transporte:

```powershell
$TransportCredential = Get-Credential -UserName 'mvp08-transport' -Message 'Misma clave de transporte del piloto'
$env:MIEC_PETCLINIC_API_KEY = $TransportCredential.GetNetworkCredential().Password
```

Estas cuentas son las configuradas al iniciar MIEC, no usuarios del sistema
operativo. Las consolas deben conservar las variables hasta terminar cada proceso;
cerrarlas al finalizar el piloto evita reutilizar accidentalmente su entorno.

### 6.3 Arrancar dos procesos reales

En la consola de PetClinic, establecer PilotRoot/PilotDbPath/PetClinicJar con los
valores ya fijados y cargar la clave de transporte antes de arrancar:

```powershell
$env:MIEC_PETCLINIC_DESTINATION = 'petclinic-experiment-001'
$env:MIEC_PETCLINIC_DB_URL = "jdbc:h2:file:$PilotDbPath/petclinic;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
& java '-Duser.timezone=America/Bogota' -jar $PetClinicJar '--spring.profiles.active=miec' '--server.port=8082'
```

En otra consola, cargar las cuatro variables de cuentas y la misma clave de
transporte, y establecer las rutas del mismo piloto:

```powershell
$env:MIEC_DB_URL = "jdbc:h2:file:$PilotDbPath/miec;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
$env:MIEC_RECIPIENT_ENABLED = 'true'
$env:MIEC_RECIPIENT_ADAPTER_ID = 'http-recipient-v1'
$env:MIEC_RECIPIENT_ADAPTER_VERSION = '1'
$env:MIEC_RECIPIENT_DESTINATION_ID = 'petclinic-experiment-001'
$env:MIEC_RECIPIENT_BASE_URL = 'http://127.0.0.1:8082'
$env:MIEC_RECIPIENT_EXECUTE_PATH = '/miec/visits'
$env:MIEC_RECIPIENT_TIMEOUT = 'PT10S'
& java '-Duser.timezone=America/Bogota' -jar $MiecJar '--server.port=8081' '--server.address=127.0.0.1'
```

Comprobar puertos libres y ausencia de otra instancia que utilice esos archivos.
Swagger de MIEC: `http://127.0.0.1:8081/swagger-ui/index.html`, autenticado.
GET /api/execution-adapters debe mostrar el destino y enlace esperados.
El GET de una clave nueva en PetClinic con la API key debe devolver el 404
contractual sin efectos. No activar consolas H2 remotas ni perfiles de prueba.

### 6.4 Ejecutar el recorrido

Usar Swagger o un cliente HTTP que conserve respuestas. Todos los comandos usan
las versiones obtenidas en la consulta más reciente, no números copiados del ejemplo.
Guardar cada ID y Location. Consultar el catálogo instalado antes de preparar el plan.

1. Crear sistema y ciclo, iniciar y definir el objetivo medible de la sección 4.
2. Medir V=0 con marcador nuevo y registrar la línea base; cerrar observación y
   diagnosticar que se verificará el registro autorizado sin duplicación.
3. Crear un plan con una acción PROCESS_EXECUTION. Su execution usa adapterId
   http-recipient-v1, adapterVersion 1, destino petclinic-experiment-001,
   mediaType application/json y body igual al texto de visita ya fijado.
4. AUTHOR solicita EXECUTE_ACTION; APPROVER revisa `/api/plans/{planId}/context`
   y aprueba explícitamente. Conservar contenido y huella aprobados.
5. AUTHOR ejecuta el plan. Consultar run/paso, operationKey y PetClinic. Si hay
   incertidumbre, seguir la sección 3; no avanzar hasta resolver o bloquear el caso.
6. APPROVER llama finish con versiones del run y ciclo. En E08 medir y registrar
   el resultado posterior con runId y método MVP08-LOCAL-COUNT-V1; revisar y sellar
   el par de muestras, con sus fechas reales. Evaluar y conservar RS-05 esperada.
7. En la primera ronda, seleccionar COLLECT_EVIDENCE, registrar aprendizaje,
   registrar rounds/initial y concluir el plan. Solicitar continuidad y aprobarla
   con otra identidad; comprobar nueva ronda en E02 y snapshot anterior intacto.
8. Repetir con marcador, muestras, diagnóstico, plan, acciones, permisos y clave
   nuevos. El ciclo crea dos visitas deliberadas: una para cada marcador/operación.
   Las visitas de sondas separadas se contabilizan en sus propios casos y no se
   incluyen en ese total; no usar COUNT(*) global de visits como indicador del ciclo.
9. En la segunda ronda seleccionar CLOSE_CYCLE y aprender. El contexto se congela
   automáticamente: no repetir rounds/initial. Concluir el segundo plan y cerrar
   E14 con su learningId. Revisar ambos snapshots y ausencia de incertidumbre.
10. Ejecutar y documentar LP-03–LP-08 y LP-11 en casos aislados. Guardar LP-07 con
    su mecanismo de fallo. Verificar LP-12 antes de firmar el acta.

Detalles de DTO/rutas: [preparación](MVP-02-intervention.md),
[ejecución](MVP-03-plan-execution.md), [medición posterior](MVP-05-post-measurements.md),
[evaluación/cierre](MVP-06-decision-closure.md) y [continuidad](MVP-07-continuity.md).
Los scripts mvp01/mvp02/mvp03 existentes son ayudas parciales, no un demostrador
completo MVP-08; no utilizarlos con mediciones inventadas como evidencia del piloto.

### 6.5 Respaldo y restauración

1. Registrar un checkpoint conocido, IDs, conteos y respuestas. Si hay incertidumbre,
   conservarla en el expediente; una copia de archivos no la resuelve.
2. Detener de forma controlada **ambos procesos del piloto** y esperar su salida.
   Comprobar que los archivos están liberados antes de consultar/copiar. No copiar
   archivos H2 mientras alguna JVM o herramienta SQL los mantenga abiertos.
3. Copiar `miec.mv.db` y `petclinic.mv.db` del mismo checkpoint a una carpeta de
   respaldo nueva y registrar SHA-256 de ambos, junto con el manifiesto y artefactos.
   No copiar credenciales, archivos de bloqueo ni mezclar checkpoints de las dos bases.
4. Restaurar copiando ese par a **otra carpeta nueva**, conservando intacto el original.
   Comprobar hashes antes de arrancar. Actualizar únicamente las rutas de ambas bases
   en las consolas del piloto; conservar destino, JAR y configuración del manifiesto.
5. Arrancar ambos procesos con el par restaurado. Consultar runs, resultados, rondas
   y conteos; comparar con el checkpoint. Consultar repetidamente no crea visitas.
   Ejecutar cualquier replay de contrato solo con la clave/cuerpo originales y
   comprobar que conserva referencia y V=1/K=1.
6. Si un archivo no corresponde al manifiesto, falta el ledger o una operación está
   incierta, detener la aceptación y registrar el caso como FALLIDO/BLOQUEADO.

No eliminar visitas ni filas de deduplicación para simular una restauración.
No restaurar una sola base para reintentar una operación. Las copias y reinicios
acreditan el escenario probado, no tolerancia a pérdida de energía del equipo.

### Cierre documental

| Elemento | Estado al crear este contrato |
|---|---|
| Operación y contrato contrastados con el código | Revisados |
| JAR/commits del piloto fijados en manifest.json | Pendiente |
| LP-01–LP-12 ejecutados contra ambos JAR reales | Pendiente |
| Respaldo/restauración del par de bases acreditados | Pendiente |
| Acta y versión definitiva aceptadas | Pendiente |

Antecedentes: [PetClinic C09](IR-009-C09-petclinic.md),
[SDK/PetClinic](IR-009-C09-sdk-validation.md), [trazabilidad](MVP-v01-traceability.md).
Los resultados anteriores conservan su alcance histórico y no se trasladan como
aprobaciones de este nuevo contrato.
