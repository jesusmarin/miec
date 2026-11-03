package com.miec.api;

import com.miec.orchestration.LifecycleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Traduce rechazos del MVP y oculta SQL, payloads y trazas en las respuestas HTTP. */
@RestControllerAdvice(assignableTypes={LifecycleController.class,InterventionController.class,MeasurementController.class,PlanExecutionController.class,PostMeasurementController.class,EvaluationController.class,CycleCompletionController.class})
public class LifecycleApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleApiErrors.class);

    /** Error público con campos inválidos cuando la validación de entrada los identifica. */
    public record ApiError(String code, String message, Map<String, String> fields) {
        /** Captura una colección inmutable de errores. */
        public ApiError { fields = Map.copyOf(fields); }
    }

    /** No declara auditoría confirmada si falló persistirla; solo devuelve una correlación sin causas internas. */
    @ExceptionHandler(com.miec.action.ExecutionIncidentAuditException.class)
    public ResponseEntity<ApiError> executionAudit(com.miec.action.ExecutionIncidentAuditException failure) {
        return ResponseEntity.internalServerError().body(new ApiError("EXECUTION_AUDIT_UNAVAILABLE","No se pudo confirmar la auditoría de ejecución",
                Map.of("incidentId",failure.incidentId().toString())));
    }

    /** Conserva la diferencia entre solicitud inválida, inexistencia y conflicto. */
    @ExceptionHandler(LifecycleException.class)
    public ResponseEntity<ApiError> application(LifecycleException failure) {
        int status = switch (failure.kind()) {
            case BAD_REQUEST -> 400;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
        };
        return ResponseEntity.status(status).body(new ApiError(failure.code(), failure.getMessage(), Map.of()));
    }

    /** Expone nombres de campos y restricciones, sin devolver los valores rechazados. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException failure) {
        var fields = new LinkedHashMap<String, String>();
        failure.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage() == null ? "Valor inválido" : error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "La solicitud contiene campos inválidos", fields));
    }

    /** Rechaza JSON ilegible, atributos desconocidos y tipos inválidos en la ruta. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed(Exception failure) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "La solicitud no cumple el contrato de la API", Map.of()));
    }

    /** Conserva errores HTTP del framework y registra fallos internos sin exponerlos al consumidor. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception failure) {
        if (failure instanceof ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode()).body(new ApiError("HTTP_ERROR", "Solicitud HTTP no admitida", Map.of()));
        }
        LOG.error("Falló una operación de la API del ciclo MVP", failure);
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR", "No fue posible completar la operación", Map.of()));
    }
}
