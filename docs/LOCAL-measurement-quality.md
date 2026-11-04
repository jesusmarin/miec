# Calidad e interpretación de las mediciones — MVP local v0.1.0

Fecha: 2026-11-04. Referencia operativa para registrar, revisar y comunicar los
resultados del MVP. Complementa la etiqueta `v0.1.0`; no cambia sus políticas,
contratos de datos ni conclusiones históricas.

**Una medición humana no acredita por sí sola suficiencia instrumental ni
causalidad.** La revisión humana, el cierre del conjunto y la aprobación de una
intervención no cambian esa regla. Las descripciones OpenAPI del incremento la
explicitan; se verán al compilar y arrancar esa revisión. No se reconstruye ni se
modifica el contenido de los JAR históricos de la entrega etiquetada.

## 1. Separar lo que se observa de lo que se concluye

| Aspecto | Lo que el MVP permite afirmar | Lo que no acredita por sí mismo |
|---|---|---|
| Registro | Una identidad declaró un valor, origen, unidad y fecha | Exactitud/calibración de un instrumento o autenticidad del origen declarado |
| Revisión | Otro usuario revisó la muestra y registró ACCEPTED_WITH_LIMITATIONS o REJECTED | Verificación instrumental ni eliminación de las limitaciones originales |
| Conjunto cerrado | Se fijaron muestras/revisiones concretas para comparar | Suficiencia, representatividad estadística o selección libre de sesgo |
| Comparación numérica | Cumplimiento del umbral, delta y dirección favorable/neutra/desfavorable | Efecto causal o conclusión favorable con evidencia insuficiente |
| Ejecución APPLIED | Existe fundamento técnico de aplicación del efecto | Que mejoró el objetivo o que la medición posterior es fiable |
| Transición/cierre | Se confirmó una etapa administrativa y su auditoría | Validación científica o causal del resultado |

Que una fuente se llame «sensor», «SQL», «CSV» o «instrumento calibrado» no modifica
su nivel de confianza. Los campos `source`, método y referencia de procedimiento
son declaraciones; no autentican ni consultan ese origen. Un valor transcrito desde
un dispositivo por una persona sigue siendo un reporte humano por estas rutas.

## 2. Regla vigente: SOURCE-QUALITY-V1

Las líneas base y mediciones posteriores del flujo actual conservan evidencia
`HUMAN_REPORT` con calidad `UNVERIFIED`. No hay un campo HTTP para elevar EV,
cambiar la calidad a VERIFIED o seleccionar otra política. El evaluador productivo
recalcula la suficiencia y rechaza una evaluación interna con EV inventado.

| Entradas seleccionadas | Suficiencia | Clasificación productiva | Confianza |
|---|---|---|---|
| Pares humanos sin revisión seleccionada | EV-1 INSUFFICIENT | RS-05 INCONCLUSIVE | LOW |
| Pares humanos revisados y aceptados con limitaciones, sin rechazo seleccionado | EV-1 INSUFFICIENT | RS-05 INCONCLUSIVE | LOW |
| Alguna muestra seleccionada tiene revisión REJECTED | EV-0 NOT_EVALUABLE | RS-05 INCONCLUSIVE | UNDETERMINED |
| Snapshot sin pares, tratado defensivamente por la política | EV-0 NOT_EVALUABLE | RS-05 INCONCLUSIVE | UNDETERMINED |

El caso sin pares describe la política interna; no autoriza a cerrar un conjunto
incompleto por HTTP. El cierre exige un par explícito por indicador y sus demás
guardas. Las limitaciones de las revisiones, sus ausencias y rechazos permanecen
en la evaluación. LOW no eleva EV-1 a suficiente; UNVERIFIED no afirma que el dato
sea falso, sino que este flujo no acredita su verificación instrumental.

EV-2/EV-3/EV-4 existen en el modelo general, pero **no se asignan** a estos reportes
por tener dos identidades, más filas, un texto de método, una revisión aceptada,
un conjunto cerrado o un destinatario autenticado. El clasificador numérico se
prueba también con suficiencia hipotética; eso no habilita tales niveles en la
ruta productiva humana.

