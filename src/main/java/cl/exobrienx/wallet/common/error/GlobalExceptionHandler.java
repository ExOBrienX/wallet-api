package cl.exobrienx.wallet.common.error;

import cl.exobrienx.wallet.auth.TooManyLoginAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manejador único de errores de la API. Todas las respuestas de error usan el formato
 * {@code ProblemDetail} (RFC 9457) e incluyen siempre:
 * <ul>
 *   <li>{@code status}: el código HTTP;</li>
 *   <li>{@code code}: un código estable (ej. {@code INSUFFICIENT_FUNDS}) para que el cliente
 *       decida qué hacer sin depender del texto;</li>
 *   <li>{@code title} y {@code detail}: el motivo en español, apto para mostrar al usuario;</li>
 *   <li>{@code errors}: solo en errores de validación, los mensajes de cada campo.</li>
 * </ul>
 * Los mensajes nunca repiten el valor recibido ni exponen detalles internos (clases, trazas, SQL).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // --- errores de negocio -----------------------------------------------------------

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getTitle(), ex.getMessage());
    }

    /** Igual que una excepción de negocio, pero indicando cuándo se puede volver a intentar. */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException ex) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
                .body(handleDomain(ex));
    }

    // --- seguridad -------------------------------------------------------------------

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        if (ex instanceof InvalidBearerTokenException) {
            return problem(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "No autenticado",
                    "El token de acceso no es válido o expiró.");
        }
        return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "No autenticado",
                "Se requiere un token de acceso válido.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied() {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Acceso denegado",
                "No tienes permiso para realizar esta operación.");
    }

    /** URL rechazada por el firewall de Spring Security (secuencias usadas para evadir controles). */
    @ExceptionHandler(RequestRejectedException.class)
    ProblemDetail handleRejectedRequest(RequestRejectedException ex) {
        log.warn("Petición rechazada por el firewall: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "REQUEST_REJECTED", "Petición rechazada",
                "La URL contiene caracteres o secuencias no permitidas.");
    }

    // --- concurrencia ----------------------------------------------------------------

    /**
     * Dos operaciones modificaron la misma entidad a la vez (detectado por {@code @Version}).
     * El cliente puede volver a consultar el estado actual y reintentar.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock() {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Conflicto de concurrencia",
                "El recurso fue modificado por otra operación al mismo tiempo. Vuelve a intentarlo.");
    }

    /**
     * Conflicto de bloqueo en la base de datos que persistió después de los reintentos
     * ({@code TransactionRetry}). Es transitorio: el cliente puede reintentar con la misma
     * Idempotency-Key sin riesgo de duplicar la operación.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockFailure(PessimisticLockingFailureException ex) {
        log.warn("Conflicto de bloqueo tras agotar los reintentos: {}", ex.getMessage());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY", "Servicio ocupado",
                "El sistema está procesando muchas operaciones sobre la misma cuenta. Reintenta en unos segundos.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    /** Último recurso: nunca se devuelve el mensaje interno, solo se registra en el log. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Error no controlado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno",
                "Ocurrió un error inesperado. Si persiste, contacta al soporte.");
    }

    // --- validación ------------------------------------------------------------------

    /** Errores de {@code @Valid} en el cuerpo: responde 400 con el detalle de cada campo. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        addBindingErrors(errors, ex.getBindingResult().getFieldErrors(), ex.getBindingResult().getGlobalErrors());
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    /**
     * Errores de validación cuando el método valida varios parámetros a la vez (por ejemplo,
     * un header con {@code @NotBlank} junto a un cuerpo con {@code @Valid}). Los errores del
     * cuerpo se desglosan por campo, igual que en {@link #handleMethodArgumentNotValid}.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                addBindingErrors(errors, bodyErrors.getFieldErrors(), bodyErrors.getGlobalErrors());
            } else {
                errors.computeIfAbsent(parameterName(result), name -> new ArrayList<>())
                        .addAll(result.getResolvableErrors().stream().map(MessageSourceResolvable::getDefaultMessage).toList());
            }
        }
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    // --- errores del protocolo HTTP (los genera Spring MVC antes de llegar al controlador) ---

    /**
     * Traduce al español los errores estándar de Spring MVC, que por defecto llegan en inglés y
     * a veces repiten el valor enviado por el cliente. Cada uno recibe su código y su motivo.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail existing && existing.getProperties() != null
                && existing.getProperties().containsKey("code")) {
            return super.handleExceptionInternal(ex, body, headers, statusCode, request);
        }
        ProblemDetail problem = switch (ex) {
            case HttpMessageNotReadableException e -> problem(HttpStatus.BAD_REQUEST, "MALFORMED_BODY",
                    "Cuerpo inválido", unreadableBodyDetail(e));
            case MissingRequestHeaderException e -> problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                    "Falta un header", "Falta el header obligatorio " + e.getHeaderName() + ".");
            case MissingServletRequestParameterException e -> problem(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                    "Falta un parámetro", "Falta el parámetro obligatorio '" + e.getParameterName() + "'.");
            case TypeMismatchException e -> problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "Parámetro inválido", "El parámetro '" + parameterName(e) + "' no tiene un formato válido: "
                            + "se esperaba " + describe(e.getRequiredType()) + ".");
            case HttpRequestMethodNotSupportedException e -> problem(HttpStatus.METHOD_NOT_ALLOWED,
                    "METHOD_NOT_ALLOWED", "Método no permitido", "El método " + e.getMethod()
                            + " no está permitido en esta ruta" + allowedMethods(e) + ".");
            case HttpMediaTypeNotSupportedException e -> problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE", "Formato no soportado",
                    "El cuerpo debe enviarse como JSON, con el header Content-Type: application/json"
                            + mediaTypes(" (formatos aceptados: ", e.getSupportedMediaTypes()) + ".");
            case HttpMediaTypeNotAcceptableException e -> problem(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE",
                    "Formato de respuesta no disponible", "La API solo responde en formato JSON"
                            + mediaTypes(" (formatos disponibles: ", e.getSupportedMediaTypes()) + ".");
            case NoResourceFoundException e -> problem(HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "Ruta no encontrada", "No existe la ruta solicitada para el método " + e.getHttpMethod() + ".");
            default -> problem(HttpStatus.valueOf(statusCode.value()), HttpStatus.valueOf(statusCode.value()).name(),
                    "Petición no procesada", "La petición no se pudo procesar.");
        };
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    // --- utilidades --------------------------------------------------------------------

    static ProblemDetail problem(HttpStatusCode status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Datos inválidos",
                "La petición tiene campos inválidos. El detalle de cada uno está en 'errors'.");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Si el JSON es válido pero un campo tiene un tipo incorrecto (por ejemplo, texto donde se
     * espera un número), se indica el campo; si el JSON está mal formado o vacío, se dice eso.
     */
    private static String unreadableBodyDetail(HttpMessageNotReadableException ex) {
        if (ex.getMostSpecificCause() instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
            String field = jackson.getPath().stream()
                    .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining(".")).replace(".[", "[");
            String expected = jackson instanceof MismatchedInputException mismatch && mismatch.getTargetType() != null
                    && !"otro formato".equals(describe(mismatch.getTargetType()))
                    ? ": se esperaba " + describe(mismatch.getTargetType()) : "";
            return "El campo '" + field + "' tiene un valor con formato o tipo incorrecto" + expected + ".";
        }
        return "El cuerpo de la petición está vacío o no es un JSON válido.";
    }

    private static String parameterName(TypeMismatchException ex) {
        return ex instanceof MethodArgumentTypeMismatchException argument ? argument.getName() : ex.getPropertyName();
    }

    private static String describe(Class<?> type) {
        if (type == null) {
            return "otro formato";
        }
        if (type == UUID.class) {
            return "un identificador UUID (ej. 3fa85f64-5717-4562-b3fc-2c963f66afa6)";
        }
        if (type == Instant.class) {
            return "una fecha ISO-8601 en UTC (ej. 2026-11-01T00:00:00Z)";
        }
        if (type == LocalDate.class) {
            return "una fecha (ej. 2026-11-01)";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "un número entero, sin decimales";
        }
        if (type == String.class) {
            return "un texto";
        }
        if (type.isEnum()) {
            return "uno de estos valores: " + Arrays.stream(type.getEnumConstants())
                    .map(Object::toString).collect(Collectors.joining(", "));
        }
        return "otro formato";
    }

    /** Lista de tipos de contenido entre paréntesis, o vacío si no se conocen. */
    private static String mediaTypes(String prefix, List<MediaType> types) {
        return types == null || types.isEmpty() ? "" : prefix + types.stream()
                .map(MediaType::toString).distinct().collect(Collectors.joining(", ")) + ")";
    }

    private static String allowedMethods(HttpRequestMethodNotSupportedException ex) {
        return ex.getSupportedHttpMethods() == null || ex.getSupportedHttpMethods().isEmpty()
                ? "" : " (permitidos: " + ex.getSupportedHttpMethods().stream()
                .map(Object::toString).sorted().collect(Collectors.joining(", ")) + ")";
    }

    private static void addBindingErrors(Map<String, List<String>> errors,
                                         List<FieldError> fieldErrors, List<ObjectError> globalErrors) {
        fieldErrors.forEach(error -> errors.computeIfAbsent(error.getField(), field -> new ArrayList<>())
                .add(error.getDefaultMessage()));
        globalErrors.forEach(error -> errors.computeIfAbsent(error.getObjectName(), name -> new ArrayList<>())
                .add(error.getDefaultMessage()));
    }

    /** Para headers se informa el nombre del header (ej. "Idempotency-Key"), no el del parámetro Java. */
    private static String parameterName(ParameterValidationResult result) {
        RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        return result.getMethodParameter().getParameterName();
    }
}
