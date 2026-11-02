# Incremento local posterior a C08.4

## Alcance y orden

1. Validaciones temporales: igualdad, desfases positivos/negativos y reenvío.
   No se modificó la regla estricta occurred_at <= received_at ni se inventó tolerancia.
   Las carreras entre evidencia contradictoria y reintento se prueban en ambos
   órdenes de confirmación con señales y bloqueos reales.
2. ExecutionOutcomeQueryService: resultado efectivo en una proyección SQL que
   conserva estado original, reconciliación y contradicciones. Una contradicción
   también se compara con el efecto confirmado de la fila original.
3. IdempotentActionExecutionService.recoverResult: recupera referencias ya
   confirmadas, sin destinatario, nuevos intentos o claves. Revalida EXECUTE_ACTION.
   Devuelve vacío ante incertidumbre, contradicción o referencia inexistente.
4. ExecutionRequest: contenido inmutable, media type y destinatario. SHA-256
   central sobre campos UTF-8 enmarcados por longitud y versión UTF8-V1.
   No normaliza semánticamente JSON: espacios distintos son solicitudes distintas.
   El adaptador recibe el mismo cuerpo capturado mediante Context.request().bodyBytes().
   V008 persiste el snapshot. Las operaciones históricas sin cuerpo siguen
   consultables, pero no se pueden reejecutar fabricando un snapshot retrospectivo.
5. PendingExecutionReviewService: detecta PENDING antiguos; solo un revisor
   autorizado puede cancelarlos. V009 guarda la cancelación sin falsificar
   started_at/completed_at ni cambiar el estado original. Inicio y revisión
   compiten bajo bloqueo del plan/intento. El reintento aplica el límite habitual
   y vuelve a validar IR-010. No hay cancelación o reasignación automática.
6. ExecutionOperationsQueryService: páginas por cursor (created_at,execution_id),
   máximo 100 resultados y filtros ALL/NEEDS_REVIEW. Métricas agrupadas en SQL,
   por intento, con transacción de lectura repetible. Incluye UNKNOWN, leases
   vencidos, contradicciones y pendientes antiguos; excluye resoluciones vigentes
   y cancelaciones. Es un servicio interno, no un endpoint público sin autorización.
7. Migración V007->V009 y regresión completa en H2 en memoria.

## Compatibilidad y operación

- V001-V007 no se modifican. V008 y V009 se validan sobre bases temporales.
- El contrato del adaptador cambia de fingerprint declarado a request() inmutable.
  No se conserva una ruta de ejecución que permita presentar una huella arbitraria.
- recoverResult consulta lo conocido por MIEC: no consulta automáticamente al destino.
- La bandeja es una paginación viva; cambios concurrentes pueden hacer que un caso
  entre o salga del filtro. No se promete una exportación histórica congelada.
- Las métricas son consultas internas; exportadores, alertas y paneles remotos
  pueden construirse después sin cambiar las reglas de ejecución.
- El control de claves y bytes en MIEC no demuestra la deduplicación durable
  de un destinatario real. Su adaptador y las pruebas distribuidas siguen pendientes.
- Todos los métodos nuevos de prueba incluyen Javadoc y DisplayName.

## Validaciones por paso

| Paso | Resultado |
| --- | --- |
| 1 | 16 pruebas aprobadas de concurrencia y tiempo. |
| 2 | 3 pruebas aprobadas de resultado efectivo. |
| 3 | 14 pruebas aprobadas de recuperación de resultado y proyección. |
| 4 | 16 pruebas aprobadas de solicitud, migración y ejecución protegida. |
| 5 | 29 pruebas aprobadas de pendientes, inicio y resultado efectivo. |
| 6 | 3 pruebas aprobadas de paginación y métricas. |
| 7 | 665 pruebas aprobadas; cero fallos, errores u omisiones. Migración V007→V009 validada. |

Los registros review-local-step*.log y review-local-regression.log son locales
e ignorados por Git. La base data/miecdb no forma parte de estas validaciones.

La regresión final incluye una limpieza tardía del coordinador después de cancelar
un PENDING: la revisión y las fechas originales permanecen intactas. El IDE compiló
correctamente y los cambios quedaron preparados en el índice de Git, sin commit
ni publicación. La base H2 de desarrollo quedó fuera del índice de este cambio.
