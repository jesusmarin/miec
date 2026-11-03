package com.miec.execution;

import com.miec.action.ExecutionRequest;
import com.miec.action.IdempotentActionExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/**
 * Adaptador HTTP genérico opt-in del protocolo IR-009. Ningún dominio experimental
 * se incorpora al JAR. Requiere un destinatario con efecto y ledger atómicos;
 * la interfaz y las pruebas de transporte no certifican esa garantía por sí solas.
 */
@Component
@ConditionalOnProperty(name="miec.recipient.enabled",havingValue="true")
public class ConfiguredHttpActionAdapter implements ExecutableActionAdapter {
    private final JsonMapper json;
    private final URI base;
    private final String key;
    private final String executePath;
    private final Duration timeout;
    private final HttpClient client;
    private final ExecutableActions.Descriptor descriptor;

    /**
     * Configura un único enlace del servidor. HTTP solo se permite en IP loopback
     * literal; fuera de ella exige HTTPS. No sigue redirecciones ni URLs de resultados.
     * La huella del enlace incluye rutas y protocolo, pero excluye la clave rotatoria.
     */
    public ConfiguredHttpActionAdapter(JsonMapper json,
            @Value("${miec.recipient.adapter-id:http-recipient-v1}") String id,
            @Value("${miec.recipient.adapter-version:1}") String version,
            @Value("${miec.recipient.destination-id}") String destination,
            @Value("${miec.recipient.base-url}") URI base,
            @Value("${miec.recipient.api-key}") String key,
            @Value("${miec.recipient.execute-path:/miec/operations}") String executePath,
            @Value("${miec.recipient.timeout:PT10S}") Duration timeout) {
        this.json=json; this.base=Objects.requireNonNull(base); this.key=Objects.requireNonNull(key);
        this.executePath=executePath; this.timeout=timeout;
        String host=base.getHost();
        boolean local="127.0.0.1".equals(host)||"[::1]".equals(host)||"::1".equals(host);
        if(host==null||destination==null||!destination.matches("[A-Za-z0-9._:-]{1,255}")
                ||base.getRawUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null
                ||!(base.getPath().isEmpty()||base.getPath().equals("/"))
                ||!("https".equalsIgnoreCase(base.getScheme())||("http".equalsIgnoreCase(base.getScheme())&&local))
                ||executePath==null||!executePath.matches("/[A-Za-z0-9/_-]+")
                ||key.length()<16||key.chars().anyMatch(c->c<33||c>126)
                ||timeout==null||timeout.compareTo(Duration.ofMillis(1))<0||timeout.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Configuración de destinatario HTTP inválida");
        String binding=new ExecutionRequest(destination,"application/vnd.miec.adapter-binding-v1+json",
                json.writeValueAsString(List.of(id,version,base.normalize().toString(),executePath,"/miec/operations/","IR009-HTTP-01"))).fingerprint();
        descriptor=new ExecutableActions.Descriptor(id,version,destination,binding);
        client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Expone identidad y huella no secreta de las rutas instaladas. */
    @Override public ExecutableActions.Descriptor descriptor() { return descriptor; }

    /** Valida JSON y conserva cuerpo exacto; construir el ejecutor no hace solicitudes HTTP. */
    @Override public IdempotentActionExecutor bind(ExecutionRequest request) {
        if(!descriptor.destinationId().equals(request.destinationId())||!"application/json".equals(request.mediaType())
                ||request.bodyBytes().length>65536||!json.readTree(request.body()).isObject())
            throw new IllegalArgumentException("Solicitud HTTP incompatible con el adaptador");
        return new Bound(request);
    }

    /** Ejecutor inmutable vinculado a una solicitud y al enlace configurado del servidor. */
    private final class Bound implements IdempotentActionExecutor {
        private final ExecutionRequest request;
        /** Captura sin reconstruir los bytes que se aprobaron. */
        Bound(ExecutionRequest request) { this.request=request; }
        /** Entrega el snapshot exacto al registro durable de IR-009. */
        @Override public ExecutionRequest request() { return request; }
        /** Solo un 404 del contrato habilita el envío idempotente; otros errores conservan UNKNOWN. */
        @Override public Lookup lookup(Context context) {
            check(context);
            try {
                var response=send(builder("/miec/operations/"+context.operation().key()).GET().build());
                var body=read(response);
                if(response.statusCode()==200) return new Lookup(Status.APPLIED,confirmed(body,context));
                if(response.statusCode()==404&&"OPERATION_NOT_FOUND".equals(json.readTree(body).path("code").asString()))
                    return new Lookup(Status.NOT_FOUND,null);
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch(IOException|RuntimeException unavailable) { /* No se infiere ausencia de efecto. */ }
            return new Lookup(Status.UNKNOWN,null);
        }
        /** Envía bytes aprobados con la clave estable, huella y fencing del intento actual. */
        @Override public String execute(Context context) {
            check(context);
            var message=builder(executePath).header("Content-Type",request.mediaType())
                    .header("Idempotency-Key",context.operation().key().toString())
                    .header("X-MIEC-Destination",request.destinationId()).header("X-MIEC-Fingerprint",request.fingerprint())
                    .header("X-MIEC-Fencing-Token",Long.toString(context.ownership().fencingToken()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request.bodyBytes())).build();
            try {
                var response=send(message); var body=read(response);
                if(response.statusCode()!=200&&response.statusCode()!=201) throw new IllegalStateException("Destinatario rechazó el envío; resultado incierto");
                return confirmed(body,context);
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Envío interrumpido; resultado incierto");
            } catch(IOException unavailable) { throw new IllegalStateException("Respuesta no disponible; resultado incierto"); }
        }
        /** Rechaza cualquier uso con un snapshot diferente al capturado. */
        private void check(Context context) {
            if(!request.equals(context.request())) throw new IllegalArgumentException("Contexto de otra solicitud");
        }
        /** Valida todas las identidades antes de aceptar una referencia; no utiliza fechas remotas para leases. */
        private String confirmed(String body,Context context) {
            JsonNode result=json.readTree(body); String reference=result.path("resultReference").asString();
            if(!"APPLIED".equals(result.path("status").asString())
                    ||!context.operation().key().toString().equals(result.path("operationKey").asString())
                    ||!request.destinationId().equals(result.path("destinationId").asString())
                    ||!request.fingerprint().equals(result.path("fingerprint").asString())
                    ||reference.isBlank()||reference.length()>500||!reference.equals(reference.trim()))
                throw new IllegalStateException("Resultado no corresponde a la solicitud aprobada");
            return reference;
        }
    }

    /** Construye mensajes sin credenciales en URLs ni datos aportados por la respuesta remota. */
    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("Accept","application/json").header("X-MIEC-Api-Key",key);
    }

    /** Aplica timeout a la respuesta completa, incluido su cuerpo, y cancela al interrumpir o vencer. */
    private HttpResponse<byte[]> send(HttpRequest message) throws IOException,InterruptedException {
        var pending=client.sendAsync(message,info->new LimitedBody());
        try { return pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch(ExecutionException|TimeoutException unavailable) { throw new IOException("Respuesta no disponible"); }
        finally { if(!pending.isDone()) pending.cancel(true); }
    }

    /** Exige JSON y UTF-8 válido, sin aceptar tipos con un prefijo parecido a application/json. */
    private String read(HttpResponse<byte[]> response) throws IOException {
        String type=response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim();
        if(!type.equalsIgnoreCase("application/json")) throw new IOException("Respuesta incompatible con el protocolo");
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
    }

    /** Suscriptor con memoria acotada; rechaza cuerpos grandes antes de acumularlos. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        /** Se completa únicamente al recibir todo el cuerpo dentro del límite. */
        @Override public CompletionStage<byte[]> getBody() { return result; }
        /** Solicita un lote a la vez para mantener backpressure. */
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        /** Cancela al superar 64 KiB sin exponer el contenido remoto en errores. */
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>65536) { subscription.cancel(); result.completeExceptionally(new IOException("Respuesta demasiado grande")); return; }
                byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        /** Propaga fallo de transporte para conservar incertidumbre. */
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        /** Publica bytes exactos al terminar la respuesta. */
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
