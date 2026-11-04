# IR-009-C09 — Persistencia experimental de PetClinic en PostgreSQL

Fecha de validación: 2026-11-04. Proyecto destinatario: `spring-petclinic`, rama
`miec/experimento-001`. Base local `petclinic`, PostgreSQL 14.4 en 127.0.0.1:5532.

## Implementación

La persistencia pertenece a PetClinic. El destinatario consume
`com.miec:miec-recipient-sdk:0.1.0-SNAPSHOT` y no importa el coordinador MIEC.
El SDK no incorpora tablas, transacciones o tipos de visita específicos.

Se añadieron `db/miec-postgresql/schema.sql` y `data.sql` y se adaptó el perfil
`miec-postgres` para utilizar sesiones UTC. Los scripts son aditivos y no utilizan
el inicializador PostgreSQL genérico destructivo. El servicio solicita solamente
la columna generada `id`, necesaria para interpretar correctamente el resultado
de PostgreSQL mediante `GeneratedKeyHolder`.

Reserva de clave, creación de visita y escritura de resultado permanecen en una
transacción del destinatario. La recuperación de clave duplicada se realiza fuera
de la transacción fallida. Una consulta o replay no modifica el resultado original.

## Evidencia

| Validación | Casos | Resultado |
|---|---:|---|
| Contrato HTTP PetClinic sobre H2 | 6 | Aprobados |
| Contrato y esquema PetClinic sobre PostgreSQL | 8 | Aprobados |
| Respuesta perdida y caída forzada del JAR PostgreSQL | 1 | Aprobado |
| Regresión local PetClinic y empaquetado | 83 | Aprobados |
| Instalación explícita sobre petclinic | 1 | Aprobado |
| Compatibilidad del consumidor MIEC con el nuevo JAR, perfil H2 | 5 | Aprobados |

Todos los grupos terminaron sin fallos, errores ni omisiones. La instalación
local finalizó a las 11:52:12 y la validación del consumidor a las 11:55:52,
hora de Bogotá. Los seis casos HTTP H2 también forman parte de la regresión de
83 casos; los grupos no representan escenarios distintos sumables.

La suite PostgreSQL reutiliza seis escenarios HTTP: creación, replay, conflicto,
consulta sin efecto, ocho solicitudes concurrentes, autenticación, propietario,
huella y UTF-8. El escenario de rollback fuerza un rechazo SQL al guardar el
resultado, comprueba cero visitas y cero reservas y después crea correctamente
la operación reutilizando la clave. Los dos escenarios adicionales comprueban
la conservación de datos al reaplicar los scripts y la unicidad de mascota por
propietario sin distinguir mayúsculas.

El caso del JAR envía un único POST y cierra el socket sin leer respuesta.
Verifica el commit desde otra conexión, termina la JVM forzosamente y arranca otra.
Consulta y replay conservan ID, referencia, huella y fecha originales, con una sola
visita y operación en PostgreSQL. No se reenvía el POST para lograr el primer commit.

Las pruebas usan bases `petclinic_pg_*` propias, eliminadas al finalizar. No cargan
visitas de test en las bases del usuario. La instalación explícita de `petclinic`
creó ocho tablas y tres fixtures de tipo/propietario/mascota con ID 900001, dejando
cero visitas y cero operaciones. No se modificó `miec-v01`.

## Reproducción

Desde el repositorio `spring-petclinic`:

```powershell
.\mvnw.cmd '-Dtest=MiecVisitIntegrationTests,MiecVisitPostgresIT' '-Dmiec.petclinic.postgres.test=true' test
.\mvnw.cmd '-DskipTests' package
.\mvnw.cmd '-Dtest=MiecVisitPostgresRestartIT' '-Dmiec.petclinic.postgres.test=true' '-Dmiec.petclinic.jar=target/spring-petclinic-4.0.0-SNAPSHOT.jar' test
.\mvnw.cmd '-Dtest=*Test,*Tests,!MySqlIntegrationTests,!PostgresIntegrationTests' verify
```

La regresión excluye los orquestadores genéricos de MySQL y PostgreSQL que recrean
contenedores. PostgreSQL 14 se valida mediante las IT explícitas sobre el servidor
local existente, sin descargar ni recrear otros contenedores.

Desde `miec-prototype`, comprobar compatibilidad del adaptador experimental:

```powershell
.\mvnw.cmd '-Dtest=PetClinicContractIT,PetClinicProcessIT' '-Dmiec.petclinic.jar=../spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar' test
```

Estos cinco casos existentes usan archivos H2 y JVM propias; no constituyen una
demostración integral MIEC/PostgreSQL → PetClinic/PostgreSQL.

Registros locales, excluidos de Git: `review-petclinic-postgres.log`,
`review-petclinic-postgres-restart.log`, `review-petclinic-regression.log`,
`review-petclinic-postgres-install.log` y `review-petclinic-miec-consumer.log`.
La guía del destinatario está en `spring-petclinic/docs/MIEC-POSTGRESQL.md`.

## Aceptación posterior

Después de validar el destinatario se ejecutó [MVP-08](MVP-08-local-project.md),
con ambos proyectos sobre PostgreSQL y datos propios. El
[informe posterior](MVP-08-local-project-acceptance.md) acredita los doce casos,
dos rondas, reconciliación, restauración y cierre E14. Esta validación conjunta
no cierra todo IR-009 ni sustituye la entrega formal versionada.

Quedan separados el versionado de futuras migraciones de PetClinic, privilegios
SQL mínimos, protección frente a SQL directo, autenticación distribuida y
respaldo/restauración. El inicializador aditivo no convierte esquemas históricos
ni acredita por sí solo portabilidad productiva de toda la aplicación.
