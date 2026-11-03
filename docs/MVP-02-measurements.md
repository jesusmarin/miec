# MVP v01: objetivos medibles y línea base

## Contrato y compatibilidad
Un objetivo nuevo del flujo medible tiene de 1 a 20 indicadores. Cada indicador
incluye nombre único (sin distinguir mayúsculas), unidad explícita, operador
GT/GTE/LT/LTE/EQ y umbral decimal de hasta 15 enteros y 5 decimales.
No se convierten unidades automáticamente ni se evalúa éxito usando la línea base.
Cada medición conserva valor, unidad, origen, fecha del hecho, recepción y autor;
es un reporte humano UNVERIFIED. El hecho no puede ser posterior a la recepción.
Todas las mediciones de un criterio constituyen la línea base; no se elige una
agregación implícita. El diagnóstico referencia el acta de cierre que fija ese conjunto.

Los contratos anteriores siguen disponibles para compatibilidad. Un ciclo sin
criterios estructurados se identifica como LEGACY_UNSTRUCTURED; no se inventan
umbrales ni unidades. Los ciclos medibles no pueden cerrar observación por la ruta
anterior. No se modifican migraciones publicadas.

## Secuencia
1. Contrato de medición.
2. Migración aditiva V012 reutilizando indicator y baseline existentes.
3. Inicialización y consulta de objetivos medibles.
4. Mediciones inmutables y trazables.
5. Cierre explícito E02 a E03, versión y auditoría.
6. HTTP y OpenAPI/Swagger autenticados.
7. Contexto medible referenciado por diagnóstico y plan.
8. Demostración y regresión.

## API
Todas las rutas nuevas requieren HTTP Basic con las cuentas configuradas por
MIEC_AUTHOR_PASSWORD y MIEC_APPROVER_PASSWORD. Sin claves no hay acceso.
Las rutas históricas de sistemas/ciclos permanecen locales con el contrato MVP-01.

| Método | Ruta | Rol | Resultado |
|---|---|---|---|
| POST | /api/cycles/{id}/measurement/initialize | AUTHOR | Objetivo e indicadores; E01 a E02 |
| GET | /api/cycles/{id}/measurement | AUTHOR / APPROVER | Objetivos, condiciones y líneas base |
| POST | /api/cycles/{id}/measurement/baselines | AUTHOR | Medición y evidencia; conserva E02 |
| POST | /api/cycles/{id}/measurement/complete | AUTHOR | Evidencia de cierre; E02 a E03 |
| GET | /api/plans/{id}/context | AUTHOR / APPROVER | Contexto anterior más measurements |

Los POST responden 200 con cycle y resourceId. expectedVersion corresponde al
ciclo vigente y aumenta con cada escritura, incluidas las mediciones.
El cierre devuelve el evidenceId que debe utilizar POST /api/cycles/{id}/diagnosis.
Errores: 400 contrato/unidad/fecha/referencia inválida; 401 sin autenticación;
403 rol insuficiente; 404 ciclo inexistente; 409 versión, etapa o conjunto incompleto.
No existe API para editar o eliminar objetivos, criterios o mediciones de este flujo.

Inicialización de ejemplo (ciclo ya iniciado, versión 1):
```json
{"expectedVersion":1,"objective":"Reducir latencia","successCriteria":"p95 <= 100 ms","reason":"Objetivo medible","criteria":[{"name":"p95","unit":"ms","operator":"LTE","target":100}]}
```
Medición de ejemplo (reemplazar UUID y fecha por datos del caso):
```json
{"expectedVersion":2,"indicatorId":"UUID obtenido de la consulta","value":125.5,"unit":"ms","source":"Laboratorio A","measuredAt":"2026-11-02T12:00:00Z","reason":"Línea base"}
```
No se permiten fechas futuras, aunque procedan de un reloj externo; se debe corregir
la fuente y conservar su fecha real. No se admite tolerancia silenciosa de reloj.

## Swagger protegido
Dependencia springdoc-openapi-starter-webmvc-ui 3.1.1 (Spring Boot 4.1).
Abrir /swagger-ui/index.html e introducir credenciales configuradas ante el desafío
Basic del navegador. El documento /v3/api-docs también est? protegido. La opción
Authorize permite enviar Basic en las llamadas; persistAuthorization est? desactivado.
Nunca incluir contraseñas en URL, ejemplos ni repositorio. El navegador puede conservar
su autenticación Basic durante la sesión: cerrar la sesión del navegador compartido.
Las rutas históricas no muestran un requisito Basic ficticio. Uso local por defecto;
la exposición remota requiere TLS y revisar la seguridad de todas las rutas históricas.
Referencia: https://springdoc.org/faq.html

## Demostración
Con la aplicación reiniciada para aplicar V012 y cargar las cuentas:
```powershell
$author = Get-Credential -UserName author
.\docs\mvp01-measurements-demo.ps1 -AuthorCredential $author
```
La demostración crea recursos nuevos en la instancia indicada. Las pruebas automatizadas
usan bases aisladas y no ejecutan este guion contra la base local del usuario.

## Límite de MVP v01
El criterio numérico queda verificable, pero no se declara éxito a partir de la línea
base. La evaluación posterior a una intervención y sus políticas de agregación
pertenecen al incremento de resultados. No se afirma integridad contra un administrador
con acceso SQL directo. El acta de cierre fija UUID de todas las mediciones; no se
elige implícitamente la última ni un promedio. El código de planes/autorizaciones
existente se conserva y recibe el contexto medible.

## Resultado de validaciones
- Paso 1: contrato, precision, unidades, fechas y compatibilidad documentados.
- Paso 2: V012 aditiva y migracion V011 a V012 con datos historicos conservados.
- Paso 3: objetivo y criterios atomicos, consulta directa y rechazos de contrato.
- Paso 4: mediciones trazables, referencias y unidades controladas, E02 conservado.
- Paso 5: cierre completo, evidencia del conjunto, versiones y concurrencia.
- Paso 6: HTTP y Swagger/OpenAPI JSON/YAML autenticados, con roles y errores documentados.
- Paso 7: contexto medible integrado en la revision del plan sin alterar autorizaciones.
- Paso 8: regresion completa: 752 pruebas sin fallos, errores ni omisiones.

Se normalizo el reloj del flujo a microsegundos, la precision de TIMESTAMP(6),
para evitar que el redondeo de una fecha persistida la convierta en futura frente
al reloj de la siguiente operacion. No se agregaron tolerancias de vigencia.
El guion PowerShell paso validacion de sintaxis. El recorrido equivalente se
valida por HTTP automaticamente en MeasurementApiTest; no se ejecuto el guion
contra la base local del usuario.
Logs locales (ignorados por Git): review-mvp01-measurements-regression.log,
review-mvp01-measurements-restart.log y review-mvp01-measurements-clock.log.

Validacion final de persistencia: MvpLifecycleRestartIT ejecuto 3 pruebas sin
fallos, errores ni omisiones: ciclo MVP-01, aprobacion humana MVP-02 y objetivo
medible con linea base y cierre. Cada escenario usa dos JVM y un archivo H2
propio. El JAR se inspecciono: incluye V012, APIs y Swagger; excluye clases de
prueba, adaptadores experimentales y miec-recipient-sdk.
Los ocho pasos estan completados. El siguiente incremento del MVP v01 debe
abordar la ejecucion controlada del plan aprobado y, posteriormente, la medicion
de resultados frente al criterio, sin confundir esa medicion con la linea base.
