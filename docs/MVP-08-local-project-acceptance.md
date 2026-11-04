# MVP-08 — Resultado del primer uso local completo

**Aceptación técnica: APROBADA, 12/12 casos.** Fecha: 2026-11-04.

RUNID: `MVP08-20261104T172519Z-a03a82fe`.
Contrato: [MVP08-LOCAL-02](MVP-08-local-project.md).
Expediente privado: `%LOCALAPPDATA%/MIEC/pilots/MVP08-20261104T172519Z-a03a82fe`.

## Resultado observable

Se ejecutaron los JAR reales de MIEC y PetClinic sobre PostgreSQL 14.4. PetClinic
consumió el SDK fijado en el expediente. El propietario y la mascota fueron creados
por sus formularios HTTP, con ownerId=1 y petId=1, separados de los fixtures 900001.
La fecha seleccionada de las visitas fue 2026-11-11.

El ciclo `93121e19-cfd6-4882-82d1-4daca073ca47` completó dos rondas y terminó en
`E14_FINALIZADO`, versión 47, tanto en la base original como en la restaurada.

| Ronda | Plan | Operación | Visita / referencia | Conteos |
|---|---|---|---|---|
| 1 | `0d4217a3-dbc5-4ffc-9990-2ad2677b36b7` | `8282c15f-6eed-3894-82f9-4ba6655a0b33` | 1 / `petclinic:visit:1` | V=1, K=1 |
| 2 | `adebb18f-f746-4bea-b435-45f5e72eb04b` | `96dcac26-8ab8-3f56-8258-b27b83af59c3` | 2 / `petclinic:visit:2` | V=1, K=1 |

Cada ronda observó realmente una línea base 0 y un resultado posterior 0 para
`max(0,V-1)`, además de comprobar V=1/K=1. Ambas evaluaciones permanecieron
RS-05 INCONCLUSIVE bajo la política humana vigente: no se atribuyó causalidad,
mejora del código o suficiencia instrumental a estos conteos.

## Casos ejecutados

| Caso | Resultado | Evidencia principal |
|---|---|---|
| LP-01 | APROBADO | JAR y SDK fijados; propietario y mascota nuevos por HTTP |
| LP-02 | APROBADO | Plan autorizado, ejecución confirmada, E08 y una visita |
| LP-03 | APROBADO | Consultas repetidas conservan recibo y conteos |
| LP-04 | APROBADO | Dos starts MIEC simultáneos: 201/409; ocho replays: 200 y mismo resultado |
| LP-05 | APROBADO | Otra huella válida para la misma clave: 409, sin efecto adicional |
| LP-06 | APROBADO | Propietario, huella, UTF-8 y credencial inválidos rechazados |
| LP-07 | APROBADO | Commit remoto comprobado, respuesta cortada, UNKNOWN y reconciliación autorizada |
| LP-08 | APROBADO | Reinicio independiente de ambos JAR conserva recibo y estado incierto original |
| LP-09 | APROBADO | Continuidad pendiente sobrevive reinicio; E11 → E02 y contexto nuevo |
| LP-10 | APROBADO | Dos rondas, primer snapshot intacto y cierre E14 |
| LP-11 | APROBADO | 403 de rol; 409 de versión/permisos; 400 de referencia ajena; sin efectos |
| LP-12 | APROBADO | Par restaurado en bases nuevas, recursos iguales y replay sin duplicados |

El proxy local recibió el éxito de PetClinic y comprobó su ledger mediante otra
conexión antes de cortar la respuesta. MIEC conservó el intento
`a5989959-bad6-42b2-8ee1-eb4fb4156445` como UNKNOWN. La solicitud y aprobación
RECONCILE_ACTION permitieron consultar PetClinic y guardar resolución APPLIED
con su evidencia. El UNKNOWN técnico se conserva como historial, sin incertidumbre
efectiva pendiente al cerrar el ciclo.

