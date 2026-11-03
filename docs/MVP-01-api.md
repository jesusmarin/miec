# MVP-01: sistemas y ciclos mediante API local

Implementación en seis pasos: comandos, servicios de persistencia, API de creación y
consulta, inicio con reglas de estados, historial y validación completa.

| Ruta | Resultado |
|---|---|
| POST /api/systems | 201 y Location; nombre obligatorio, dominio/descripción/responsable opcionales. |
| GET /api/systems/{systemId} | 200 o 404. |
| POST /api/systems/{systemId}/cycles | 201 y Location; JSON {} o configurationVersionId opcional. |
| GET /api/cycles/{cycleId} | 200 o 404; incluye version. |
| POST /api/cycles/{cycleId}/start | 200; JSON expectedVersion y reason obligatorios. |
| GET /api/cycles/{cycleId}/history | Página de transiciones con limit 1..100 (50 por defecto), after UUID opcional. |

El servidor asigna UUID, número de ciclo, fechas, estado inicial E00_INACTIVO y A0.
No se aceptan estados arbitrarios desde JSON. Ciclos y solicitudes de inicio llevan
X-MIEC-Actor, identidad declarada para auditoría local; no equivale a autenticación.
Registrar un sistema no concede permisos sobre acciones o planes. El MVP no expone
los servicios de ejecución externa ni modifica sus autorizaciones.

Los comandos se normalizan y validan también al invocar los servicios directamente.
Se reutilizan las tablas existentes. V010 agrega reason nullable a cycle_event
porque payload_reference no debe utilizarse como almacén de motivos. Los eventos
históricos conservan reason null. El estado, la versión y su auditoría se guardan
juntos; la versión evita comenzar dos veces el mismo ciclo.

El historial devuelve transiciones, no todos los eventos. Antes del primer inicio
está vacío. La creación conserva su evento CYCLE_CREATED interno. El cursor debe
pertenecer al ciclo consultado. Los errores distinguen 400, 404, 409 y fallos internos
500 sin exponer SQL o trazas. El ámbito inicial es local; autenticación de usuarios
y administración de permisos REST son incrementos posteriores.

## Ejecutar y demostrar

Iniciar MIEC desde el IDE o, en la raíz del proyecto:

```powershell
.\mvnw.cmd spring-boot:run
```

La instancia debe cargar el código nuevo y aplicar V010. Si había una instancia
anterior, reiniciarla desde el IDE. El puerto predeterminado es 8081 y la dirección
local 127.0.0.1; MIEC_SERVER_ADDRESS permite cambiarla explícitamente. Esta entrega
no incluye autenticación REST: X-MIEC-Actor registra una identidad declarada, no una
identidad autenticada. No se exponen comandos de planes o ejecución externa.

Con la aplicación iniciada, ejecutar manualmente:

```powershell
powershell -NoProfile -File .\docs\mvp01-demo.ps1
```

El guion crea un sistema y un ciclo nuevos en cada ejecución, inicia el ciclo y
consulta el historial. Devuelve IDs, E01_INICIALIZANDO, versión 1 y una transición.
Admite -BaseUrl y -Actor para una instancia local distinta. Su sintaxis se validó;
la prueba automática equivalente usa archivos y puertos aislados.

Ejemplo independiente del comando de inicio, utilizando el ciclo ya consultado:

```http
POST /api/cycles/{cycleId}/start
Content-Type: application/json
X-MIEC-Actor: operador-local

{"expectedVersion":0,"reason":"Iniciar el ciclo de observación"}
```

Si la versión cambió, 409 VERSION_CONFLICT exige consultar el ciclo antes de volver
a decidir. Una versión actual no permite repetir un inicio desde E01: 409
TRANSITION_NOT_ALLOWED. La versión debe ser un entero no negativo; no se truncan
decimales. JSON con atributos desconocidos se rechaza con 400. Las creaciones POST
no son idempotentes: no reenviarlas automáticamente tras una respuesta incierta.

## Historial y consistencia

El historial se ordena por occurredAt y transitionId. nextCursor null indica fin;
si existe se utiliza como after en la siguiente consulta. Los registros históricos
sin evento o motivo conservan esos campos en null. No se fabrica auditoría antigua.
La lectura utiliza una transacción de aislamiento repetible y no modifica el ciclo.

Crear ciclos bloquea el sistema para asignar números distintos dentro del flujo
API. Iniciar bloquea sistema y ciclo en ese orden, comprueba versión/regla F01 y
confirma estado, startedAt, incremento de versión, evento y transición juntos.
Estos controles no coordinan escrituras SQL directas que omitan los servicios.

## Validaciones realizadas por paso

| Paso | Resultado |
|---|---|
| 1. Contrato, comandos y V010 | 4 pruebas aprobadas; migración desde V009 conserva los eventos existentes. |
| 2. Persistencia | 6 pruebas aprobadas; incluye números concurrentes y rollback de creación/auditoría. |
| 3. API inicial | 4 pruebas HTTP aprobadas; Location, 400, 404 y rechazo de atributos arbitrarios. |
| 4. Inicio | 33 pruebas aprobadas, incluyendo reglas previas de Cycle; concurrencia y rollback. |
| 5. Historial | 9 pruebas aprobadas; fechas iguales, cursores ajenos y auditoría por HTTP. |
| 6. Regresión | 714 pruebas, cero fallos, errores u omisiones; clean package correcto. |
| 6. Persistencia entre JVM | 1 prueba explícita aprobada; misma base, IDs, versión, estado e historial en otra JVM. |
| Artefacto y guion | JAR con API/servicio/V010, sin clases experimentales ni SDK de test; guion PowerShell analizado sin errores sintácticos. |

Los conteos por paso se superponen; no deben sumarse al total de regresión.
Las pruebas usan H2 en memoria y un archivo temporal propio para el reinicio.
La prueba general de arranque también quedó aislada en memoria; no se utiliza la
base de desarrollo como fixture. El fallo SQL de la prueba de rollback es inyectado
intencionadamente: su log interno no representa un fallo de la suite.

Comandos de verificación:

```powershell
.\mvnw.cmd clean package
.\mvnw.cmd "-Dtest=MvpLifecycleRestartIT" "-Dmiec.mvp.jar=target/miec-prototype-0.0.1-SNAPSHOT.jar" test
```

MvpLifecycleRestartIT queda fuera de los patrones habituales de Surefire y necesita
un JAR construido. Inicia y termina solo JVM propias, con WRITE_DELAY=0 y directorios
bajo target/mvp01-restart-it. No acredita otros motores ni fallos de energía. Los
logs y archivos de esa prueba se conservan bajo target para inspección.

El entorno de desarrollo debe tener instalado el SDK de los experimentos para
compilar las pruebas existentes. Si falta: ejecutar el wrapper con
-f ../miec-recipient-sdk/pom.xml install. Esa dependencia sigue siendo de test y no
es necesaria para ejecutar el JAR productivo construido.

Logs locales ignorados por Git: review-mvp01-step1.log a review-mvp01-step5.log,
review-mvp01-regression.log y review-mvp01-restart.log. Los seis pasos de este
incremento están completados. El siguiente incremento funcional es gestionar
planes y autorización humana mediante la capa de aplicación y sus APIs.
