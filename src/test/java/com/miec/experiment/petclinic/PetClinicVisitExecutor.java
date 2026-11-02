package com.miec.experiment.petclinic;

import com.miec.action.ExecutionRequest;
import com.miec.action.IdempotentActionExecutor;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** Adaptador autenticado del contrato C09; no transforma una ausencia visible en prueba NO_EFFECT. */
public final class PetClinicVisitExecutor implements IdempotentActionExecutor {
    public static final String DESTINATION_ID="petclinic-experiment-001";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final URI baseUri;
    private final String apiKey;
    private final ExecutionRequest request;
    private final Duration timeout;
    private final HttpClient client;

    /** Respuesta vinculada a una clave y huella; la fecha remota no gobierna el lease de MIEC. */
    private record RemoteResult(String status,UUID operationKey,String fingerprint,int visitId,
                                String resultReference,String recordedAt) { }
    /** Error estable; un 404 ajeno al contrato no equivale a NOT_FOUND. */
    private record RemoteError(String code,String message) { }

    /**
     * Configura un destino raíz, sin redirecciones. HTTP se admite solo en loopback
     * para el experimento; fuera de él se exige HTTPS. El secreto nunca se incluye
     * en mensajes de excepción ni se expone mediante un accessor.
     */
    public PetClinicVisitExecutor(URI baseUri,String apiKey,ExecutionRequest request,Duration timeout) {
        this.baseUri=Objects.requireNonNull(baseUri);this.apiKey=Objects.requireNonNull(apiKey);
        this.request=Objects.requireNonNull(request);this.timeout=Objects.requireNonNull(timeout);
        String host=baseUri.getHost();
        boolean loopback="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"[::1]".equals(host)||"::1".equals(host);
        if(host==null||baseUri.getRawUserInfo()!=null||baseUri.getRawQuery()!=null||baseUri.getRawFragment()!=null
                || !(baseUri.getPath().isEmpty()||baseUri.getPath().equals("/"))
                || !("https".equalsIgnoreCase(baseUri.getScheme())||("http".equalsIgnoreCase(baseUri.getScheme())&&loopback))) {
            throw new IllegalArgumentException("Destino HTTP inseguro o incompatible con el contrato");
        }
        if(apiKey.length()<16||apiKey.chars().anyMatch(c->c<33||c>126)
                ||timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(30))>0
                ||!"application/json".equals(request.mediaType())||request.bodyBytes().length>65536) {
            throw new IllegalArgumentException("Credenciales, solicitud o timeout inválidos");
        }
        this.client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Construye JSON determinista y captura los bytes que se enviarán realmente al destinatario. */
    public static ExecutionRequest visitRequest(int ownerId,int petId,LocalDate date,String description) {
        Objects.requireNonNull(date);Objects.requireNonNull(description);
        if(ownerId<1||petId<1||description.isBlank()||description.length()>255) throw new IllegalArgumentException("Visita inválida");
        var fields=new LinkedHashMap<String,Object>();
        fields.put("ownerId",ownerId);fields.put("petId",petId);fields.put("date",date.toString());fields.put("description",description);
        return new ExecutionRequest(DESTINATION_ID,"application/json",JSON.writeValueAsString(fields));
    }

    /** Devuelve la misma solicitud inmutable durante toda la vida del adaptador. */
    @Override public ExecutionRequest request() { return request; }

    /**
     * Consulta un resultado autenticado. Solo OPERATION_NOT_FOUND del contrato
     * admite un envío idempotente; ninguna respuesta de error demuestra NO_EFFECT.
     */
    @Override public Lookup lookup(Context context) {
        checkContext(context);
        try {
            var response=client.send(builder("/miec/operations/"+context.operation().key()).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==200) return new Lookup(Status.APPLIED,validateResult(response.body(),context).resultReference());
            if(response.statusCode()==404&&"OPERATION_NOT_FOUND".equals(JSON.readValue(response.body(),RemoteError.class).code())) {
                return new Lookup(Status.NOT_FOUND,null);
            }
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(IOException|RuntimeException unavailable) { /* La respuesta no es evidencia de ausencia. */ }
        return new Lookup(Status.UNKNOWN,null);
    }

    /** Envía los bytes capturados con clave y huella; una pérdida de respuesta conserva incertidumbre. */
    @Override public String execute(Context context) {
        checkContext(context);
        var message=builder("/miec/visits").header("Content-Type","application/json")
                .header("Idempotency-Key",context.operation().key().toString())
                .header("X-MIEC-Destination",request.destinationId()).header("X-MIEC-Fingerprint",request.fingerprint())
                .header("X-MIEC-Fencing-Token",Long.toString(context.ownership().fencingToken()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.bodyBytes())).build();
        try {
            var response=client.send(message,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200&&response.statusCode()!=201) {
                throw new IllegalStateException("PetClinic rechazó la operación: HTTP "+response.statusCode());
            }
            return validateResult(response.body(),context).resultReference();
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("PetClinic: envío interrumpido; resultado incierto",interrupted);
        } catch(IOException unavailable) { throw new IllegalStateException("PetClinic: respuesta no disponible; resultado incierto",unavailable); }
    }

    /** Valida identidad, huella y referencia antes de aceptar una respuesta aplicada. */
    private RemoteResult validateResult(String body,Context context) {
        var result=JSON.readValue(body,RemoteResult.class);
        if(result==null||!"APPLIED".equals(result.status())||!context.operation().key().equals(result.operationKey())
                ||!request.fingerprint().equals(result.fingerprint())||result.visitId()<1
                ||!("petclinic:visit:"+result.visitId()).equals(result.resultReference())) {
            throw new IllegalStateException("PetClinic: respuesta incompatible con la operación");
        }
        Instant.parse(result.recordedAt());
        return result;
    }

    /** Construye mensajes autenticados sin colocar secretos en URLs. */
    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout).header("Accept","application/json").header("X-MIEC-Api-Key",apiKey);
    }

    /** Impide usar este adaptador con otros bytes o una configuración de destino distinta. */
    private void checkContext(Context context) {
        Objects.requireNonNull(context);
        if(!request.equals(context.request())) throw new IllegalArgumentException("Contexto de otra solicitud");
    }
}
