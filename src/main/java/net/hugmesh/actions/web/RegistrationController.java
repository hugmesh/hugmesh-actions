package net.hugmesh.actions.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import net.hugmesh.actions.federation.FederationUsernameClient;
import net.hugmesh.actions.federation.UsernameAvailability;
import net.hugmesh.actions.registration.ReservedNames;
import net.hugmesh.actions.sync.GitHubOrgAdminGrantAction;

/**
 * HTTP surface the hugmesh.games registration remote calls when a
 * new user signs up through external GitHub OAuth. The remote
 * forwards the user's GitHub access_token + their freshly-created
 * Casdoor username; this controller evaluates the HugMesh-org
 * admin rule and either grants admin on hugmesh.net Casdoor
 * or returns 403 with the boilerplate message the SPA shows to the
 * user.
 *
 * <p>Authentication: {@code /api/registration/**} requires a valid Casdoor
 * JWT carrying {@code ROLE_ADMIN} (see {@code SecurityConfig}) — this
 * controller does NOT skip the OAuth2-resource-server gate. In production
 * the only caller that reaches this path with such a token is
 * hugmesh-be-gateway, proxying the registration remote's BFF request; an
 * external caller without an admin-scoped token is rejected by the resource
 * server before this method ever runs.
 *
 * <p><b>Reserved-username gate.</b> This is the FIRST point the platform
 * sees the username chosen during the external-OAuth signup (Casdoor mints
 * the user; the registration remote forwards us the resulting
 * {@code hugmeshUsername}). Before doing anything with it we reject —
 * {@code 422 Unprocessable Entity} — any username that, once normalised to
 * the vanity-subdomain DNS-label form, collides with a dedicated app
 * path/prefix route (see {@link ReservedNames}). This keeps a user from
 * ever owning a handle like {@code admin}/{@code api}/{@code games} whose
 * vanity surface would shadow a gateway route.
 *
 * <p><b>Casdoor MUST also enforce this.</b> Casdoor is the IdP and is where
 * the username is actually assigned during signup; a non-admin signup that
 * never triggers the org-admin evaluate call is NOT seen by this controller.
 * Configure Casdoor's organization-level forbidden/reserved usernames so the
 * IdP rejects the same labels at the source (Casdoor exposes
 * {@code organization.AccountItems} / a forbidden-username policy on the
 * signup application — keep its list in sync with {@link ReservedNames}).
 * This controller is defence-in-depth, not the sole gate.
 *
 * <p><b>Confusable / availability gate (defence-in-depth).</b> The local
 * {@link ReservedNames} check is exact-only, so it cannot catch a homoglyph /
 * look-alike of an existing username or of a reserved label ({@code 4dmin},
 * {@code akk0ma}, a Cyrillic {@code аdmin}). When the federation username-check
 * is configured on ({@code app.federation.username-check.enabled=true}), this
 * controller ALSO asks the registry owner —
 * {@link FederationUsernameClient#availability(String)} ->
 * {@code GET /api/federation/username/{candidate}/availability}, a
 * ROLE_SERVICE call — and REJECTS ({@code 422}) any candidate the registry
 * reports as colliding (exact / confusable / reserved) BEFORE the grant runs.
 * This is the GitHub-org-admin path ONLY; it is NOT the universal signup gate
 * (a normal self-signup never reaches this controller — that gate is Casdoor +
 * the federation engine on the login mint). The check fails CLOSED: a
 * federation outage rejects rather than mints a look-alike (the local exact
 * reserved fast-path still runs first regardless).
 */
@Tag(name = "Registration", description = "Internal service surface for GitHub OAuth signup: "
        + "reserved-username gate, federation confusable gate, and GitHub-org-admin evaluation. "
        + "Called by the registration remote's BFF — not a public user-facing endpoint.")
@RestController
@RequestMapping("/api/registration")
@Validated
public class RegistrationController {

    private final GitHubOrgAdminGrantAction grantAction;
    /**
     * The federation confusable/availability client — present ONLY when
     * {@code app.federation.username-check.enabled=true} (gated config-on, like
     * the other internal-service calls). {@code null} otherwise, in which case
     * the gate degrades to the local exact reserved fast-path alone.
     */
    private final ObjectProvider<FederationUsernameClient> federationUsername;

    public RegistrationController(GitHubOrgAdminGrantAction grantAction,
                                  ObjectProvider<FederationUsernameClient> federationUsername) {
        this.grantAction = grantAction;
        this.federationUsername = federationUsername;
    }

