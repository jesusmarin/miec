# Paso 8 — diseño de nuevas rondas

Actualización MVP-07: el contrato, la persistencia inicial y la conclusión operativa
se implementan en [07.1–07.3](MVP-07-rounds.md). La continuidad y las rondas
sucesivas descritas aquí siguen pendientes. La migración V018 no asigna históricos:
el registro inicial es explícito en E11 con contexto operativo completo.

Estado del documento original: diseño. Este documento no crea migraciones, rutas
ni transiciones; la implementación parcial está identificada arriba. Complementa
[el cierre administrativo E14](MVP-06-decision-closure.md).
Su objetivo es hacer viable la continuidad desde E11 sin alterar historia previa.

## Problemas que debe resolver el próximo incremento

- Un plan anterior APPROVED bloquea otra propuesta por ACTIVE_PLAN_EXISTS.
- evaluation_admission y human_decision son únicos por ciclo en CYCLE-CLOSURE-V1.
- measurement_set es único por run y bloquea nuevos datos/revisiones tras el cierre.
- Las líneas base y diagnósticos actuales pertenecen al ciclo, sin ronda explícita.
- E14 es terminal en F01: continuar después exige otro ciclo, no reabrir el cerrado.

## Modelo propuesto antes de construir

1. **Ronda explícita.** cycle_round con roundId, cycleId, número, versión, motivo,
   autor, fechas y estado. Una sola ronda activa por ciclo. La migración debe
   identificar la ronda inicial de los registros nuevos conocidos; los históricos
   ambiguos se mantienen sin asignación inventada y requieren revisión explícita.
2. **Vínculos inmutables.** Asociar objetivo/criterios, diagnóstico, plan, run,
   observaciones, conjunto, evaluación, decisión y aprendizaje a su ronda con FKs
   compuestas. No copiar valores actuales para representar criterios históricos.
3. **Conclusión operativa del plan.** Registro append-only plan_completion con plan,
   run, evaluación, decisión, motivo, responsable y evento. No sustituir APPROVED por
   otro estado sin diseñar su efecto sobre IR-009 y permisos. La aprobación histórica
   y su huella se conservan; el plan concluido deja de contar como activo para otra
   propuesta dentro de una ronda nueva válida.
4. **Admisión por ronda.** Eliminar la unicidad por ciclo solo al sustituirla por
   invariantes de ronda activa y admisión/decisión por ronda. Mantener unicidad por
   conjunto y versiones de política. No quitar restricciones sin reemplazo.
5. **Fuentes y conjuntos versionados.** Cada ronda identifica sus muestras y ventanas
   explícitamente. Una revisión nueva no modifica el conjunto o evaluación anterior.
   Un conjunto sustituto referencia al anterior y exige nueva selección explícita;
   no se reutiliza una medición posterior como línea base automáticamente.

La numeración de la migración se confirmará al implementar; no se publica un V018
vacío ni se modifican V001–V017. Se probará conservación de historia y actualización
desde el esquema vigente antes de habilitar comandos.

## Flujo y permisos propuestos

La recomendación sigue siendo estratégica. Después del aprendizaje en E11,
un comando específico de continuidad seleccionará un tipo cerrado y un motivo:

| Intención | Continuidad que debe diseñarse | F01 inmediata |
|---|---|---|
| COLLECT_EVIDENCE | Nueva observación con ronda identificada | E11 → E02 |
| ADJUST_PLAN | Nueva planificación con contexto diagnóstico elegible | E11 → E04 |
| REQUEST_HUMAN_REVIEW | Revisión explícita previa a seleccionar continuidad | No inventar E11 → E05 |
| CLOSE_CYCLE | Cierre administrativo existente | E11 → E14 |

La API futura no aceptará un estado arbitrario. Exigirá expectedVersion del ciclo
y ronda, referencia a decisión/aprendizaje, motivo e identidad autorizada. Debe
definirse una política de aprobación humana de continuidad antes de implementarla.
Abrir la ronda, concluir operativamente la anterior, registrar contexto y efectuar
la transición se confirmarán en una sola transacción.

Un plan nuevo requiere acciones, revisión y autorizaciones nuevas. No se prolongan
permisos vencidos ni se transfieren claves idempotentes, ownership o fencing de la
ronda anterior. La fachada y la admisión previa al envío deberán rechazar planes
concluidos o ajenos a la ronda activa, conservando el contrato genérico de IR-009.
No se reenvían efectos aplicados y UNKNOWN/CONFLICT siguen requiriendo resolución.

Continuar después de E14 se modelará mediante otro ciclo, con relación de
procedencia explícita si corresponde. No se altera F01 para reabrir el cerrado.
La API actual no debe anunciar esas continuidades como disponibles.

## Secuencia de implementación futura y aceptación

| Orden | Entregable | Validación obligatoria |
|---|---|---|
| R1 | Contrato de rondas, roles y definición de plan activo | Correspondencia F01/F02/IR-009 y sin permisos implícitos |
| R2 | Migración aditiva y FKs de ronda | Historia intacta, datos ambiguos no inventados |
| R3 | Registro de plan concluido y contexto activo | Nueva propuesta sin ACTIVE_PLAN_EXISTS falso; plan anterior consultable |
| R4 | Continuidad E11 → E02/E04 | Una ronda activa, versiones y auditoría atómicos |
| R5 | Muestras/conjuntos/evaluaciones nuevos | Snapshots originales idénticos y sin selección implícita |
| R6 | Admisión de ejecución de ronda | Permisos nuevos y cero reenvíos de efectos anteriores |
| R7 | HTTP y demostración de dos rondas | Concurrencia, rollback, reinicio y cierre E14 terminal |

Se probarán además: revisión tardía, intento de reutilizar permiso/acción vieja,
plan de otra ronda, contexto incompleto para E04, contradicción técnica durante
continuidad y operaciones fallidas entre commits. El caso de aceptación debe
demostrar dos rondas reales, no solo una transición de estado por SQL.

Fuentes instrumentales verificadas, PostgreSQL, privilegios SQL y operación
productiva conservan sus incrementos propios. Una nueva ronda no convierte
atestaciones humanas en EV-2/EV-3 ni demuestra causalidad.
