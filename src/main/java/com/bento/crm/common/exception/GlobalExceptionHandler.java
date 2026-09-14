package com.bento.crm.common.exception;

import com.bento.crm.common.dto.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(
            MethodArgumentNotValidException ex,
            WebRequest request) {
        List<ApiError.FieldError> fieldErrors = new ArrayList<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.add(ApiError.FieldError.builder()
                    .field(fieldName)
                    .message(errorMessage)
                    .build());
        });

        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/validation")
                .title("Validation Failed")
                .status(HttpStatus.BAD_REQUEST.value())
                .detail("Input validation failed")
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .validationErrors(fieldErrors)
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/not-found")
                .title("Resource Not Found")
                .status(HttpStatus.NOT_FOUND.value())
                .detail(ex.getMessage())
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailedException(
            AuthenticationFailedException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/unauthorized")
                .title("Authentication Failed")
                .status(HttpStatus.UNAUTHORIZED.value())
                .detail(ex.getMessage())
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(MultipleOrganizationsException.class)
    public ResponseEntity<ApiError> handleMultipleOrganizationsException(
            MultipleOrganizationsException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/multiple-organizations")
                .title("Multiple Organizations Found")
                .status(HttpStatus.CONFLICT.value())
                .detail(ex.getMessage())
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .organizations(ex.getOrganizations())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgumentException(
            IllegalArgumentException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/bad-request")
                .title("Bad Request")
                .status(HttpStatus.BAD_REQUEST.value())
                .detail(ex.getMessage())
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalStateException(
            IllegalStateException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/conflict")
                .title("Conflict")
                .status(HttpStatus.CONFLICT.value())
                .detail(ex.getMessage())
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    /**
     * A missing tenant context is a server-side wiring fault, not something the caller did wrong.
     * It subclasses {@link IllegalStateException} but must not be reported as 409 Conflict, and
     * its message must not leak to the client.
     */
    @ExceptionHandler(MissingTenantContextException.class)
    public ResponseEntity<ApiError> handleMissingTenantContext(
            MissingTenantContextException ex,
            WebRequest request) {
        log.error("Tenant context missing while handling a request", ex);
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/internal-server-error")
                .title("Internal Server Error")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .detail("An unexpected error occurred")
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Malformed request body (invalid JSON, wrong type for a field, empty body where one is
     * required). This is a client error; the raw parser message can echo request content, so it
     * is not forwarded.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableMessage(
            HttpMessageNotReadableException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/bad-request")
                .title("Bad Request")
                .status(HttpStatus.BAD_REQUEST.value())
                .detail("Request body is missing or malformed")
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    /**
     * A path or query parameter that will not convert to its target type, e.g. a non-UUID id in
     * {@code /files/{id}}. Previously fell through to the generic handler and returned 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            WebRequest request) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value";
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/bad-request")
                .title("Bad Request")
                .status(HttpStatus.BAD_REQUEST.value())
                .detail(detail)
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    /**
     * Constraint violation from the database (unique key, FK, not-null). The driver message
     * names columns and constraints and must not reach the client; report a generic 409.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException ex,
            WebRequest request) {
        log.warn("Data integrity violation", ex);
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/conflict")
                .title("Conflict")
                .status(HttpStatus.CONFLICT.value())
                .detail("The request conflicts with the current state of the resource")
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    /**
     * Upload larger than {@code spring.servlet.multipart.max-file-size}. Without this handler the
     * container exception propagates as an unhandled 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/payload-too-large")
                .title("Payload Too Large")
                .status(HttpStatus.PAYLOAD_TOO_LARGE.value())
                .detail("The uploaded file exceeds the maximum allowed size")
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDeniedException(
            AccessDeniedException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/forbidden")
                .title("Access Denied")
                .status(HttpStatus.FORBIDDEN.value())
                .detail("You do not have permission to access this resource")
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFoundException(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            WebRequest request) {
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/not-found")
                .title("Not Found")
                .status(HttpStatus.NOT_FOUND.value())
                .detail("The requested resource was not found")
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGenericException(
            Exception ex,
            WebRequest request) {
        log.error("Unhandled exception", ex);
        ApiError apiError = ApiError.builder()
                .type("https://api.example.com/errors/internal-server-error")
                .title("Internal Server Error")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                // Never echo ex.getMessage() here: it routinely carries SQL fragments, file
                // paths and internal class names. The stack trace is in the server log above.
                .detail("An unexpected error occurred")
                .instance(request.getDescription(false).replace("uri=", ""))
                .timestamp(Instant.now())
                .build();

        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
