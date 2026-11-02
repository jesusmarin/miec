# Contrato reutilizable de integración IR-009

MIEC coordina autorización, propiedad, vigencia, intentos y reconciliación. Cada
destinatario implementa su protocolo mediante `IdempotentActionExecutor`.
Los nombres de dominio, endpoints, credenciales y formatos externos pertenecen
al adaptador. Ningún destinatario experimental forma parte del JAR de MIEC.

## Garantías exigidas al destinatario

1. Una clave representa una solicitud inmutable: destino, tipo y bytes exactos.
2. Repetir clave y contenido devuelve la misma referencia sin repetir el efecto.
3. Reutilizar la clave con otro contenido se rechaza sin modificar el resultado.
4. La reserva de clave, el efecto y el resultado se confirman atómicamente.
5. Solicitudes concurrentes de la misma clave producen un solo efecto.
6. Una consulta no ejecuta efectos. NOT_FOUND es ausencia visible, no prueba de
   que no exista una operación en vuelo. UNKNOWN nunca permite un envío nuevo.
7. Una respuesta perdida conserva incertidumbre en MIEC. La consulta posterior
   puede confirmar el efecto sin repetirlo.
8. Claves y resultados sobreviven a reinicios y se retienen durante todo el
   horizonte de reintentos; su eliminación exige una política explícita.

El fencing de un destinatario requiere una implementación explícita: transmitir
un token no demuestra que el sistema externo lo haga cumplir.

## Confianza y evidencia

La aplicación registra fuentes `DestinationEvidenceSource` mediante configuración
de servidor. El llamador aporta identidad del intento y autorización de revisión,
no un endpoint, secreto, adaptador ni cuerpo de solicitud. MIEC reconstruye el
contexto persistido, autoriza antes de consultar y revalida el permiso al guardar.
La consulta externa ocurre sin mantener transacciones ni bloqueos JDBC.

La observación debe coincidir en clave, destino y huella. Solo APPLIED permite
registrar EFFECT_APPLIED; NOT_FOUND y UNKNOWN no prueban NO_EFFECT_CONFIRMED.
La fecha de evidencia es la fecha de observación del reloj de MIEC, no una fecha
remota usada para gobernar el lease. Las fuentes son código confiable del servidor:
su registro no sustituye autenticación remota ni una auditoría del adaptador.

## Validación por adaptador

Las pruebas reutilizables de contrato cubren consulta sin efecto, replay,
conflicto de contenido y concurrencia. El arnés de cada destinatario prepara datos
aislados y permite contar los efectos reales. No basta una respuesta simulada para
afirmar durabilidad, atomicidad o recuperación entre procesos.

PetClinic sigue siendo la referencia HTTP entre JVM. Un segundo experimento de
reservas valida la extensión del contrato con otro dominio y un ledger JDBC local;
no acredita por sí solo fallos de red ni una segunda aplicación desplegada.

## Ubicación y alcance

- Producción: contratos de ejecución, fuentes configuradas y verificación común.
- `src/test/java/com/miec/integration/contract`: pruebas de contrato reutilizables.
- `src/test/java/com/miec/experiment`: adaptadores y verificaciones experimentales.
- No se necesita una migración nueva: se reutilizan los snapshots y evidencias
  existentes. La incorporación de otro destino no modifica el coordinador.

## Incorporar otro destinatario

1. Implementar `IdempotentActionExecutor`: snapshot inmutable, consulta y envío
   con clave estable. Autenticar el servidor y validar clave, huella y referencia
   antes de aceptar una respuesta. Configurar tiempos máximos y no seguir URLs
   proporcionadas en resultados externos.
2. Implementar `DestinationEvidenceSource` con conexión y credenciales de servidor.
   Su consulta recibe el contexto persistido y devuelve una `Observation` vinculada
   a clave, destino y huella; nunca ejecuta una operación para averiguar su estado.
3. Registrar exactamente un bean de fuente por `destinationId` en la configuración
   del despliegue. Cero fuentes permite arrancar MIEC, pero rechaza verificaciones
   de destinos no instalados; fuentes duplicadas impiden arrancar por ambigüedad.
