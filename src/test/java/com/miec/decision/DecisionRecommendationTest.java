
package com.miec.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DecisionRecommendationTest {

    private final Instant recommendedAt =
            Instant.parse("2026-10-30T12:00:00Z");

    private DecisionRecommendation createValidRecommendation() {
        return new DecisionRecommendation(
                DecisionAction.CONTINUE_PLAN,
                "  Continuar el plan por sus resultados favorables  ",
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                recommendedAt
        );
    }

    @Test
    @DisplayName("Debe crear una recomendación con datos válidos")
    void shouldCreateRecommendationWithValidData() {
        DecisionRecommendation recommendation =
                createValidRecommendation();

        assertNotNull(recommendation);
        assertEquals(DecisionAction.CONTINUE_PLAN,
                recommendation.action());
        assertEquals("Continuar el plan por sus resultados favorables",
                recommendation.rationale());
        assertEquals(2, recommendation.supportingEvidenceIds().size());
        assertEquals(recommendedAt, recommendation.recommendedAt());
    }

    @Test
    @DisplayName("Debe rechazar una acción nula")
    void shouldRejectNullAction() {
        assertThrows(NullPointerException.class,
                () -> new DecisionRecommendation(
                        null,
                        "Justificación válida",
                        List.of(),
                        recommendedAt
                ));
    }

    @Test
    @DisplayName("Debe rechazar una justificación nula")
    void shouldRejectNullRationale() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionRecommendation(
                        DecisionAction.CONTINUE_PLAN,
                        null,
                        List.of(),
                        recommendedAt
                ));
    }

    @Test
    @DisplayName("Debe rechazar una justificación vacía o compuesta por espacios")
    void shouldRejectBlankRationale() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DecisionRecommendation(
                                DecisionAction.CONTINUE_PLAN,
                                "",
                                List.of(),
                                recommendedAt
                        )),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DecisionRecommendation(
                                DecisionAction.CONTINUE_PLAN,
                                "   ",
                                List.of(),
                                recommendedAt
                        ))
        );
    }

    @Test
    @DisplayName("Debe rechazar una lista de evidencias nula")
    void shouldRejectNullSupportingEvidenceIds() {
        assertThrows(NullPointerException.class,
                () -> new DecisionRecommendation(
                        DecisionAction.CONTINUE_PLAN,
                        "Justificación válida",
                        null,
                        recommendedAt
                ));
    }

    @Test
    @DisplayName("Debe rechazar identificadores nulos dentro de la lista")
    void shouldRejectNullEvidenceIdElements() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionRecommendation(
                        DecisionAction.CONTINUE_PLAN,
                        "Justificación válida",
                        Arrays.asList(UUID.randomUUID(), null),
                        recommendedAt
                ));
    }

    @Test
    @DisplayName("Debe permitir una lista de evidencias vacía")
    void shouldAllowEmptySupportingEvidenceIds() {
        DecisionRecommendation recommendation =
                new DecisionRecommendation(
                        DecisionAction.REQUEST_HUMAN_REVIEW,
                        "Se requiere revisión humana",
                        List.of(),
                        recommendedAt
                );

        assertTrue(recommendation.supportingEvidenceIds().isEmpty());
    }

    @Test
    @DisplayName("Debe rechazar una fecha de recomendación nula")
    void shouldRejectNullRecommendedAt() {
        assertThrows(NullPointerException.class,
                () -> new DecisionRecommendation(
                        DecisionAction.CONTINUE_PLAN,
                        "Justificación válida",
                        List.of(),
                        null
                ));
    }

    @Test
    @DisplayName("Debe normalizar la justificación eliminando espacios externos")
    void shouldTrimRationale() {
        DecisionRecommendation recommendation =
                createValidRecommendation();

        assertEquals(
                "Continuar el plan por sus resultados favorables",
                recommendation.rationale()
        );
    }

    @Test
    @DisplayName("Debe devolver una lista de evidencias inmutable")
    void shouldReturnImmutableSupportingEvidenceIds() {
        DecisionRecommendation recommendation =
                createValidRecommendation();

        assertThrows(UnsupportedOperationException.class,
                () -> recommendation.supportingEvidenceIds()
                        .add(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Debe conservar la acción recomendada")
    void shouldPreserveRecommendedAction() {
        DecisionRecommendation recommendation =
                new DecisionRecommendation(
                        DecisionAction.REQUEST_HUMAN_REVIEW,
                        "La intervención requiere supervisión",
                        List.of(),
                        recommendedAt
                );

        assertEquals(DecisionAction.REQUEST_HUMAN_REVIEW,
                recommendation.action());
    }

    @Test
    @DisplayName("Debe conservar los identificadores de las evidencias")
    void shouldPreserveSupportingEvidenceIds() {
        UUID evidenceId1 = UUID.randomUUID();
        UUID evidenceId2 = UUID.randomUUID();

        DecisionRecommendation recommendation =
                new DecisionRecommendation(
                        DecisionAction.ADJUST_PLAN,
                        "Ajustar el plan según las evidencias",
                        List.of(evidenceId1, evidenceId2),
                        recommendedAt
                );

        assertEquals(
                List.of(evidenceId1, evidenceId2),
                recommendation.supportingEvidenceIds()
        );
    }
}

