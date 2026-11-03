package com.miec.orchestration;

import com.miec.action.ExecutionRequest;
import com.miec.action.IdempotentActionExecutor;
import com.miec.execution.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Destinatario simulado exclusivo de tests; no acredita durabilidad remota ni entra en el JAR. */
@TestConfiguration(proxyBeanMethods=false)
public class ExecutionTestConfiguration {
    /** Instala un adaptador controlable sin credenciales ni procesos externos. */
    @Bean TestAdapter testExecutionAdapter() { return new TestAdapter(); }

    /** Ledger concurrente en memoria para verificar orden, incertidumbre y ausencia de reenvíos ciegos. */
    public static final class TestAdapter implements ExecutableActionAdapter {
        final ConcurrentHashMap<UUID,String> ledger=new ConcurrentHashMap<>();
        final AtomicInteger effects=new AtomicInteger();
        volatile Runnable beforeEffect=()->{};
        volatile Runnable duringLookup=()->{};
        volatile Runnable afterEffect=()->{};
        volatile String binding="a".repeat(64);
        /** Hook del servidor simulado para pruebas HTTP en otro paquete; nunca pertenece al catálogo productivo. */
        public void onLookup(Runnable hook) { duringLookup=hook; }
        /** Mantiene una identidad distinta de los adaptadores productivos. */
        @Override public ExecutableActions.Descriptor descriptor() { return new ExecutableActions.Descriptor("test-recipient","1","test-destination",binding); }
        /** Captura sin producir efectos; las pruebas controlan lo que ocurre durante execute. */
        @Override public IdempotentActionExecutor bind(ExecutionRequest request) {
            return new IdempotentActionExecutor() {
                /** Devuelve los bytes capturados, nunca una solicitud reconstruida. */
                @Override public ExecutionRequest request() { return request; }
                /** Consulta sin incrementar el contador de efectos. */
                @Override public Lookup lookup(Context context) {
                    duringLookup.run();
                    String result=ledger.get(context.operation().key());
                    return new Lookup(result==null?Status.NOT_FOUND:Status.APPLIED,result);
                }
                /** Deduplica una clave durante el proceso y permite simular pérdida de respuesta. */
                @Override public String execute(Context context) {
                    beforeEffect.run();
                    String reference=ledger.computeIfAbsent(context.operation().key(),key->{effects.incrementAndGet(); return "test-result:"+key;});
                    afterEffect.run(); return reference;
                }
            };
        }
    }
}
