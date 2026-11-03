package com.miec.orchestration;

import com.miec.decision.DeterministicResultEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Instala el evaluador puro sin introducir dependencias de Spring en el dominio. */
@Configuration(proxyBeanMethods=false)
class EvaluationConfiguration {
    /** Un único evaluador numérico versionado disponible por su clase y por ResultEvaluator. */
    @Bean DeterministicResultEvaluator deterministicResultEvaluator() {return new DeterministicResultEvaluator();}
}
