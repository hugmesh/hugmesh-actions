package systems.snapdragon.actions.oidc;

import java.util.List;

/**
 * Typed DTO mirroring the admin-bff response shape of
 * {@code POST /bff/internal/oidc/first-login} (its
 * {@code OidcFirstLoginController.FirstLoginResponse}). Only the
 * {@code assignedRoles} field matters to this caller; an unknown extra
 * field on the wire is tolerated by Jackson's default lenient
 * deserialization.
 *
 * @param assignedRoles the roles the admin-bff resolved + applied for the
 *                      user (may be empty when no default-role map is
 *                      configured for the provider+domain, or when
 *                      admin-bff is in dry-run with casdoor.admin.enabled=false).
 */
public record FirstLoginResponse(List<String> assignedRoles) {
}