4. Llamar `DestinationEvidenceVerificationService.verifyApplied(Command)` con el ID
   del intento, ID de evidencia, autorización y responsable. El comando no admite
   reemplazar el adaptador ni alterar los datos persistidos.
5. Heredar `IdempotentDestinationContract` y proporcionar un `Target` aislado. Las
   cuatro pruebas se ejecutan sin modificar el arnés. Añadir pruebas del transporte,
   rollback y recuperación propias del destino antes de habilitarlo en producción.

Las fuentes registradas son una frontera de confianza dentro del proceso Java.
El método de bajo nivel `ExecutionEvidenceService.verified` sigue disponible para
integraciones internas existentes: no debe exponerse directamente en un controlador
ni aceptar evidencia externa sin autenticación. Esta entrega no redefine el modelo
de autorización de toda la aplicación ni acredita tolerancia a fallos de energía.

## Ejecutar validaciones

En la raíz de MIEC, con Maven Wrapper de Windows:

```powershell
.\mvnw.cmd "-Dtest=DestinationEvidenceVerificationServiceTest,ExecutionEvidenceServiceTest,ReservationContractTest,ReservationExecutionIntegrationTest" test
.\mvnw.cmd "-Dtest=PetClinicContractIT,PetClinicProcessIT" "-Dmiec.petclinic.jar=../spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar" test
.\mvnw.cmd clean package
```

Los dos IT de PetClinic son explícitos, requieren el JAR ya construido e inician
solo procesos propios con archivos aislados. El contrato compartido cuenta visitas
durables por SQL después de detener cada JVM. Reservas consulta inventario y ledger
de su base propia y comprueba rollback y reapertura; su prueba de respuesta perdida
es una simulación local, mientras PetClinic prueba la pérdida real en el transporte.

La nueva variante interna de registro fecha las observaciones con el reloj de base
de datos bajo bloqueo. Dos verificaciones concurrentes del mismo evento conservan
una sola fila y su fecha original; no se relaja la validación de fechas de reportes
ni de verificaciones históricas con fecha explícita.

## Resultado de esta entrega

- Paso 1: garantías, frontera de confianza y separación documentadas.
- Pasos 2 y 3: fuente configurada y verificación común; se eliminó el verificador
  PetClinic que recibía un ejecutor desde el llamador. La fuente experimental
  implementa el puerto genérico y se instala solo en el contexto de su prueba.
- Paso 4: cuatro pruebas heredables ejecutadas contra PetClinic HTTP real, con
  conteo SQL de efectos después de detener cada proceso propio.
- Paso 5: las mismas cuatro pruebas pasan contra reservas; además pasan rollback,
  reapertura y recuperación mediante ejecución, evidencia y reconciliación comunes.
- Evidencia: 12 pruebas del nuevo verificador y 4 del servicio de evidencia previo
  sin fallos, incluida deduplicación concurrente del mismo ID de observación.
- Regresión final: 688 pruebas, cero fallos, errores u omisiones; `clean package`
  terminó correctamente. El JAR contiene los contratos y el servicio común, sin
  clases de PetClinic, reservas ni del arnés de pruebas.
- PetClinic explícito final: 5 pruebas, cero fallos, errores u omisiones: contrato
  compartido y pérdida de respuesta posterior a commit con reinicio en otra JVM.

Registros locales ignorados por Git: `review-integration-step2.log`,
`review-integration-step3.log`, `review-integration-step4.log`,
`review-integration-step5.log`, `review-integration-evidence-concurrency.log`,
`review-integration-regression.log` y `review-integration-petclinic-final.log`.

No se añadió un adaptador productivo de dominio ni una dependencia del núcleo a
los experimentos. El soporte de pruebas compartido se reutiliza en este repositorio;
para distribuirlo a otros repositorios como dependencia Maven hará falta publicar
un módulo de soporte de pruebas, que no forma parte de esta entrega.

## Validación posterior del JAR compartido

PetClinic ya consume miec-recipient-sdk por Maven para modelos y validaciones; el
adaptador experimental de MIEC utiliza el mismo JAR con scope test. El alcance,
las pruebas y el cierre técnico experimental se describen en
[IR-009-C09-sdk-validation.md](IR-009-C09-sdk-validation.md). Las interfaces SDK-03
y las garantías productivas pendientes no se consideran completadas por esa prueba.
