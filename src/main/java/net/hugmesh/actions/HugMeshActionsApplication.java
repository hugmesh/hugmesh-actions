package net.hugmesh.actions;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HugMesh Actions — a platform-wide service that runs scheduled
 * cross-stack reconciliation jobs.
 *
 * <p>Current actions:
 *
 * <ul>
 *   <li>{@code GameMasterAdminSyncAction} — propagates the
 *       {@code game-master} role from hugmesh.systems Casdoor
 *       into the admin role on hugmesh.games Casdoor. Runs every
 *       {@code app.actions.game-master-sync.interval} (default 60 s).</li>
 *   <li>{@code GitHubOrgAdminGrantAction} — auto-grants
 *       hugmesh.systems admin to users with admin reach on a
 *       configured HugMesh GitHub org (on-demand, from the
 *       registration flow).</li>
 *   <li>{@code GitHubOrgAdminReconcileAction} — scheduled revoke loop
 *       that strips hugmesh.systems admin from users whose GitHub
 *       org-admin reach has since been removed. Runs every
 *       {@code app.actions.gh-org-admin.reconcile.interval-ms}
 *       (default 6 h).</li>
 *   <li>{@code OidcDefaultRoleProbeAction} — DEFAULT-OFF scheduled probe
 *       that applies each new Casdoor user's OIDC-provider default roles
 *       via admin-bff's {@code POST /bff/internal/oidc/first-login} hook.
 *       Runs every {@code app.actions.oidc-default-roles.interval-ms}
 *       (default 5 min).</li>
 * </ul>
 *
 * <p>The service is INTENTIONALLY stateless — every tick reads source-
 * of-truth membership from hugmesh.systems Casdoor and reconciles
 * the hugmesh.games Casdoor against it. No local DB; no event
 * sourcing. A missed tick is corrected on the next one.
 *
 * <p>Designed to run in any container with network access to the
 * two Casdoor instances. The compose stack in this repo wires it
 * against a dev pair of Casdoor servers; production runs in the
 * hugmesh.systems cluster with prod URLs / client credentials.
 */
@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(
        info = @Info(
                title = "HugMesh Actions API",
                version = "0.0.1",
                description = "Platform-wide scheduled-actions + registration API "
                        + "(cross-stack role sync, GitHub-org admin grant). The "
                        + "/v3/api-docs spec is the source for the platform's "
                        + "ng-openapi-gen typed-clients + the admin api-docs module."))
public class HugMeshActionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HugMeshActionsApplication.class, args);
    }
}
