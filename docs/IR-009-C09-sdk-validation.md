# IR-009-C09: validación del SDK con PetClinic

## Alcance

Validar el JAR com.miec:miec-recipient-sdk:0.1.0-SNAPSHOT como contrato compartido
entre el destinatario PetClinic y el adaptador experimental de MIEC. Es una
validación de los modelos y validaciones SDK-01/SDK-02, no la implementación de
toda la ruta del SDK ni una certificación productiva de IR-009 completo.

## Cambios realizados

- SDK construido, probado e instalado en el repositorio Maven local. Los
  consumidores declaran coordenadas Maven; no se copian sus fuentes.
- PetClinic, rama miec/experimento-001, incorpora el JAR en compilación. El servicio
  experimental usa OperationPayload y OperationRequest para calcular y verificar
  la huella; OperationResult valida el resultado confirmado.
- PetClinic conserva la validación de visitas, autenticación, tablas y transacción
  de negocio. El SDK no instala persistencia ni administra conexiones SQL.
- UTF-8 se decodifica de forma estricta antes de capturar el texto. Una secuencia
  inválida o huella declarada incorrecta se rechaza antes de reservar clave o visita.
- La respuesta experimental añade destinationId para vincular explícitamente el
  resultado. El adaptador de MIEC usa OperationResult.matches para comprobar clave,
  destino y huella. Conserva comprobaciones propias de visita y fecha remota.
- MIEC incorpora el SDK exclusivamente con scope test; su coordinador y sus
  servicios productivos siguen sin depender de los experimentos.
- No se modificaron endpoints, claves ni referencias de visitas. No se requiere
  una migración SQL para este cambio de contrato.

## Construcción y validación reproducible

Cada repositorio consumidor debe utilizar el mismo repositorio Maven local donde
se instala el SDK. Desde la raíz de MIEC:

```powershell
.\mvnw.cmd -f ..\miec-recipient-sdk\pom.xml clean install
.\mvnw.cmd -f ..\spring-petclinic\pom.xml "-Dtest=MiecVisitIntegrationTests" package
.\mvnw.cmd clean package
.\mvnw.cmd "-Dtest=PetClinicContractIT,PetClinicProcessIT" "-Dmiec.petclinic.jar=../spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar" test
```

Regresión adicional de PetClinic sin Docker, desde su raíz:

```powershell
.\mvnw.cmd "-Dtest=*Test,*Tests,!MySqlIntegrationTests,!PostgresIntegrationTests" test
```

Los IT crean sus propias JVM, secretos, puertos y archivos bajo target. No utilizan
la instancia de PetClinic del usuario. Cambió el DTO experimental de respuesta:
reconstruir ambos consumidores cuando se actualice el contrato compartido.

## Evidencia ya aprobada

| Validación | Resultado |
|---|---|
| SDK, construcción e instalación local | 24 pruebas, cero fallos, errores u omisiones. |
| PetClinic, contrato HTTP | 6 pruebas; incluye dos nuevas de huella falsa y UTF-8 inválido sin efectos. |
| Adaptador MIEC | 4 pruebas; respuestas con destino, clave o huella ajenos se rechazan. |
| PetClinic, regresión sin contenedores | 83 pruebas, cero fallos, errores u omisiones. |
| MIEC, regresión y empaquetado | 688 pruebas, cero fallos, errores u omisiones. |
| Artefactos | SHA-256 del SDK anidado en PetClinic idéntico al JAR construido; MIEC productivo excluye SDK y adaptadores experimentales. |

MySqlIntegrationTests y PostgresIntegrationTests no se ejecutaron porque requieren
contenedores. No se afirma validación de esos motores a partir de las pruebas H2.

Estado de la última comprobación entre procesos: EN_CURSO_PROCESOS.

## Criterio de cierre

Este punto puede cerrarse, dentro del alcance experimental C09, cuando también
pasen las cuatro pruebas de contrato compartido y la prueba de pérdida de respuesta
posterior a commit con reinicio en otra JVM: misma clave, referencia y un solo efecto
durable, con verificación y reconciliación en MIEC.

Esto no cierra IR-009 completo ni SDK-03 y siguientes. Siguen pendientes las
interfaces genéricas de ejecución/consulta, la aplicación de capacidades, el segundo
consumidor del JAR, publicación para CI y las deudas de infraestructura, seguridad
y operación identificadas. Autenticación local con API key, retención de claves y
fencing del destinatario requieren el tratamiento ya documentado para producción.

Los logs locales de este incremento son review-sdk-petclinic-sdk.log,
review-sdk-petclinic-contract.log, review-sdk-petclinic-adapter.log,
review-sdk-petclinic-regression.log, review-sdk-miec-regression.log y
review-sdk-petclinic-process.log. Están ignorados por Git.
