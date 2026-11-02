# IR-009-C09 — experimento MIEC–PetClinic

## Contrato mínimo

PetClinic: rama miec/experimento-001, perfil miec, proceso y base separados.
MIEC: adaptador JDK HttpClient para IdempotentActionExecutor.

- POST /miec/visits, Content-Type application/json.
- GET /miec/operations/{key}, solo consulta; nunca crea filas ni visitas.
- X-MIEC-Api-Key: secreto compartido del experimento, mínimo 16 caracteres,
  configurado por entorno, sin valor predeterminado utilizable.
- Idempotency-Key: UUID de execution_operation.
- X-MIEC-Destination: petclinic-experiment-001 por defecto.
- X-MIEC-Fingerprint: SHA-256 calculado por ambas aplicaciones: campos UTF-8
  UTF8-V1, destino, application/json, cuerpo exacto, cada uno precedido por su
  longitud en bytes como entero big-endian de 32 bits.
- X-MIEC-Fencing-Token: generación positiva, trazabilidad únicamente. C09 no
  afirma que PetClinic implemente fencing de otros efectos o recursos.

Cuerpo: ownerId y petId positivos, date ISO yyyy-MM-dd futura para la primera
creación, description no vacía de hasta 255 caracteres. La mascota debe pertenecer
al propietario. Un replay confirmado no vuelve a validar la fecha contra hoy.

Resultado APPLIED: operationKey, fingerprint, visitId, resultReference
(petclinic:visit:<id>) y recordedAt. La referencia es opaca, no una URL que deba
abrirse automáticamente. Primer POST 201; repetición idéntica 200 y mismo cuerpo.
Misma clave con otra huella/cuerpo: 409 OPERATION_CONFLICT. Huella declarada que
no coincide con los bytes: 400 FINGERPRINT_MISMATCH. Autenticación inválida: 401.
GET inexistente: 404 OPERATION_NOT_FOUND; no equivale a NO_EFFECT_CONFIRMED,
pues un POST concurrente puede no haber confirmado todavía.

## Persistencia y concurrencia

Tabla miec_visit_operations propia de PetClinic, clave primaria única. Una sola
transacción reserva la clave, inserta la visita y completa la referencia. Un
fallo revierte ambos registros. El perdedor de una reserva concurrente recupera
la fila confirmada fuera de la transacción revertida; nunca continúa esa transacción.
La clave y su resultado se retienen indefinidamente durante el experimento.

El perfil miec usa H2 en archivo y DDL no destructivo, sin DROP TABLE ni reinserción
de datos que sobrescriba registros. Los fixtures usan IDs 900001 para propietario,
mascota y tipo, solo en este perfil. No cambia el perfil habitual de PetClinic.
La ruta predeterminada es miec-data/petclinic, ignorada por Git y fuera de target
para conservar las operaciones incluso al limpiar los artefactos de compilación.
Para un motor productivo se requerirá estrategia formal de migraciones y permisos.

## Secuencia de validación

1. Contrato y perfil aislados.
2. Persistencia atómica y endpoint autenticado.
3. Pruebas de creación, replay, conflicto, validación, rollback y concurrencia.
4. Adaptador MIEC: timeouts, respuesta autenticada y vínculo clave/huella.
5. Prueba entre procesos: visita única, respuesta perdida después de commit,
   recuperación de resultado y reinicio real de PetClinic con el mismo archivo.
6. Regresiones y archivos preparados en Git, sin commit ni push.

El experimento permite HTTP únicamente en loopback. Fuera del equipo se exige
HTTPS, gestión de secretos y controles adicionales; no se despliega aquí.

## Ejecución y reproducción

En PetClinic, definir MIEC_PETCLINIC_API_KEY en el entorno (mínimo 16 caracteres
ASCII visibles), y usar un puerto dedicado si ya está ejecutándose otra instancia:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=miec" "-Dspring-boot.run.arguments=--server.port=8082"
```

Construir el JAR y validar el contrato sin requerir contenedores:

```powershell
.\mvnw.cmd "-Dtest=MiecVisitIntegrationTests" package
```

Desde la raíz de MIEC, la prueba entre procesos genera su propio secreto efímero,
puertos libres y archivo bajo target/petclinic-process-it; no usa la instancia
anterior ni su base. La clase IT está fuera de los patrones habituales de Surefire:

```powershell
.\mvnw.cmd "-Dtest=PetClinicProcessIT" "-Dmiec.petclinic.jar=../spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar" test
```

Las pruebas de transporte del adaptador son PetClinicVisitExecutorTest.
El verificador PetClinicEvidenceVerifier consulta el destinatario autenticado y
solo genera evidencia EFFECT_APPLIED. Un 404 nunca produce NO_EFFECT_CONFIRMED.
La referencia de ejecución es opaca (petclinic:visit:id); no se siguen URLs de respuesta.

## Evidencia de validación

- PetClinic: 81 pruebas sin fallos en la regresión disponible sin Docker; se
  excluyeron MySqlIntegrationTests y PostgresIntegrationTests, que requieren contenedores.
- Contrato HTTP PetClinic: 4 pruebas sin fallos, también con el empaquetado final.
- Adaptador MIEC: 4 pruebas sin fallos.
- Regresión habitual de MIEC: 669 pruebas sin fallos, errores u omisiones.
- Prueba entre procesos: dos JVM sucesivas, mismo archivo, replay y referencia
  conservados; conteo SQL directo de una visita y una operación para la clave.
- Logs: review-c09-petclinic-regression.log, review-c09-petclinic-final.log,
  review-c09-adapter-tests.log, review-c09-process-it.log y review-c09-miec-regression.log.
  Son artefactos locales ignorados por Git.

No se cambiaron las migraciones ni las bases habituales de MIEC/PetClinic. No se
añadió recuperación automática, transporte productivo ni fencing distribuido.

El perfil experimental configura H2 con WRITE_DELAY 0: cada commit se escribe
en disco antes de responder. La prueba entre procesos detectó pérdida de datos
con la demora de escritura predeterminada al terminar inmediatamente la JVM;
no se añadió una espera artificial para ocultarla. Esto valida la terminación
del proceso, sin afirmar tolerancia a una pérdida de energía del equipo.

## Separación del experimento

El adaptador, verificador y pruebas PetClinic están exclusivamente en
src/test/java/com/miec/experiment/petclinic. No se incluyen en el JAR de
producción. El verificador no tiene @Service: PetClinicProcessIT lo registra
explícitamente mediante @Import. El núcleo conserva sus contratos genéricos.
ExecutionRecoveryTestSupport expone solamente soporte de test reutilizable.

Después de trasladar clases desde src/main, ejecutar clean para eliminar los
.class antiguos antes de empaquetar; cambiar el paquete por sí solo no elimina
los artefactos compilados previamente. Los comandos de pruebas anteriores
siguen funcionando por nombre simple de clase.