    /**
     * Evaluate-request body. Both fields are required and bounded:
     * <ul>
     *   <li>{@code githubAccessToken} — the user's freshly-minted GitHub OAuth
     *       access token the registration remote forwards. GitHub tokens are
     *       opaque ASCII (classic {@code gho_*}/{@code ghp_*} or a fine-grained
     *       {@code github_pat_*}); bound the length + charset so a malformed /
     *       oversized blob is rejected with a clean 400 before any GitHub call.</li>
     *   <li>{@code hugmeshUsername} — the Casdoor username chosen at signup.
     *       Bounded to the DNS-label charset a vanity subdomain can carry (the
     *       reserved-name gate then rejects a present-but-reserved label with
     *       422 — see {@link #rejectReservedUsername}).</li>
     * </ul>
     */
    public record GrantRequest(
            @NotBlank(message = "githubAccessToken is required")
            @Size(max = 255, message = "githubAccessToken is too long")
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = "githubAccessToken has invalid characters")
            String githubAccessToken,

            @NotBlank(message = "hugmeshUsername is required")
            @Size(max = 64, message = "hugmeshUsername is too long")
            // Allow the leading-separator forms (e.g. "_matrix", "_fragments") so the
            // reserved-name gate — not this charset guard — stays the authority on a
            // reserved label; this @Pattern only blocks whitespace/control/oversized
            // junk before the reserved check + grant action run.
            @Pattern(regexp = "[A-Za-z0-9._-]+",
                    message = "hugmeshUsername has invalid characters")
            String hugmeshUsername) {}

    public record GrantResponse(String memberId) {}

    @Operation(operationId = "evaluateGithubOrgAdmin",
            summary = "Evaluate GitHub-org-admin eligibility and conditionally grant admin on hugmesh.net Casdoor.",
            description = "Called by the registration remote BFF after a user signs up via GitHub OAuth. "
                    + "Runs in order: (1) local reserved-username gate (422 on collision), "
                    + "(2) federation confusable/availability gate if configured (422 on collision, fails closed), "
                    + "(3) GitHub-org-admin eligibility check + conditional Casdoor admin grant.<br>"
                    + "<b>Role:</b> Internal service-to-service call (gated by SecurityConfig to internal callers).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evaluated — memberId returned (granted or already a member)"),
            @ApiResponse(responseCode = "400", description = "Validation failed — missing/invalid field", content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthenticated — no valid JWT", content = @Content),
            @ApiResponse(responseCode = "403", description = "Not eligible for org admin grant", content = @Content),
            @ApiResponse(responseCode = "422", description = "Username is reserved or collides (confusable/exact) with an existing name", content = @Content),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content)
    })
    @PostMapping("/github-org-admin/evaluate")
    public Mono<GrantResponse> evaluate(@Valid @RequestBody GrantRequest req) {
        rejectReservedUsername(req.hugmeshUsername());
        return assertAvailable(req.hugmeshUsername())
                .then(Mono.defer(() ->
                        grantAction.evaluateAndGrant(req.githubAccessToken(), req.hugmeshUsername())))
                .map(GrantResponse::new);
    }

    /**
     * Ask the registry owner whether the candidate collides (exact / confusable /
     * reserved) and ERROR (422) if so, BEFORE granting; completes empty when the
     * candidate is available. A no-op completed Mono when the federation
     * username-check is not configured on (the bean is absent) — the local exact
     * reserved fast-path is then the only gate. The underlying client fails CLOSED
     * (an unreachable federation resolves to collide=true), so a brown-out rejects
     * rather than mints a look-alike.
     */
    private Mono<Void> assertAvailable(String hugmeshUsername) {
        FederationUsernameClient client = federationUsername.getIfAvailable();
        if (client == null) {
            return Mono.empty();
        }
        return client.availability(hugmeshUsername)
                .handle((a, sink) -> {
                    if (a.collide()) {
                        sink.error(collisionRejection(hugmeshUsername, a));
                    } else {
                        sink.complete();
                    }
                });
    }

    /**
     * Build the 422 for a federation-reported collision, surfacing the
     * {@code reason} (exact|confusable|reserved) and the {@code matchedWith}
     * existing handle / reserved label so the SPA can tell the user why.
     */
    private static ResponseStatusException collisionRejection(String username, UsernameAvailability a) {
        StringBuilder msg = new StringBuilder("username '").append(username)
                .append("' is unavailable");
        String reason = a.reason();
        if (reason != null && !reason.isBlank()) {
            msg.append(" (").append(reason).append(')');
        }
        if (a.matchedWith() != null && !a.matchedWith().isBlank()) {
            msg.append(": it collides with '").append(a.matchedWith()).append('\'');
        } else {
            msg.append(": it collides with an existing or reserved name");
        }
        msg.append(". Choose another username.");
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, msg.toString());
    }

    /**
     * Reject (422) a username that collides with a reserved app path/prefix
     * once normalised to the vanity-subdomain DNS-label form. A null/blank
     * username is left for the downstream grant action to handle — this check
     * only fires on a present-but-reserved label.
     */
    private static void rejectReservedUsername(String hugmeshUsername) {
        if (ReservedNames.isReserved(hugmeshUsername)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "username '" + hugmeshUsername + "' is reserved: it collides with a "
                    + "dedicated platform route/prefix (normalised label '"
                    + ReservedNames.normalize(hugmeshUsername) + "'). Choose another username.");
        }
    }
}
