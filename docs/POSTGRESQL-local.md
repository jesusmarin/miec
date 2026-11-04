# Conexión PostgreSQL 14 local — configuración inicial

Estado: perfiles de conexión preparados; migraciones y consultas PostgreSQL
pendientes de adaptación y validación. Esto no declara que el piloto ya arranque
o funcione con PostgreSQL. No se crea ninguna base ni se migran datos históricos.

| Aplicación | Perfil | URL predeterminada | Usuario | Contraseña reemplazable |
|---|---|---|---|---|
| MIEC | postgres | jdbc:postgresql://127.0.0.1:5532/miec-v01?currentSchema=public | postgres | xxx |
| PetClinic | miec,miec-postgres | jdbc:postgresql://127.0.0.1:5532/petclinic?currentSchema=public | postgres | xxx |

MIEC: `src/main/resources/application-postgres.properties`.
PetClinic: `src/main/resources/application-miec-postgres.properties`, en el repositorio
hermano spring-petclinic. Mantener `miec` primero y `miec-postgres` después para
sobrescribir la conexión y el inicializador H2 del experimento. El perfil genérico
`postgres` de PetClinic no es el perfil de integración configurado aquí.

Las propiedades contienen `${VARIABLE:xxx}`. Puede sustituir ese `xxx` localmente,
pero la contraseña real no debe añadirse a Git. Preferir estas variables:

```powershell
# Consola de MIEC
$dbCredential = Get-Credential -UserName 'postgres' -Message 'PostgreSQL: base miec-v01'
$env:MIEC_POSTGRES_PASSWORD = $dbCredential.GetNetworkCredential().Password

# Consola de PetClinic (no necesita compartir las variables de la otra consola)
$dbCredential = Get-Credential -UserName 'postgres' -Message 'PostgreSQL: base petclinic'
$env:MIEC_PETCLINIC_POSTGRES_PASSWORD = $dbCredential.GetNetworkCredential().Password
```

Se mantienen los requisitos de cuentas AUTHOR/APPROVER de MIEC y API key del enlace
PetClinic definidos en [MVP-08](MVP-08-local-project.md). La contraseña PostgreSQL
no sustituye ninguna de esas credenciales. H2 sigue siendo la configuración base
cuando no se activan estos perfiles.

Después de implementar y validar los esquemas específicos, los comandos de
arranque serán, desde cada proyecto:

```powershell
# MIEC
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=postgres'

# PetClinic
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=miec,miec-postgres' '-Dspring-boot.run.arguments=--server.port=8082'
```

Actualmente el arranque debe detenerse: MIEC exige migraciones en
`classpath:db/postgresql`; PetClinic exige esquema/datos del experimento en
`classpath:db/miec-postgresql/schema.sql` y `data.sql`. Todavía no se proporcionan
esos archivos. No se desactiva Flyway para ocultar la ausencia del esquema y no
se utilizan los scripts H2 ni los inicializadores genéricos sobre estas bases.

MIEC incorpora el driver PostgreSQL y el módulo PostgreSQL de Flyway, con versiones
administradas por su POM padre. PetClinic ya incluye el driver. Para continuar:

1. Implementar los esquemas PostgreSQL separados sin modificar las migraciones H2 publicadas.
2. Adaptar las consultas y funciones dependientes de H2, incluido el tratamiento
   de JSON, reloj, restricciones y SQL de inicialización.
3. Probar instalación desde cero, atomicidad, concurrencia, idempotencia y reinicio
   en ambas bases de laboratorio; después ejecutar MVP-08.

El usuario postgres se configura según lo solicitado para el laboratorio local.
La separación de usuarios y privilegios de aplicación/migración queda pendiente
antes de un despliegue compartido.
