package systems.hugmesh.actions.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reactive validation advice: turns a jakarta/Spring validation failure into a
 * clean {@code 400 Bad Request} with a structured field-error body, instead of
 * the framework default (which would surface as a 500 for a malformed body or an
 * opaque error). Two sources are mapped:
 *
 * <ul>
 *   <li>{@link WebExchangeBindException} — a {@code @Valid @RequestBody} DTO whose
 *       jakarta constraints failed (one entry per invalid field).</li>
 *   <li>{@link ConstraintViolationException} — a {@code @Validated} controller's
 *       {@code @PathVariable}/{@code @RequestParam} constraint failure.</li>
 * </ul>
 *
 * <p>The body shape is {@code {"error": "validation failed", "fields": {field: msg}}}.
 * The {@code error} key matches the platform's {@code {error}} convention the UIs
 * read; {@code fields} is additive so a typed client can surface per-field messages.
 *
 * <p>This advice deliberately does NOT handle {@link org.springframework.web.server.ResponseStatusException}
 * — the controller's 422 reserved-username gate must propagate with its own status.
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> handleBind(WebExchangeBindException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getFieldErrors()) {
            fields.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
        }
        return badRequest(fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
            // The property path is like "evaluate.req.field" / "method.arg"; keep the
            // trailing leaf so the client sees the parameter/field name.
            String leaf = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fields.putIfAbsent(leaf, v.getMessage());
        }
        return badRequest(fields);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(Map<String, String> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation failed");
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
