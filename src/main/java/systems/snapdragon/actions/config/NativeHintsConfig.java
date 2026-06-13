package systems.snapdragon.actions.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import systems.snapdragon.actions.github.GitHubClient;
import systems.snapdragon.actions.sync.GameMasterAdminSyncAction;
import systems.snapdragon.actions.sync.GitHubOrgAdminGrantAction;
import systems.snapdragon.actions.sync.GitHubOrgAdminReconcileAction;
import systems.snapdragon.actions.web.RegistrationController;

/**
 * GraalVM native-image / AOT reachability hints for the bits of this
 * service the static analyzer can't see through on its own.
 *
 * <p>Spring Boot's own AOT processing already covers the framework
 * surface (the WebFlux {@link org.springframework.web.reactive.function.client.WebClient}
 * codecs, the OAuth2 resource-server JWT pipeline, scheduling
 * infrastructure, and the Jackson auto-configuration). What it cannot
 * always infer:
 *
 * <ul>
 *   <li><b>JSON-bound types</b> reached only reflectively through
 *       Jackson — the registration controller's request/response
 *       records and the {@link GitHubClient} response records. The
 *       Casdoor/GitHub WebClients deserialize into raw {@code Map} /
 *       {@code List} (already reflection-safe), but the typed DTOs that
 *       cross the HTTP boundary need explicit binding hints so their
 *       constructors/accessors survive native image.</li>
 *   <li><b>Resilience4j {@code @CircuitBreaker} targets</b> — the
 *       scheduled reconcilers and the grant action are wrapped by
 *       resilience4j's reactor operator via Spring AOP. Registering the
 *       action classes for reflection (incl. declared methods) keeps the
 *       advised methods + the AOT-generated proxy reachable.</li>
 *   <li><b>SpringDoc Swagger-UI static resources</b> — the Swagger-UI
 *       HTML / JS / CSS ship as classpath resources inside the
 *       {@code springdoc-openapi-starter-webflux-ui} jar's
 *       {@code META-INF/resources/webjars/swagger-ui/} tree.
 *       native-image strips unreferenced classpath resources by default,
 *       which 404s the UI; the include patterns below keep it reachable.
 *       The {@code /v3/api-docs} JSON spec is generated reflectively
 *       from the {@code io.swagger.v3.oas.models} types — SpringDoc
 *       ships its own {@code reachability-metadata.json} for those, and
 *       the parent's {@code add-reachability-metadata} goal pulls it in,
 *       so no per-type reflection hint is registered here for the
 *       model classes.</li>
 * </ul>
 *
 * <p>Best-effort: this is the minimal set the source obviously needs. A
 * full {@code -Pnative native:compile} in a GraalVM-equipped environment
 * is the authoritative check; the {@code add-reachability-metadata} goal
 * (contributed by the parent's {@code native} profile) supplies the
 * library-side metadata for the WebClient/Netty/Jackson/resilience4j/
 * springdoc jars on top of these app-specific hints. If the native
 * Swagger-UI still 404s after this, widen the resource pattern to the
 * full {@code META-INF/resources/webjars/.*} tree.</p>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeHintsConfig.Hints.class)
public class NativeHintsConfig {

    static class Hints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.reflection()
                    .registerType(RegistrationController.GrantRequest.class, dtoCategories())
                    .registerType(RegistrationController.GrantResponse.class, dtoCategories())
                    .registerType(GitHubClient.User.class, dtoCategories())
                    .registerType(GitHubClient.OrgMembership.class, dtoCategories())
                    .registerType(GitHubClient.RepoPermission.class, dtoCategories());

            hints.reflection()
                    .registerType(GameMasterAdminSyncAction.class, proxyTargetCategories())
                    .registerType(GitHubOrgAdminGrantAction.class, proxyTargetCategories())
                    .registerType(GitHubOrgAdminReconcileAction.class, proxyTargetCategories());

            hints.resources()
                    .registerPattern("META-INF/resources/webjars/swagger-ui/*")
                    .registerPattern("META-INF/resources/webjars/swagger-ui/**");
        }

        private static MemberCategory[] dtoCategories() {
            return new MemberCategory[] {
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS,
            };
        }

        private static MemberCategory[] proxyTargetCategories() {
            return new MemberCategory[] {
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
            };
        }
    }
}