No editar calidad, niveles EV o resultados por SQL. Esta revisión tampoco cambia
`SOURCE-QUALITY-V1` ni `EVALUATION-POLICY-V1`: no se altera el cálculo o snapshot
persistido y no se reinterpreta la historia con una regla nueva.

## 3. Procedimiento de registro y revisión

AUTHOR registra el origen identificable, valor decimal, unidad exacta, método y
fecha realmente observada. Mantener disponibles los registros de soporte por un
canal privado, sin introducir credenciales en `source` o en las descripciones.
Los datos deben corresponder al indicador, ciclo, plan, ejecución y ronda elegidos.
La fecha de recepción o la precisión decimal no certifican la precisión del método.

APPROVER distinto del autor revisa el soporte y deja explícitas las limitaciones:

- Cómo se obtuvo/transcribió el valor y quién lo observó.
- Procedimiento aplicado, unidad, ventana y condiciones de medición.
- Qué aspectos del origen, instrumento y comparabilidad no se han acreditado.
- Ausencias, incertidumbres o motivos de rechazo; no ocultarlos porque el valor
  posterior cumpla el objetivo.

Registrar ACCEPTED_WITH_LIMITATIONS significa aceptar esa revisión con sus
limitaciones, no aprobar EV-2 ni reemplazar la evidencia original. REJECTED debe
conservarse; no se modifica la muestra o revisión para obtener otra conclusión.

Ejemplo de texto para `limitations`, adaptándolo a lo realmente observado:

> Reporte humano transcrito del registro local. La revisión no acredita
> autenticación instrumental, calibración ni comparabilidad metodológica validada.
> La comparación antes/después no permite atribuir causalidad a la intervención.

No es una validación nueva de texto libre: la aplicación conserva la declaración,
pero no determina que escribir estas palabras pruebe o resuelva esas limitaciones.

Antes de cerrar, revisar qué pares y revisiones quedarán seleccionados. No elegir
automáticamente la muestra más favorable ni presentar el par como promedio o
estimación poblacional. EXPLICIT_PAIR_V1 fija una muestra base y una posterior por
indicador; no convierte unidades ni agrega observaciones.

Una vez cerrado, el run no admite nuevas mediciones/revisiones por esas APIs.
Si se descubre un error, conservar y señalar el conjunto original, registrar el
incidente y definir una nueva observación en un contexto permitido. No prometer
un endpoint de corrección, reapertura o sustitución que el MVP no tiene.
Las rondas nuevas preservan las evidencias y conclusiones anteriores.

## 4. Cómo comunicar resultados sin sobreatribuir

Ejemplo ilustrativo: umbral LTE 8, línea base 10 y posterior 7, unidad constante.
El delta es -3; el valor posterior cumple y el cambio numérico es favorable.
Con los reportes humanos actuales la conclusión sigue siendo **EV-1, RS-05 y LOW**,
aunque otro usuario haya aceptado ambas revisiones con limitaciones.

Comunicación adecuada:

> En el par seleccionado el valor pasó de 10 a 7 y cumplió el umbral LTE 8.
> SOURCE-QUALITY-V1 conserva EV-1 y RS-05: la evidencia humana no acredita
> suficiencia instrumental ni permite atribuir ese cambio a la intervención.

No comunicar «mejora demostrada por MIEC», «causalidad confirmada» o «evidencia
instrumental verificada» a partir de ese registro. Tampoco ocultar un empeoramiento
porque el valor posterior aún esté dentro del umbral: cumplimiento y dirección del
cambio son resultados distintos.

En el piloto PetClinic, 0 → 0 para visitas adicionales conserva el criterio numérico,
no muestra una mejora. Sus dos rondas permanecieron RS-05 y no atribuyeron
causalidad ni calidad instrumental a los conteos humanos registrados.
Ver [aceptación del piloto](MVP-08-local-project-acceptance.md).

Al presentar una evaluación, conservar juntos: política y versiones, set/indicador,
valores y unidad, cumplimiento, delta, clasificación, suficiencia, confianza,
limitaciones e incertidumbres. Un avance a E09 o cierre administrativo no eleva
ninguno de esos atributos. Una decisión humana posterior debe mantener visible
la conclusión insuficiente; no reemplazarla por la recomendación o por el cierre.

