package com.miec.execution;

import com.miec.action.ExecutionRequest;
import com.miec.action.IdempotentActionExecutor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Catálogo cerrado de beans del servidor; una API no puede instalar código ni sustituir endpoints. */
@Component
public class ExecutableActionCatalog {
    private final Map<String,ExecutableActionAdapter> adapters;

    /** Rechaza instalaciones ambiguas al arrancar; un catálogo vacío no impide consultar planes históricos. */
    public ExecutableActionCatalog(List<ExecutableActionAdapter> installed) {
        var map=new LinkedHashMap<String,ExecutableActionAdapter>();
        for(var adapter:installed) {
            var descriptor=Objects.requireNonNull(adapter.descriptor());
            if(descriptor.adapterId()==null||!descriptor.adapterId().matches("[A-Za-z0-9._-]{1,100}")
                    ||descriptor.adapterVersion()==null||!descriptor.adapterVersion().matches("[A-Za-z0-9._-]{1,100}")
                    ||descriptor.destinationId()==null||descriptor.destinationId().isBlank()||descriptor.destinationId().length()>255
                    ||descriptor.bindingFingerprint()==null||!descriptor.bindingFingerprint().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Descriptor de adaptador inválido");
            if(map.putIfAbsent(descriptor.adapterId(),adapter)!=null)
                throw new IllegalArgumentException("Identidad de adaptador duplicada");
        }
        adapters=Map.copyOf(map);
    }

    /** Solo expone identidad pública; nunca devuelve secretos ni objetos ejecutores. */
    public List<ExecutableActions.Descriptor> descriptors() {
        return adapters.values().stream().map(ExecutableActionAdapter::descriptor)
                .sorted(Comparator.comparing(ExecutableActions.Descriptor::adapterId)).toList();
    }

    /** Captura el enlace y la solicitud antes de que una persona apruebe el plan, sin ejecutar nada. */
    public ExecutableActions.Definition capture(ExecutableActions.Spec spec) {
        Objects.requireNonNull(spec);
        var adapter=adapter(spec.adapterId()); var descriptor=adapter.descriptor();
        if(!descriptor.adapterVersion().equals(spec.adapterVersion())||!descriptor.destinationId().equals(spec.destinationId()))
            throw new IllegalArgumentException("Adaptador, versión o destino no instalados");
        var request=new ExecutionRequest(spec.destinationId(),spec.mediaType(),spec.body());
        if(request.bodyBytes().length>65536) throw new IllegalArgumentException("Solicitud superior a 64 KiB");
        requireExact(adapter.bind(request),request);
        return new ExecutableActions.Definition("01",descriptor.adapterId(),descriptor.adapterVersion(),descriptor.destinationId(),
                descriptor.bindingFingerprint(),request.mediaType(),request.body());
    }

    /** Rechaza configuración modificada tras aprobar; nunca reinterpreta automáticamente planes históricos. */
    public IdempotentActionExecutor resolve(ExecutableActions.Definition definition) {
        if(definition==null||!"01".equals(definition.schemaVersion())) throw new IllegalArgumentException("Definición ejecutable ausente o incompatible");
        var adapter=adapter(definition.adapterId()); var descriptor=adapter.descriptor();
        if(!descriptor.adapterVersion().equals(definition.adapterVersion())||!descriptor.destinationId().equals(definition.destinationId())
                ||!descriptor.bindingFingerprint().equals(definition.bindingFingerprint()))
            throw new IllegalArgumentException("El enlace del adaptador cambió desde la aprobación");
        var request=definition.request();
        if(request.bodyBytes().length>65536) throw new IllegalArgumentException("Solicitud superior a 64 KiB");
        return requireExact(adapter.bind(request),request);
    }

    /** Devuelve exclusivamente un adaptador previamente instalado. */
    private ExecutableActionAdapter adapter(String id) {
        var adapter=adapters.get(id);
        if(adapter==null) throw new IllegalArgumentException("Adaptador no instalado");
        return adapter;
    }

    /** Detecta adaptadores que cambian la solicitud aprobada durante su captura. */
    private static IdempotentActionExecutor requireExact(IdempotentActionExecutor executor,ExecutionRequest request) {
        if(executor==null||!request.equals(executor.request())) throw new IllegalArgumentException("El adaptador alteró la solicitud");
        return executor;
    }
}
