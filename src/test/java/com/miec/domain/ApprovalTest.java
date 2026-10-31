
package com.miec.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Pruebas del modelo de autorización Approval")
class ApprovalTest {

    private final UUID cycleId = UUID.randomUUID();

    private Approval createValidApproval() {
        return Approval.request(
                cycleId,
                "Autorización de ejecución",
                "Solicitud para ejecutar una corrección de software",
                "MIEC_ORCHESTRATOR"
        );
    }

    @Test
    @DisplayName("Debe crear una solicitud de autorización con datos válidos")
    void shouldCreateApprovalWithValidData() {
        Approval approval = createValidApproval();

        assertNotNull(approval.getId());
        assertEquals(cycleId, approval.getCycleId());
        assertEquals("Autorización de ejecución", approval.getSubject());
        assertEquals(
                "Solicitud para ejecutar una corrección de software",
                approval.getDescription()
        );
        assertEquals("MIEC_ORCHESTRATOR", approval.getRequestedBy());
        assertEquals(ApprovalStatus.PENDING, approval.getStatus());
    }

    @Test
    @DisplayName("Debe generar identificadores únicos para cada solicitud")
    void shouldGenerateUniqueIdentifiers() {
        Approval first = createValidApproval();
        Approval second = createValidApproval();

        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    @DisplayName("Debe registrar la fecha de creación de la solicitud")
    void shouldRegisterRequestTimestamp() {
        Instant beforeCreation = Instant.now();

        Approval approval = createValidApproval();

        Instant afterCreation = Instant.now();

        assertNotNull(approval.getRequestedAt());
        assertFalse(approval.getRequestedAt().isBefore(beforeCreation));
        assertFalse(approval.getRequestedAt().isAfter(afterCreation));
        assertNull(approval.getDecidedAt());
    }

    @Test
    @DisplayName("Debe iniciar la solicitud en estado pendiente")
    void shouldStartWithPendingStatus() {
        Approval approval = createValidApproval();

        assertTrue(approval.isPending());
        assertFalse(approval.isApproved());
        assertFalse(approval.isRejected());
    }

    @Test
    @DisplayName("Debe aprobar una solicitud pendiente")
    void shouldApprovePendingRequest() {
        Approval approval = createValidApproval();

        Approval approved = approval.approve(
                "HUMAN_AUTHORIZER",
                "La ejecución cumple las condiciones establecidas."
        );

        assertTrue(approved.isApproved());
        assertEquals(ApprovalStatus.APPROVED, approved.getStatus());
        assertEquals("HUMAN_AUTHORIZER", approved.getDecidedBy());
        assertEquals(
                "La ejecución cumple las condiciones establecidas.",
                approved.getJustification()
        );
        assertNotNull(approved.getDecidedAt());
    }

    @Test
    @DisplayName("Debe rechazar una solicitud pendiente")
    void shouldRejectPendingRequest() {
        Approval approval = createValidApproval();

        Approval rejected = approval.reject(
                "HUMAN_AUTHORIZER",
                "La ejecución presenta riesgos no aceptables."
        );

        assertTrue(rejected.isRejected());
        assertEquals(ApprovalStatus.REJECTED, rejected.getStatus());
        assertEquals("HUMAN_AUTHORIZER", rejected.getDecidedBy());
        assertEquals(
                "La ejecución presenta riesgos no aceptables.",
                rejected.getJustification()
        );
        assertNotNull(rejected.getDecidedAt());
    }

    @Test
    @DisplayName("Debe conservar los datos originales al registrar una aprobación")
    void shouldPreserveOriginalDataAfterApproval() {
        Approval original = createValidApproval();

        Approval approved = original.approve(
                "HUMAN_AUTHORIZER",
                "Autorizado."
        );

        assertEquals(original.getId(), approved.getId());
        assertEquals(original.getCycleId(), approved.getCycleId());
        assertEquals(original.getSubject(), approved.getSubject());
        assertEquals(original.getDescription(), approved.getDescription());
        assertEquals(original.getRequestedBy(), approved.getRequestedBy());

        assertTrue(original.isPending());
        assertTrue(approved.isApproved());
    }

    @Test
    @DisplayName("Debe conservar los datos originales al registrar un rechazo")
    void shouldPreserveOriginalDataAfterRejection() {
        Approval original = createValidApproval();

        Approval rejected = original.reject(
                "HUMAN_AUTHORIZER",
                "No autorizado."
        );

        assertEquals(original.getId(), rejected.getId());
        assertEquals(original.getCycleId(), rejected.getCycleId());
        assertEquals(original.getSubject(), rejected.getSubject());
        assertEquals(original.getRequestedBy(), rejected.getRequestedBy());

        assertTrue(original.isPending());
        assertTrue(rejected.isRejected());
    }

    @Test
    @DisplayName("No debe permitir aprobar una solicitud que ya fue aprobada")
    void shouldRejectRepeatedApproval() {
        Approval approved = createValidApproval().approve(
                "HUMAN_AUTHORIZER",
                "Autorizado."
        );

        assertThrows(
                IllegalStateException.class,
                () -> approved.approve(
                        "ANOTHER_AUTHORIZER",
                        "Segunda aprobación."
                )
        );
    }

    @Test
    @DisplayName("No debe permitir rechazar una solicitud que ya fue rechazada")
    void shouldRejectRepeatedRejection() {
        Approval rejected = createValidApproval().reject(
                "HUMAN_AUTHORIZER",
                "No autorizado."
        );

        assertThrows(
                IllegalStateException.class,
                () -> rejected.reject(
                        "ANOTHER_AUTHORIZER",
                        "Segundo rechazo."
                )
        );
    }

    @Test
    @DisplayName("No debe permitir aprobar una solicitud previamente rechazada")
    void shouldNotApproveRejectedRequest() {
        Approval rejected = createValidApproval().reject(
                "HUMAN_AUTHORIZER",
                "No autorizado."
        );

        assertThrows(
                IllegalStateException.class,
                () -> rejected.approve(
                        "HUMAN_AUTHORIZER",
                        "Intento de aprobación."
                )
        );
    }

    @Test
    @DisplayName("No debe permitir rechazar una solicitud previamente aprobada")
    void shouldNotRejectApprovedRequest() {
        Approval approved = createValidApproval().approve(
                "HUMAN_AUTHORIZER",
                "Autorizado."
        );

        assertThrows(
                IllegalStateException.class,
                () -> approved.reject(
                        "HUMAN_AUTHORIZER",
                        "Intento de rechazo."
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar solicitudes sin identificador de ciclo")
    void shouldRejectNullCycleId() {
        assertThrows(IllegalArgumentException.class, () ->
                Approval.request(
                        null,
                        "Asunto",
                        "Descripción",
                        "SOLICITANTE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar solicitudes sin asunto")
    void shouldRejectEmptySubject() {
        assertThrows(IllegalArgumentException.class, () ->
                Approval.request(
                        cycleId,
                        " ",
                        "Descripción",
                        "SOLICITANTE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar solicitudes sin descripción")
    void shouldRejectEmptyDescription() {
        assertThrows(IllegalArgumentException.class, () ->
                Approval.request(
                        cycleId,
                        "Asunto",
                        " ",
                        "SOLICITANTE"
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar solicitudes sin responsable solicitante")
    void shouldRejectEmptyRequestedBy() {
        assertThrows(IllegalArgumentException.class, () ->
                Approval.request(
                        cycleId,
                        "Asunto",
                        "Descripción",
                        " "
                )
        );
    }

    @Test
    @DisplayName("Debe rechazar aprobaciones sin responsable de decisión")
    void shouldRejectApprovalWithoutDecisionMaker() {
        Approval approval = createValidApproval();

        assertThrows(
                IllegalArgumentException.class,
                () -> approval.approve(null, "Autorizado.")
        );
    }

    @Test
    @DisplayName("Debe rechazar aprobaciones sin justificación")
    void shouldRejectApprovalWithoutJustification() {
        Approval approval = createValidApproval();

        assertThrows(
                IllegalArgumentException.class,
                () -> approval.approve("HUMAN_AUTHORIZER", " ")
        );
    }

    @Test
    @DisplayName("Debe rechazar rechazos sin responsable de decisión")
    void shouldRejectRejectionWithoutDecisionMaker() {
        Approval approval = createValidApproval();

        assertThrows(
                IllegalArgumentException.class,
                () -> approval.reject(null, "No autorizado.")
        );
    }

    @Test
    @DisplayName("Debe rechazar rechazos sin justificación")
    void shouldRejectRejectionWithoutJustification() {
        Approval approval = createValidApproval();

        assertThrows(
                IllegalArgumentException.class,
                () -> approval.reject("HUMAN_AUTHORIZER", " ")
        );
    }

    @Test
    @DisplayName("Debe eliminar espacios externos de los campos de texto")
    void shouldTrimTextFields() {
        Approval approval = Approval.request(
                cycleId,
                " Asunto ",
                " Descripción ",
                " SOLICITANTE "
        );

        assertEquals("Asunto", approval.getSubject());
        assertEquals("Descripción", approval.getDescription());
        assertEquals("SOLICITANTE", approval.getRequestedBy());
    }

    @Test
    @DisplayName("Debe eliminar espacios externos de los datos de decisión")
    void shouldTrimDecisionFields() {
        Approval approved = createValidApproval().approve(
                " AUTORIZADOR ",
                " Aprobación justificada "
        );

        assertEquals("AUTORIZADOR", approved.getDecidedBy());
        assertEquals("Aprobación justificada", approved.getJustification());
    }
}