package systems.snapdragon.actions.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
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
 */
@RestController
@RequestMapping("/api/registration")
public class RegistrationController {

    private final GitHubOrgAdminGrantAction grantAction;

    public RegistrationController(GitHubOrgAdminGrantAction grantAction) {
        this.grantAction = grantAction;
    }

    public record GrantRequest(String githubAccessToken, String snapdragonUsername) {}
    public record GrantResponse(String memberId) {}

    @PostMapping("/github-org-admin/evaluate")
    public Mono<GrantResponse> evaluate(@RequestBody GrantRequest req) {
        return grantAction
                .evaluateAndGrant(req.githubAccessToken(), req.snapdragonUsername())
                .map(GrantResponse::new);
    }
}
