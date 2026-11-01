package com.miec.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link EvidenceValidator}.
 *
 * <p>Estas pruebas verifican que una implementación concreta del validador
 * cumple con los invariantes de F07 y F02. Se utiliza una implementación
 * simulada para validar el comportamiento esperado.
 */
@DisplayName("Pruebas del contrato EvidenceValidator MIEC")
class EvidenceValidatorTest {

    /**
     * Implementación simulada del validador para pruebas.
     *
     * <p>Esta implementación devuelve una evaluación basada en la cantidad
     * de evidencias aportadas, sin validar su contenido real.
     */
    private static class StubEvidenceValidator implements EvidenceValidator {

        @Override
        public EvidenceAssessment validate(EvidenceValidationRequest request) {
            if (request == null) {
                throw new NullPointerException("El request es obligatorio");
            }

            EvidenceSufficiency sufficiency;
            List<String> limitations;
            String rationale;

            if (request.hasNoEvidence()) {
                sufficiency = EvidenceSufficiency.NOT_EVALUABLE;
                limitations = List.of("No se aportaron evidencias");
                rationale = "La solicitud no contiene evidencias para evaluar";
            } else if (request.evidenceCount() == 1) {
                sufficiency = EvidenceSufficiency.INSUFFICIENT;
                limitations = List.of("Solo una evidencia disponible");
                rationale = "La evidencia es limitada y no permite una conclusión confiable";
            } else {
                sufficiency = EvidenceSufficiency.SUFFICIENT;
                limitations = List.of();
                rationale = "Se dispone de evidencia suficiente para evaluar";
            }

            return new EvidenceAssessment(
                    sufficiency,
                    request.evidenceIds(),
                    limitations,
                    List.of(),
                    rationale,
                    Instant.now()
            );
        }
    }

    private final EvidenceValidator validator = new StubEvidenceValidator();

    @Test
    @DisplayName("Debe clasificar como NOT_EVALUABLE cuando no hay evidencias")
    void shouldClassifyAsNotEvaluableWhenNoEvidence() {
        EvidenceValidationRequest request = new EvidenceValidationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(),
                "criteria-0.1.0",
                Instant.now()
        );

        EvidenceAssessment assessment = validator.validate(request);

        assertThat(assessment.sufficiency()).isEqualTo(EvidenceSufficiency.NOT_EVALUABLE);
        assertThat(assessment.limitations()).contains("No se aportaron evidencias");
        assertThat(assessment.allowsEvaluation()).isFalse();
    }

    @Test
    @DisplayName("Debe clasificar como INSUFFICIENT cuando hay una sola evidencia")
    void shouldClassifyAsInsufficientWithSingleEvidence() {
        EvidenceValidationRequest request = new EvidenceValidationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(UUID.randomUUID()),
                "criteria-0.1.0",
                Instant.now()
        );

        EvidenceAssessment assessment = validator.validate(request);

        assertThat(assessment.sufficiency()).isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(assessment.limitations()).contains("Solo una evidencia disponible");
        assertThat(assessment.allowsEvaluation()).isFalse();
    }

    @Test
    @DisplayName("Debe clasificar como SUFFICIENT cuando hay múltiples evidencias")
    void shouldClassifyAsSufficientWithMultipleEvidence() {
        EvidenceValidationRequest request = new EvidenceValidationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                "criteria-0.1.0",
                Instant.now()
        );

        EvidenceAssessment assessment = validator.validate(request);

        assertThat(assessment.sufficiency()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(assessment.limitations()).isEmpty();
        assertThat(assessment.allowsEvaluation()).isTrue();
    }

    @Test
    @DisplayName("Debe rechazar un request nulo")
    void shouldRejectNullRequest() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obligatorio");
    }

    @Test
    @DisplayName("Debe conservar los identificadores de evidencia en el resultado")
    void shouldPreserveEvidenceIdsInResult() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        EvidenceValidationRequest request = new EvidenceValidationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(first, second),
                "criteria-0.1.0",
                Instant.now()
        );

        EvidenceAssessment assessment = validator.validate(request);

        assertThat(assessment.evidenceIds()).containsExactly(first, second);
    }

    @Test
    @DisplayName("Debe proporcionar un rationale que justifique la clasificación")
    void shouldProvideRationaleForClassification() {
        EvidenceValidationRequest request = new EvidenceValidationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(UUID.randomUUID()),
                "criteria-0.1.0",
                Instant.now()
        );

        EvidenceAssessment assessment = validator.validate(request);

        assertThat(assessment.rationale()).isNotBlank();
        assertThat(assessment.assessedAt()).isNotNull();
    }
}