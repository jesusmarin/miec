package com.miec.orchestration;

import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.miec.orchestration.MeasurementContracts.*;

/** Fachada autenticada del flujo medible; el cliente nunca selecciona su identidad. */
@Service
public class MeasurementApplicationService {
    private final MeasurementWorkflow workflow;
    private final InterventionIdentity identity;
    /** Recibe transacciones internas y fuente de identidad del servidor. */
    MeasurementApplicationService(MeasurementWorkflow workflow,InterventionIdentity identity) { this.workflow=workflow; this.identity=identity; }
    /** Inicializa con identidad AUTHOR y condiciones completas. */
    public InterventionViews.Preparation initialize(UUID cycle,Initialize command) { return workflow.initialize(cycle,command,identity.author()); }
    /** Consulta objetivo y líneas base con rol lector. */
    public Context context(UUID cycle) { identity.reader(); return workflow.context(cycle); }
    /** Registra una medición manual sin cerrar observación. */
    public InterventionViews.Preparation measure(UUID cycle,Measure command) { return workflow.measure(cycle,command,identity.author()); }
    /** Cierra observación completa con autor y versión controlados. */
    public InterventionViews.Preparation complete(UUID cycle,Complete command) { return workflow.complete(cycle,command,identity.author()); }
}
