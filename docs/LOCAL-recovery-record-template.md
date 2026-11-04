# Acta de intervención operativa — plantilla

Plantilla sin datos privados. Guardar la copia rellenada fuera del repositorio.
Procedimiento: [recuperación operativa](LOCAL-operational-recovery.md).
No incluir contraseñas, Authorization, API keys ni dumps.

| Campo | Valor |
|---|---|
| Incidente / correlación | |
| Responsable y siguiente responsable | |
| Inicio / fin UTC | |
| Versión, commit y SHA-256 de ambos JAR | |
| Bases del par y ubicación privada del respaldo, si existe | |
| Sistema / ciclo / ronda / plan / run | |
| Estado / versión de ciclo y run antes | |
| Propiedad: coordinador vigente, vencido o ausente; evidencia consultada | |
| Clasificación y procedimiento elegido; justificación | |

## Situación por acción

| ordinal / actionId | operationKey / executionId | estado del paso / observedOutcome | referencia y evidencia privada | aplicado, no enviado o incierto |
|---|---|---|---|---|
| | | | | |

## Autorizaciones e intervenciones

| Hora UTC | Identidad / rol | authorizationId, alcance, estado y vigencia | recurso y versión esperada | comando/motivo sin secretos | HTTP/código y efecto confirmado |
|---|---|---|---|---|---|
| | | | | | |

## Resultado y relevo

- Estado / versión de ciclo y run después:
- Hechos confirmados; pendientes e incertidumbres conservados:
- Evidencia y respuestas guardadas en ubicación privada:
- Resultado del acta: RECUPERADO_LOCALMENTE / RECONCILIADO_APPLIED /
  FINALIZADO_TECNICAMENTE_E08 / BLOQUEADO_ESCALADO. Elegir el que corresponda;
  son etiquetas documentales, no estados de MIEC.
- Motivo de cierre de la intervención o límite que exige escalamiento:
- Próxima acción admisible, responsable y condición para reevaluar:
- Revisión del responsable / fecha:

Cerrar esta acta no cambia el estado del ciclo ni declara cumplido su objetivo.
