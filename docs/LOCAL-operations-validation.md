# Consolidación operativa local — informe de validación

Fecha: 2026-11-04. Resultado técnico del ensayo: **APROBADO** en el laboratorio.
Procedimiento vigente: [instalación y operación conjunta](LOCAL-installation-operations.md).
Este incremento complementa `v0.1.0`; no mueve su etiqueta ni modifica los JAR.

## Comprobaciones del asistente y de la guía

`tools/operations/tests/LocalEnvironment.Tests.ps1`: **7 escenarios aprobados**
en un proceso PowerShell hijo, con credenciales ficticias y diálogos sustituidos:

1. Configuración válida: ambas bases comparten la cuenta SQL y ambos extremos
   comparten la API key; ruta de ejecución instalada correctamente.
2. AUTHOR/APPROVER con igual nombre: rechazo antes de cambiar el entorno.
3. AUTHOR/APPROVER con igual clave: rechazo antes de cambiar el entorno.
4. Identidad inválida: rechazo antes de cambiar el entorno.
5. API key corta: rechazo antes de cambiar el entorno.
6. Cancelación de credenciales: rechazo antes de cambiar el entorno.
7. Limpieza: retira las variables administradas y conserva una variable ajena.

Se validó la sintaxis del asistente y de los diez bloques PowerShell de la guía.
Las funciones `Invoke-LabDocker` e `Invoke-LabSql` de la receta se ejecutaron
realmente en modo de lectura: `pg_restore` informó 14.4 y el checkpoint MIEC
devolvió las 45 líneas esperadas. No se probó un cambio de contraseña del servidor.

Reproducción de pruebas del asistente desde la raíz:

```powershell
powershell -NoProfile -File tools/operations/tests/LocalEnvironment.Tests.ps1
```

## Ensayo de respaldo, restauración y HTTP

RUNID: `OPS-20261104T212636Z-c7d96897`.
Ventana UTC: `2026-11-04T21:26:36.022589Z` a `2026-11-04T21:27:28.502451Z`.
Duración observada del programa de ensayo: aproximadamente 52,48 s;
no es un RTO comprometido ni mide un incidente completo con intervención humana.

Se aprobaron **23 comprobaciones operativas**, no 23 casos nuevos de negocio:
precondiciones, saneamiento del JAR, hashes, integridad de los datos y secuencias,
arranque, autenticación y consultas de recursos existentes.

No había listeners de aplicaciones en 8081/8082 ni sesiones SQL activas sobre
los orígenes al iniciar. Se conservaron dos sesiones idle de pgAdmin en MIEC;
no se cerraron conexiones del usuario. Los checkpoints antes/después de los dumps
y al final del ensayo fueron iguales. Esto observa estabilidad durante esta
ventana; no constituye exclusión de futuros escritores ni una instantánea
distribuida mientras existan aplicaciones operando.

| Componente | Origen conservado | Destino nuevo | Checkpoint restaurado |
|---|---|---|---|
| MIEC | `miec-v01` | `miec_ops_restore_miec_0034f26d0348456d` | 44 tablas, una secuencia; contenido y estado iguales |
| PetClinic | `petclinic` | `miec_ops_restore_petclinic_f354eee14ba441b4` | Ocho tablas, seis secuencias; contenido y estado iguales |

Los dumps custom se escribieron dentro del contenedor y se copiaron como archivos,
sin pipelines textuales. El SHA-256 coincidió entre el archivo del contenedor y
la copia externa. `pg_restore --exit-on-error --single-transaction --no-owner
--no-privileges` terminó correctamente sobre bases creadas desde `template0`.
El checkpoint de solo lectura compara cantidad y resumen de filas por tabla,
historial Flyway y estado de secuencias; su MD5 no se presenta como firma.

Después se iniciaron los JAR reales, sobre el par nuevo y puertos loopback libres,
con credenciales efímeras por entorno y sin secretos en argumentos. La credencial
JDBC se obtuvo del perfil privado existente en memoria; no se guardó en el expediente.
Se verificaron:

- Salud HTTP PetClinic y navegación autenticada MIEC.
- MIEC sin Basic: 401; PetClinic sin API key: 401.
- Ciclo `93121e19-cfd6-4882-82d1-4daca073ca47`: conserva estado E14 y versión 47.
- Ciclo inactivo `d7e7a5b1-9774-4ea8-b0cd-2af77fffa04d`: conserva estado y versión.
- Operaciones `8282c15f-6eed-3894-82f9-4ba6655a0b33` y
  `96dcac26-8ab8-3f56-8258-b27b83af59c3`: consultas conservan huella, destino,
  visita y referencia. No se enviaron nuevos POST ni replays en este ensayo.
- Cierre de las dos JVM propias mediante attach/JMX local. Las bases originales
  mantuvieron exactamente sus checkpoints finales.

## Identificación de los artefactos y evidencia

| Archivo | SHA-256 |
|---|---|
| `miec-prototype-0.1.0.jar` | `1ade2065d3429c921fd1a99de65df576f26c28feba3527803f2b6e8c64fa3b7f` |
| `spring-petclinic-4.0.0-SNAPSHOT.jar` | `2b20610312ffea920209aa814bc3f5b041af0aca1d4a8fb79b1520eb45afa0ca` |
| `miec.dump` | `01a71cc594fd446372032f42136e40845dca02ef936947f7b404d57c679f6d1b` |
| `petclinic.dump` | `3cb23ffd6b6b2180430e03678d0358a018987251fa4f1b3a2b2c40f0d3d1250b` |

Ambos JAR conservaron `xxx` como único fallback de contraseña PostgreSQL.
Los artefactos ya existían; no se recompilaron para este incremento documental.
No se atribuye este ensayo a una ejecución nueva del piloto completo LP-01–LP-12.

Expediente privado:
`%LOCALAPPDATA%/MIEC/operations/OPS-20261104T212636Z-c7d96897`.
Contiene `validation.json`, dumps, índices de archivo, checkpoints y respuestas/logs
sin encabezados de autenticación. Los dos destinos restaurados y los temporales
`/tmp/OPS-20261104T212636Z-c7d96897-*.dump` permanecen para inspección.
Revisar su custodia y retirar únicamente temporales propios después de aprobar
una copia privada verificada. Los datos de respaldo no se preparan para Git.

El resumen regenerable en `target/operations-validation.json` y los programas
temporales de validación de `target` están excluidos de Git. El asistente, sus pruebas,
el checkpoint SQL y esta documentación sí forman parte del incremento.

## Límites pendientes

No se certificaron cifrado del medio, copia externa independiente, retención
automatizada, recuperación en otra máquina, permisos SQL mínimos ni cambios de
esquema futuros. Se documentó su procedimiento y responsabilidad; no se declara
una automatización existente. La publicación reproducible de las adaptaciones
PetClinic y la firma funcional del responsable permanecen pendientes.
No se volvió a ejecutar la regresión Java: no se modificó código Java, migraciones,
dependencias ni configuración de ejecución de la aplicación.