La verificación de ejecución IR-009 y la calidad de la medición son contratos
distintos. Un recibo remoto autenticado puede sustentar APPLIED sin autenticar la
medición del objetivo, validar su instrumento o demostrar una relación causal.

## 5. Qué falta para fuentes instrumentales

La deuda permanece abierta. Antes de desarrollar un canal instrumental, definir
por separado su contrato de fuente, autenticación, integridad, identidad del
instrumento/método, trazabilidad y unidades, fechas y sincronización, incertidumbre
y tratamiento de fallos/replays. Precisar cómo se acredita la comparabilidad de
base y posterior, y cómo se conservan sus registros de soporte.

Después, una política de calidad explícita y versionada debe determinar qué nivel
EV se justifica con esos fundamentos y qué limitaciones permanecen. Autenticar una
fuente es necesario para ese contrato, pero no asigna automáticamente EV-3.
Toda política nueva debe conservar las conclusiones originales y tener pruebas
de rechazo de suplantación, datos incompatibles e insuficiencia.

La evaluación causal exige su propio diseño y fundamentos; una fuente instrumental
fiable y un cambio temporal antes/después tampoco bastan por sí solos. No se
incorpora inferencia causal ni se declara cumplida por este incremento documental.

## 6. Trazabilidad y validación

| Regla | Componente / pruebas existentes |
|---|---|
| Reporte humano y calidad original intacta tras revisión | PostMeasurementWorkflow; PostMeasurementWorkflowTest |
| Ausencia/aceptación humana → EV-1; rechazo → EV-0; siempre RS-05 | MeasurementEvidencePolicy; DeterministicResultEvaluatorTest.shouldPreserveConservativeEvidencePolicy |
| No se acepta EV fabricado | DeterministicResultEvaluatorTest.shouldRejectForgedEvidenceAssessment |
| Cumplimiento separado del cambio y clasificación | DeterministicResultEvaluatorTest.shouldSeparateThresholdFromImprovement |
| Registro, revisión, cierre y documentación HTTP | MeasurementApiTest; PostMeasurementApiTest |
| Lectura del snapshot, suficiencia y limitaciones | EvaluationApiTest |

Desde la raíz, con el SDK disponible:

```powershell
.\mvnw.cmd '-Dtest=DeterministicResultEvaluatorTest,PostMeasurementWorkflowTest,PostMeasurementApiTest,EvaluationApiTest,MeasurementApiTest' test
```

La ampliación de este incremento afecta Javadoc/OpenAPI y documentación; no añade
estados, campos, migraciones, niveles EV, clasificadores ni texto persistido de la
política. Los resultados de revalidación se registran al final de esta guía.

Referencias de implementación: [política de calidad](../src/main/java/com/miec/decision/MeasurementEvidencePolicy.java),
[evaluador](../src/main/java/com/miec/decision/DeterministicResultEvaluator.java).
Contratos: [mediciones posteriores](MVP-05-post-measurements.md),
[evaluación](MVP-06-evaluation.md) y [decisión/cierre](MVP-06-decision-closure.md).

### Resultado de esta revisión

El 2026-11-04 se completó la revalidación a las 17:18:54, hora de Bogotá:

| Clase existente | Casos aprobados |
|---|---:|
| DeterministicResultEvaluatorTest | 10 |
| PostMeasurementWorkflowTest | 5 |
| PostMeasurementApiTest | 3 |
| EvaluationApiTest | 2 |
| MeasurementApiTest | 2 |
| **Total enfocado** | **22** |

Cero fallos, errores u omisiones. Se compiló el código con las ampliaciones de
Javadoc/OpenAPI y se reutilizaron las pruebas de política, registro/revisión y HTTP.
No se añadieron pruebas que duplicaran sus verificaciones ni se reejecutaron CI,
el piloto conjunto o la regresión completa. Las bases del usuario no se utilizaron.
Registro local excluido de Git: `review-measurement-quality.log`; informes en
`target/surefire-reports`. La suficiencia instrumental y la atribución causal siguen
siendo capacidades no acreditadas por el flujo humano actual.
