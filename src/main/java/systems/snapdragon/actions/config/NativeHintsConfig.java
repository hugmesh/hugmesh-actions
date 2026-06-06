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
 * </ul>
 *
 * <p>Best-effort: this is the minimal set the source obviously needs. A
 * full {@code -Pnative native:compile} in a GraalVM-equipped environment
 * is the authoritative check; the {@code add-reachability-metadata} goal
 * (contributed by the parent's {@code native} profile) supplies the
 * library-side metadata for the WebClient/Netty/Jackson/resilience4j
 * jars on top of these app-specific hints.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeHintsConfig.Hints.class)
public class NativeHintsConfig {

    static class Hints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // --- JSON-bound DTOs crossing the HTTP boundary -----------
            hints.reflection()
                    .registerType(RegistrationController.GrantRequest.class, dtoCategories())
                    .registerType(RegistrationController.GrantResponse.class, dtoCategories())
                    .registerType(GitHubClient.User.class, dtoCategories())
                    .registerType(GitHubClient.OrgMembership.class, dtoCategories())
                    .registerType(GitHubClient.RepoPermission.class, dtoCategories());

            // --- Resilience4j @CircuitBreaker advised beans -----------
            // Keep the advised methods + their AOT proxy reachable.
            hints.reflection()
                    .registerType(GameMasterAdminSyncAction.class, proxyTargetCategories())
                    .registerType(GitHubOrgAdminGrantAction.class, proxyTargetCategories())
                    .registerType(GitHubOrgAdminReconcileAction.class, proxyTargetCategories());
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
