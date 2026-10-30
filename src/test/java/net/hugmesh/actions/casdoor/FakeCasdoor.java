package net.hugmesh.actions.casdoor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory stand-in for ONE Casdoor organization, behind a real
 * {@link CasdoorClient} (via {@link CasdoorClientTestFactory}). It mimics the
 * casbin/casdoor:3.80.0 behaviour measured on 2026-10-30, the parts the GM
 * sync depends on:
 *
 * <ul>
 *   <li>{@code get-role} / {@code get-user}: {@code status:"ok", data:null}
 *       for a missing object (not an error).</li>
 *   <li>{@code update-role} REPLACES the stored role with the posted object
 *       (every column written: a partial body loses {@code isEnabled},
 *       {@code displayName}, {@code roles}, {@code domains}), and answers
 *       {@code data:"Affected"} even when nothing changed.</li>
 *   <li>{@code get-users?field=properties&value=v} is a substring
 *       ({@code LIKE %v%}) match over the serialized properties.</li>
 * </ul>
 *
 * Every update-role call is recorded so a test can assert which roles were
 * (never) written.
 */
public final class FakeCasdoor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String organization;
    private final Map<String, Map<String, Object>> roles = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> users = new LinkedHashMap<>();
    /** ids ({@code org/name}) of every update-role POST, in order. */
    public final List<String> roleWrites = new CopyOnWriteArrayList<>();
    /** When set, every request whose path contains this fails with a 500. */
    public volatile String failPath;
    /** When set, every request answers HTTP 200 {@code status:"error"} with this
     *  message: what Casdoor does for a rejected credential. */
    public volatile String rejectAllWith;
    /** When set, only update-role answers HTTP 200 {@code status:"error"}. */
    public volatile String rejectWritesWith;
    /** When true, update-role stores the members but leaves the role disabled. */
    public volatile boolean disableOnWrite;
    /** Every request path, in order. */
    public final List<String> requests = new CopyOnWriteArrayList<>();
    /** When true, update-role answers ok but does NOT store the change. */
    public volatile boolean dropWrites;

    public FakeCasdoor(String organization) { this.organization = organization; }

    public CasdoorClient client() {
        return CasdoorClientTestFactory.withExchange(this::exchange, organization);
    }

    public FakeCasdoor role(String name, boolean enabled, String... members) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("owner", organization);
        r.put("name", name);
        r.put("displayName", "DN-" + name);
        r.put("description", "seeded");
        r.put("users", new ArrayList<>(List.of(members)));
        r.put("roles", new ArrayList<>());
        r.put("domains", new ArrayList<>(List.of("default")));
        r.put("isEnabled", enabled);
        roles.put(name, r);
        return this;
    }

    public FakeCasdoor user(String name, String id, Map<String, String> properties) {
        return user(name, id, properties, false);
    }

    public FakeCasdoor user(String name, String id, Map<String, String> properties, boolean forbidden) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("owner", organization);
        u.put("name", name);
        u.put("id", id);
        u.put("isForbidden", forbidden);
        u.put("isDeleted", false);
        u.put("properties", properties == null ? null : new LinkedHashMap<>(properties));
        users.put(name, u);
        return this;
    }

    @SuppressWarnings("unchecked")
    public List<String> members(String role) {
        Map<String, Object> r = roles.get(role);
        return r == null ? null : (List<String>) r.get("users");
    }

    public Map<String, Object> roleObject(String role) { return roles.get(role); }

    private Mono<ClientResponse> exchange(ClientRequest req) {
        String path = req.url().getPath();
        requests.add(path);
        if (failPath != null && path.contains(failPath)) {
            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
        }
        String rejection = rejectAllWith != null ? rejectAllWith
                : "/api/update-role".equals(path) ? rejectWritesWith : null;
        if (rejection != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("msg", rejection);
            err.put("data", null);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(json(err))
                    .build());
        }
        Map<String, String> q = UriComponentsBuilder.fromUri(req.url()).build().getQueryParams().toSingleValueMap();
        switch (path) {
            case "/api/get-role":
                return ok(roles.get(nameOf(q.get("id"))));
            case "/api/get-user":
                return ok(users.get(nameOf(q.get("id"))));
            case "/api/get-users": {
                String value = q.get("value");
                List<Object> out = new ArrayList<>();
                for (Map<String, Object> u : users.values()) {
                    if (value == null || String.valueOf(json(u.get("properties"))).contains(value)) {
                        out.add(u);
                    }
                }
                return ok(out);
            }
            case "/api/update-role":
                return bodyOf(req).flatMap(body -> {
                    String id = q.get("id");
                    roleWrites.add(id);
                    if (!dropWrites) {
                        Map<String, Object> stored = new LinkedHashMap<>();
                        // AllCols(): every column comes from the posted object.
                        stored.put("owner", body.get("owner"));
                        stored.put("name", body.get("name"));
                        stored.put("displayName", body.getOrDefault("displayName", ""));
                        stored.put("description", body.getOrDefault("description", ""));
                        stored.put("users", body.get("users"));
                        stored.put("roles", body.get("roles"));
                        stored.put("domains", body.get("domains"));
                        stored.put("isEnabled", !disableOnWrite && Boolean.TRUE.equals(body.get("isEnabled")));
                        roles.put(nameOf(id), stored);
                    }
                    return ok("Affected");
                });
            default:
                return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
        }
    }

    private static String nameOf(String id) {
        return id == null ? null : id.substring(id.indexOf('/') + 1);
    }

    @SuppressWarnings("unchecked")
    private static Mono<Map<String, Object>> bodyOf(ClientRequest req) {
        CapturingOutputMessage msg = new CapturingOutputMessage();
        return req.body().insert(msg, new BodyInserter.Context() {
                    @Override public List<HttpMessageWriter<?>> messageWriters() {
                        return ExchangeStrategies.withDefaults().messageWriters();
                    }
                    @Override public Optional<ServerHttpRequest> serverRequest() {
                        return Optional.empty();
                    }
                    @Override public Map<String, Object> hints() { return Map.of(); }
                })
                .then(Mono.fromCallable(() -> (Map<String, Object>) JSON.readValue(msg.bodyText(), Map.class)));
    }

    private static String json(Object o) {
        try { return JSON.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Mono<ClientResponse> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("msg", "");
        body.put("data", data);
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(json(body))
                .build());
    }

    /** Minimal ClientHttpRequest that captures the written body bytes. */
    private static final class CapturingOutputMessage
            extends MockClientHttpRequest {
        CapturingOutputMessage() { super(HttpMethod.POST, "/"); }
        String bodyText() { return getBodyAsString().block(); }
    }

}
