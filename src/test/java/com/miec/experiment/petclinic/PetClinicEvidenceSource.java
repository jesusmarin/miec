package com.miec.experiment.petclinic;

import com.miec.action.IdempotentActionExecutor;
import com.miec.action.integration.DestinationEvidenceSource;
import java.net.URI;
import java.time.Duration;

/** Traduce el protocolo experimental PetClinic a observaciones del contrato común. */
public final class PetClinicEvidenceSource implements DestinationEvidenceSource {
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    /** Recibe conexión y secreto desde el bootstrap de confianza, nunca desde el comando de revisión. */
    public PetClinicEvidenceSource(URI endpoint, String apiKey, Duration timeout) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    /** Identifica el destinatario experimental sin introducir su nombre en el núcleo. */
    public String destinationId() { return PetClinicVisitExecutor.DESTINATION_ID; }

    /** Consulta autenticada con los bytes reconstruidos desde MIEC; el adaptador valida la respuesta. */
    public Observation lookup(IdempotentActionExecutor.Context context) {
        var result = new PetClinicVisitExecutor(endpoint, apiKey, context.request(), timeout).lookup(context);
        return new Observation(context.operation().key(), destinationId(), context.request().fingerprint(),
                result.status(), result.resultReference());
    }
}
