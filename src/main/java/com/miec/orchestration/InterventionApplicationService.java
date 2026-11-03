package com.miec.orchestration;

import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.miec.orchestration.InterventionCommands.*;
import static com.miec.orchestration.InterventionViews.*;

/** Única fachada pública de MVP-02; resuelve permisos antes de acceder al flujo transaccional interno. */
@Service
public class InterventionApplicationService {
    private final InterventionWorkflow workflow;
    private final InterventionIdentity identity;
    /** Recibe flujo interno y fuente confiable de identidad. */
    public InterventionApplicationService(InterventionWorkflow workflow,InterventionIdentity identity) { this.workflow=workflow; this.identity=identity; }
    /** Completa inicialización con el autor autenticado. */
    public Preparation initialize(UUID cycle,Initialize command) { return workflow.initialize(cycle,command,identity.author()); }
    /** Registra observación humana atribuida al autor autenticado. */
    public Preparation observe(UUID cycle,Observe command) { return workflow.observe(cycle,command,identity.author()); }
    /** Conserva diagnóstico y contexto sin declarar evidencia verificada. */
    public Preparation diagnose(UUID cycle,Diagnose command) { return workflow.diagnose(cycle,command,identity.author()); }
    /** Propone un plan con identidad de servidor. */
    public Plan propose(UUID cycle,ProposePlan command) { return workflow.propose(cycle,command,identity.author()); }
    /** Consulta un plan después de comprobar el rol lector. */
    public Plan getPlan(UUID plan) { identity.reader(); return workflow.getPlan(plan); }
    /** Expone objetivo y diagnóstico a los lectores autenticados antes de tomar una decisión. */
    public ReviewContext reviewContext(UUID plan) { identity.reader(); return workflow.reviewContext(plan); }
    /** Solicita permiso sin aceptar identidad o scope desde el cuerpo. */
    public Authorization request(UUID plan,RequestAuthorization command) { return workflow.requestAuthorization(plan,command,identity.author()); }
    /** Consulta el estado de una solicitud y su decisión. */
    public Authorization getAuthorization(UUID authorization) { identity.reader(); return workflow.getAuthorization(authorization); }
    /** Aprueba sin activar permisos parciales ni aceptar el aprobador desde el cuerpo. */
    public Authorization approve(UUID authorization,Decide command) { return workflow.decide(authorization,command,true,identity.approver()); }
    /** Rechaza el plan y preserva las decisiones humanas anteriores. */
    public Authorization reject(UUID authorization,Decide command) { return workflow.decide(authorization,command,false,identity.approver()); }
}
