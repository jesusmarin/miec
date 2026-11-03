package com.miec.orchestration;

import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.miec.orchestration.MeasurementContracts.*;

/** Fachada autenticada del flujo medible; el cliente nunca selecciona su identidad. */
@Service
public class MeasurementApplicationService {
    private final MeasurementWorkflow workflow;
    private final InterventionIdentity identity;
    private final PostMeasurementWorkflow post;
    private final MeasurementQueries queries;
    /** Recibe transacciones internas y fuente de identidad del servidor. */
    MeasurementApplicationService(MeasurementWorkflow workflow,InterventionIdentity identity,PostMeasurementWorkflow post,MeasurementQueries queries) {
        this.workflow=workflow;this.identity=identity;this.post=post;this.queries=queries;
    }
    /** Inicializa con identidad AUTHOR y condiciones completas. */
    public InterventionViews.Preparation initialize(UUID cycle,Initialize command) { return workflow.initialize(cycle,command,identity.author()); }
    /** Consulta objetivo y líneas base con rol lector. */
    public Context context(UUID cycle) { identity.reader(); return workflow.context(cycle); }
    /** Registra una medición manual sin cerrar observación. */
    public InterventionViews.Preparation measure(UUID cycle,Measure command) { return workflow.measure(cycle,command,identity.author()); }
    /** Cierra observación completa con autor y versión controlados. */
    public InterventionViews.Preparation complete(UUID cycle,Complete command) { return workflow.complete(cycle,command,identity.author()); }
    /** Consulta resultados y selección estable sin exigir E08 para leer el historial. */
    public PostContext posts(UUID cycle,UUID plan) {identity.reader();return queries.posts(cycle,plan);}
    /** AUTHOR registra un hecho vinculado al run finalizado; nunca una evaluación. */
    public PostContext recordPost(UUID cycle,UUID plan,PostMeasure command) {return post.record(cycle,plan,command,identity.author());}
    /** APPROVER registra revisión separada; el flujo rechaza que coincida con el autor original. */
    public PostContext reviewPost(UUID cycle,UUID plan,ReviewMeasure command) {return post.review(cycle,plan,command,identity.approver());}
    /** APPROVER fija entradas reproducibles sin asignar RS, EV ni cambiar a E09. */
    public PostContext sealPost(UUID cycle,UUID plan,SealPost command) {return post.seal(cycle,plan,command,identity.approver());}
}
