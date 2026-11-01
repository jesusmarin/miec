package com.miec.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias del contrato {@link EvidenceValidationRequest}.
 *
 * <p>Verifican las invariantes de construcción, la normalización de datos,
 * la inmutabilidad de la lista de evidencias y el tratamiento de la ausencia
 * de evidencia como entrada válida (F02 §3.7).
 */
@DisplayName("Pruebas de la solicitud de validación de evidencia MIEC")
class EvidenceValidationRequestTest {

    private static final String CRITERIA_VERSION = "criteria-0.1.0";

    private final UUID cycleId = UUID.randomUUID();
    private final UUID objectiveId = UUID.randomUUID();
    private final Instant requestedAt = Instant.now();

    /**
     * Construye una solicitud válida con las evidencias indicadas.
     *
     * @param evidenceIds identificadores a incluir
     * @return solicitud válida utilizada como base de las pruebas
     */
    private EvidenceValidationRequest requestWith(List<UUID> evidenceIds) {
        return new EvidenceValidationRequest(
                cycleId, objectiveId, evidenceIds, CRITERIA_VERSION, requestedAt);
    }

    @Test
    @DisplayName("Debe crear una solicitud válida conservando todos sus datos")
    void shouldCreateValidRequest() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        EvidenceValidationRequest request = requestWith(List.of(first, second));

        assertThat(request.cycleId()).isEqualTo(cycleId);
        assertThat(request.objectiveId()).isEqualTo(objectiveId);
        assertThat(request.evidenceIds()).containsExactly(first, second);
        assertThat(request.criteriaVersionId()).isEqualTo(CRITERIA_VERSION);
        assertThat(request.requestedAt()).isEqualTo(requestedAt);
    }

    @Test
    @DisplayName("Debe aceptar una solicitud sin evidencias como entrada válida")
    void shouldAcceptRequestWithoutEvidence() {
        EvidenceValidationRequest request = requestWith(List.of());

        assertThat(request.evidenceIds()).isEmpty();
        assertThat(request.hasNoEvidence()).isTrue();
        assertThat(request.evidenceCount()).isZero();
    }

    @Test
    @DisplayName("Debe reportar presencia de evidencia cuando se aporta al menos una")
    void shouldReportEvidencePresence() {
        EvidenceValidationRequest request = requestWith(List.of(UUID.randomUUID()));

        assertThat(request.hasNoEvidence()).isFalse();
        assertThat(request.evidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin identificador de ciclo")
    void shouldRejectNullCycleId() {
        assertThatThrownBy(() -> new EvidenceValidationRequest(
                null, objectiveId, List.of(), CRITERIA_VERSION, requestedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ciclo");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin identificador de objetivo")
    void shouldRejectNullObjectiveId() {
        assertThatThrownBy(() -> new EvidenceValidationRequest(
                cycleId, null, List.of(), CRITERIA_VERSION, requestedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("objetivo");
    }

    @Test
    @DisplayName("Debe rechazar una lista de evidencias nula e indicar el uso de lista vacía")
    void shouldRejectNullEvidenceList() {
        assertThatThrownBy(() -> requestWith(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lista vacía");
    }

    @Test
    @DisplayName("Debe rechazar una lista de evidencias con elementos nulos")
    void shouldRejectNullEvidenceElement() {
        List<UUID> withNull = Arrays.asList(UUID.randomUUID(), null);

        assertThatThrownBy(() -> requestWith(withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elementos nulos");
    }

    @Test
    @DisplayName("Debe rechazar identificadores de evidencia duplicados")
    void shouldRejectDuplicateEvidenceIds() {
        UUID repeated = UUID.randomUUID();

        assertThatThrownBy(() -> requestWith(List.of(repeated, repeated)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicado");
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin versión de criterios")
    void shouldRejectNullCriteriaVersion() {
        assertThatThrownBy(() -> new EvidenceValidationRequest(
                cycleId, objectiveId, List.of(), null, requestedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("versión de criterios");
    }

    @Test
    @DisplayName("Debe rechazar una versión de criterios en blanco")
    void shouldRejectBlankCriteriaVersion() {
        assertThatThrownBy(() -> new EvidenceValidationRequest(
                cycleId, objectiveId, List.of(), "   ", requestedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versión de criterios");
    }

    @Test
    @DisplayName("Debe normalizar la versión de criterios eliminando espacios sobrantes")
    void shouldTrimCriteriaVersion() {
        EvidenceValidationRequest request = new EvidenceValidationRequest(
                cycleId, objectiveId, List.of(),
                "  " + CRITERIA_VERSION + "  ", requestedAt);

        assertThat(request.criteriaVersionId()).isEqualTo(CRITERIA_VERSION);
    }

    @Test
    @DisplayName("Debe rechazar una solicitud sin instante de solicitud")
    void shouldRejectNullRequestedAt() {
        assertThatThrownBy(() -> new EvidenceValidationRequest(
                cycleId, objectiveId, List.of(), CRITERIA_VERSION, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("instante de solicitud");
    }

    @Test
    @DisplayName("Debe conservar el orden de las evidencias recibidas")
    void shouldPreserveEvidenceOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        EvidenceValidationRequest request = requestWith(List.of(third, first, second));

        assertThat(request.evidenceIds()).containsExactly(third, first, second);
    }

    @Test
    @DisplayName("Debe aislar la solicitud de modificaciones posteriores a la lista original")
    void shouldDefensivelyCopyEvidenceList() {
        List<UUID> mutable = new ArrayList<>();
        UUID original = UUID.randomUUID();
        mutable.add(original);

        EvidenceValidationRequest request = requestWith(mutable);
        mutable.add(UUID.randomUUID());

        assertThat(request.evidenceIds()).containsExactly(original);
        assertThat(request.evidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Debe exponer una lista de evidencias inmutable")
    void shouldExposeImmutableEvidenceList() {
        EvidenceValidationRequest request = requestWith(List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> request.evidenceIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}