Se creó un ciclo inactivo separado para probar la referencia ajena:
`d7e7a5b1-9774-4ea8-b0cd-2af77fffa04d`. No tuvo acciones ejecutadas y está identificado
en el manifiesto; no se borró para ocultar el control negativo.

## Reinicio, restauración y conservación

El procedimiento administró siete JVM propias y las cerró mediante el MBean
administrativo local de Spring. No abrió puertos JMX remotos ni detuvo otros procesos.
Las bases originales `miec-v01` y `petclinic` se conservan con los resultados.

Después del checkpoint con ambos escritores detenidos, los dumps se restauraron en:

- `mvp08_restore_miec_2dd11ff7802f4e4a`.
- `mvp08_restore_petclinic_2dd11ff7802f4e4a`.

Se compararon ciclo, rondas, ejecución, mediciones, evaluaciones e historial
consultado por HTTP. Se verificaron los mismos recibos y V=1/K=1 después de consultar
y repetir las claves originales. Una comprobación SQL posterior confirmó E14,
versión 47 y las visitas 1 y 2 en originales y restauradas.

El expediente contiene las tres aplicaciones/biblioteca fijadas, dos respaldos,
peticiones y respuestas saneadas, observaciones SQL, fallos controlados y fuentes
del harness. `inventory.json` y `inventory-sha256.txt` permiten verificar los
archivos, sin sustituir una firma humana o almacenamiento inmutable.

Se inventariaron 250 archivos de evidencia y se verificaron nuevamente los tres
JAR y los dos respaldos. SHA-256 del inventario definitivo:

```text
2e8608e303ea02a863084114bd5037741e05a3a0ebc139facf2bebd833946795
```

El verificador también rechazó un artefacto alterado en un control negativo
independiente, incluso ejecutándolo con optimización Python; sus comprobaciones
no dependen de sentencias `assert` que puedan desactivarse.

## Artefactos y versiones

| Artefacto | Versión | SHA-256 |
|---|---|---|
| miec-prototype | 0.0.1-SNAPSHOT | `91a862d5fd78719747bad83ba8dc0673330163645e55da54b7e684d016125262` |
| spring-petclinic | 4.0.0-SNAPSHOT | `2b20610312ffea920209aa814bc3f5b041af0aca1d4a8fb79b1520eb45afa0ca` |
| miec-recipient-sdk | 0.1.0-SNAPSHOT | `b2786655bdfef9538dee8d6d50a34fa8ba63a499935371b6b6c804af6d41cc4b` |

JDK: 21.0.13.0.3. Maven: 3.10.0. Los JAR se prepararon sin passwords PostgreSQL
privados predeterminados; las credenciales se entregaron por entorno.

| Repositorio | HEAD al preparar el piloto | Estado |
|---|---|---|
| MIEC | `7f12856a61d7ae9f4dad0d3988d6178978e46066` | Cambios locales documentados |
| PetClinic | `b6ec14272da51bc9a885d2e4f086f07a78c78127` | Cambios locales documentados |
| SDK | `5cb3b0fa7971070954acaa7fd2e89620b04f2f51` | Limpio |

Los diffs del expediente excluyen properties privados. Se conservaron fuentes del
harness y sus artefactos exactos; no se presenta un árbol con cambios como un
commit de entrega limpio. La reproducción está en el contrato y en
`tools/pilot/prepare-artifacts.ps1`, `run_local_project.py` y `finalize_evidence.py`.

## Alcance de la aceptación

AUTHOR y APPROVER fueron identidades distintas de una automatización local, no dos
personas independientes. Esta aceptación acredita el primer uso técnico completo
del destinatario real, con datos propios, fallos controlados y restauración.

Quedan para el siguiente incremento la revisión humana del expediente, integración
de los cambios, CI del commit resultante y entrega formal versionada del MVP v0.1.
No se crearon commits, tags o publicaciones como parte del piloto. Persisten los
límites declarados de seguridad productiva, calidad instrumental y recuperación
avanzada; no se declara cerrado todo IR-009.
