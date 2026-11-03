package com.miec.orchestration;

import com.miec.decision.DeterministicResultEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Instala el evaluador puro sin introducir dependencias de Spring en el dominio. */
@Configuration(proxyBeanMethods=false)
class EvaluationConfiguration {
    /** Un único evaluador numérico versionado disponible por su clase y por ResultEvaluator. */
    @Bean DeterministicResultEvaluator deterministicResultEvaluator() {return new DeterministicResultEvaluator();}
    /** Instala política de alternativas sin convertirlas en autorizaciones. */
    @Bean com.miec.decision.MvpAlternativePolicy mvpAlternativePolicy(){return new com.miec.decision.MvpAlternativePolicy();}
    /** Conecta F02 puro con el reloj explícito proporcionado por el caso de uso. */
    @Bean com.miec.decision.DefaultDecisionEngine defaultDecisionEngine(com.miec.decision.MvpAlternativePolicy policy){return new com.miec.decision.DefaultDecisionEngine(policy,policy);}
}
