package systems.snapdragon.actions.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.registration.ReservedNames;
import systems.snapdragon.actions.sync.GitHubOrgAdminGrantAction;

/**
 * HTTP surface the snapdragon.games registration remote calls when a
 * new user signs up through external GitHub OAuth. The remote
 * forwards the user's GitHub access_token + their freshly-created
 * Casdoor username; this controller evaluates the Snapdragon-org
 * admin rule and either grants admin on snapdragon.systems Casdoor
 * or returns 403 with the boilerplate message the SPA shows to the
 * user.
 *
 * <p>Authentication: this controller is invoked from another
 * Snapdragon backend service (the registration remote's BFF) over
 * an internal network, so it doesn't gate on a JWT — the upstream
 * has already validated the user's external token. In production
 * this is fronted by snapdragon-id-gateway which restricts the path
 * to internal callers via SecurityConfig.
 *
 * <p><b>Reserved-username gate.</b> This is the FIRST point the platform
 * sees the username chosen during the external-OAuth signup (Casdoor mints
 * the user; the registration remote forwards us the resulting
 * {@code snapdragonUsername}). Before doing anything with it we reject —
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
 */
@RestController
@RequestMapping("/api/registration")
@Validated
public class RegistrationController {

    private final GitHubOrgAdminGrantAction grantAction;

    public RegistrationController(GitHubOrgAdminGrantAction grantAction) {
        this.grantAction = grantAction;
    }

    /**
     * Evaluate-request body. Both fields are required and bounded:
     * <ul>
     *   <li>{@code githubAccessToken} — the user's freshly-minted GitHub OAuth
     *       access token the registration remote forwards. GitHub tokens are
     *       opaque ASCII (classic {@code gho_*}/{@code ghp_*} or a fine-grained
     *       {@code github_pat_*}); bound the length + charset so a malformed /
     *       oversized blob is rejected with a clean 400 before any GitHub call.</li>
     *   <li>{@code snapdragonUsername} — the Casdoor username chosen at signup.
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

            @NotBlank(message = "snapdragonUsername is required")
            @Size(max = 64, message = "snapdragonUsername is too long")
            // Allow the leading-separator forms (e.g. "_matrix", "_fragments") so the
            // reserved-name gate — not this charset guard — stays the authority on a
            // reserved label; this @Pattern only blocks whitespace/control/oversized
            // junk before the reserved check + grant action run.
            @Pattern(regexp = "[A-Za-z0-9._-]+",
                    message = "snapdragonUsername has invalid characters")
            String snapdragonUsername) {}

    public record GrantResponse(String memberId) {}

    @PostMapping("/github-org-admin/evaluate")
    public Mono<GrantResponse> evaluate(@Valid @RequestBody GrantRequest req) {
        rejectReservedUsername(req.snapdragonUsername());
        return grantAction
                .evaluateAndGrant(req.githubAccessToken(), req.snapdragonUsername())
                .map(GrantResponse::new);
    }

    /**
     * Reject (422) a username that collides with a reserved app path/prefix
     * once normalised to the vanity-subdomain DNS-label form. A null/blank
     * username is left for the downstream grant action to handle — this check
     * only fires on a present-but-reserved label.
     */
    private static void rejectReservedUsername(String snapdragonUsername) {
        if (ReservedNames.isReserved(snapdragonUsername)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "username '" + snapdragonUsername + "' is reserved: it collides with a "
                    + "dedicated platform route/prefix (normalised label '"
                    + ReservedNames.normalize(snapdragonUsername) + "'). Choose another username.");
        }
    }
}
