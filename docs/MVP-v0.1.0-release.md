# Acta t?cnica de entrega ? MVP v0.1.0

Fecha: 2026-11-04. Producto: MIEC Prototype. Alcance: validaci?n local, no producci?n.
Versi?n Maven: `com.miec:miec-prototype:0.1.0`. Etiqueta prevista: `v0.1.0`.

## Identificaci?n e integraci?n

MVP-09 se integr? mediante PR #33 en `44fe6a2040882b7f4bfad786dd7865fecf31e758`.
La entrega a?ade versi?n estable, rutas de artefactos actualizadas y controles PostgreSQL
al workflow `.github/workflows/mvp-ci.yml`. Las bases H2 dejan de estar versionadas;
los archivos locales y las bases PostgreSQL existentes se conservan. Su retirada del
?ndice no elimina los datos de commits hist?ricos.

El commit exacto entregado ser? el destino de la etiqueta anotada `v0.1.0`, creada
solamente despu?s de verificar CI del commit integrado. Resolverlo con
`git rev-parse v0.1.0^{commit}`. No reutilizar ni mover una etiqueta publicada.
Los resultados de CI deben consultarse para ese SHA en GitHub Actions; este documento
no declara aprobado un workflow que todav?a no se haya ejecutado.

## Alcance aceptable

- Sistemas y ciclos con navegaci?n paginada y resumen de actividad.
- Objetivos, indicadores, l?nea base y transici?n controlada de estados F01.
- Planes autorizados y ejecuci?n idempotente, consulta, evidencia y reconciliaci?n.
- Mediciones posteriores, conjuntos cerrados y evaluaci?n determinista versionada.
- Decisiones, aprendizaje, cierre y continuidad de m?ltiples rondas.
- APIs autenticadas, OpenAPI/Javadoc, H2 y migraciones PostgreSQL separadas.

## Dependencias y demostraci?n

SDK de pruebas: `jesusmarin/miec-recipient-sdk`, commit
`5cb3b0fa7971070954acaa7fd2e89620b04f2f51`, coordenadas `0.1.0-SNAPSHOT`.
Es dependencia de test de MIEC; su revisi?n est? fijada en CI. PetClinic es un
proyecto destinatario experimental independiente y no se distribuye dentro de MIEC.
Sus adaptaciones locales pendientes de publicaci?n no se consideran publicadas por
esta etiqueta. La demostraci?n reproducible est? documentada en
[MVP-08](MVP-08-local-project.md) y su [aceptaci?n t?cnica](MVP-08-local-project-acceptance.md).
Los 12/12 casos hist?ricos pertenecen a los hashes all? registrados, no al nuevo JAR.

## Instalaci?n y validaci?n

Requisitos: Java 21, Maven Wrapper, SDK fijado disponible e instancia PostgreSQL 14
para las IT optativas. Seguir [PostgreSQL local](POSTGRESQL-local.md).
Inyectar credenciales por variables de entorno; no publicar contrase?as ni dumps.

```powershell
.\mvnw.cmd -f ..\miec-recipient-sdk\pom.xml clean install
.\mvnw.cmd clean verify
.\mvnw.cmd '-Dtest=MvpLifecycleRestartIT,ClosedSetEvaluationRestartIT' '-Dmiec.mvp.jar=target/miec-prototype-0.1.0.jar' test
.\mvnw.cmd '-Dtest=LifecycleNavigationPostgresIT,PostgresApplicationRestartIT' '-Dmiec.postgres.test=true' '-Dmiec.mvp.jar=target/miec-prototype-0.1.0.jar' test
```

Las IT PostgreSQL crean y eliminan ?nicamente sus bases aleatorias de laboratorio.
No ejecutar pruebas de instalaci?n vac?a contra las bases del piloto pobladas.
La compilaci?n local puede incorporar secretos del perfil privado a los recursos:
para artefactos compartibles usar `tools/pilot/prepare-artifacts.ps1` y verificar
que el perfil empaquetado conserva exclusivamente el placeholder `xxx`.
El piloto admite `--miec-jar` para identificar el binario exacto de una nueva aceptaci?n.
Conservar SHA-256 de los binarios y el expediente fuera del repositorio p?blico.

CI requerido: regresi?n completa, reinicios H2, consultas PostgreSQL y reinicio del
JAR PostgreSQL sobre el SHA integrado. Un fallo impide etiquetar la entrega.

## Limitaciones y deuda declarada

MVP local: HTTP Basic y roles de laboratorio; sin certificaci?n de operaci?n remota.
La medici?n humana no acredita autom?ticamente EV-2/EV-3 ni causalidad.
Persisten recuperaci?n avanzada de planes parciales, fuentes instrumentales,
TLS/identidades, privilegios SQL, respaldo y observabilidad productivos.
La prueba PostgreSQL de CI es acotada y no representa toda la matriz de fallos reales.
El SDK conserva coordenadas SNAPSHOT aunque su c?digo est? fijado por commit.

## Conformidad

Aceptaci?n t?cnica: condicionada a controles del candidato y CI del SHA etiquetado.
Aceptaci?n funcional del responsable: PENDIENTE; no hay firma humana atribuida.
Responsable: __________________  Fecha: __________  Observaciones: __________

La etiqueta identifica el c?digo entregado, no convierte las limitaciones declaradas
en garant?as productivas ni sustituye la aceptaci?n funcional.
