package net.hugmesh.actions.sync;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.actions.casdoor.CasdoorClient;
import net.hugmesh.actions.notify.RoleChangeNotifier;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mirrors the SYSTEMS games-admin role ({@code ROLE_GAMES_ADMIN} in the
 * cluster) into a games-realm role this sync OWNS OUTRIGHT
 * ({@code mesh-games-admin} by default). It never writes the games
 * realm's own {@code admin} role: that role belongs to the standalone
 * realm (its seeded admin, admins granted in the games console or via the
 * standalone portal), and the earlier design, which replaced the whole
 * {@code admin} member list every tick, removed all of them within one
 * interval.
 *
 * <p><b>Owned-role guard.</b> The configured games role must start with
 * {@value #OWNED_ROLE_PREFIX}. Anything else (in particular {@code admin},
 * {@code player}, {@code mod}, {@code publisher}, {@code uploader},
 * {@code play-*}) is refused: startup logs an ERROR, and every tick logs a
 * WARN, counts {@code actions.gm_sync.errors}, and reads and writes nothing.
 * The service still boots (it also serves the registration endpoints).
 *
 * <p><b>Identity mapping is an explicit link, never a username.</b> The
 * games realm has its own self-signup, so {@code threewizards/alice} can be
 * a different person from {@code hugmesh/alice}. Each tick:
 *
 * <ol>
 *   <li>Read the systems role members ({@code <org>/<username>}).</li>
 *   <li>Resolve each one to its systems user id (the JWT {@code sub}).
 *       Unknown, forbidden or deleted systems users are skipped.</li>
 *   <li>Find the games-realm user whose
 *       {@code properties.<link-property>} ({@code meshSub} by default)
 *       equals that id EXACTLY. Casdoor's default account items make
 *       {@code Properties} admin-modifiable only (measured on 3.80.0: a
 *       user's own update-user silently drops a properties change), so
 *       only an admin-side link flow can write it. No linked user, more
 *       than one, or a forbidden/deleted one: skip and count
 *       {@code actions.gm_sync.unlinked}. A bare name is never written,
 *       so no dangling member exists for a later sign-up to inherit.</li>
 *   <li>Write the resulting set as the owned role's whole member list,
 *       through the read-modify-write in
 *       {@link CasdoorClient#setRoleMembers}.</li>
 * </ol>
 *
 * <p>A read ERROR on either side (including Casdoor's HTTP 200
 * {@code status:"error"} for a rejected credential) fails the tick before
 * any write, so a transient failure or a rotated secret can never shrink
 * the desired set and revoke someone. A missing target role also fails the
 * tick. Idempotent: an unchanged tick is a no-op
 * ({@code actions.gm_sync.unchanged}). Stateless beyond the counters.
 *
 * <p>Resilience4j circuit breaker on the per-call effect; tune via
 * {@code resilience4j.circuitbreaker.instances.casdoor.*}.
 *
 * <p><b>Notifications.</b> After a CONFIRMED write, every added/removed
 * member gets an in-app "role changed" notification addressed to their
 * MESH subject (the linked {@code meshSub}), via {@link RoleChangeNotifier}
 * (best-effort, dark by default).
 */
@Service
public class GameMasterAdminSyncAction {

    private static final Logger log = LoggerFactory.getLogger(GameMasterAdminSyncAction.class);

    /** Bounded token identifying this reconciler as a notification source
     *  (drives {@link RoleChangeNotifier}'s idempotent row id). */
    static final String SOURCE_TABLE = "actions_gm_sync";

    /** The games role this sync writes must carry this prefix: the sync
     *  replaces its whole member list, so it may only own roles nobody
     *  else grants. */
    static final String OWNED_ROLE_PREFIX = "mesh-";

    private final CasdoorClient systems;
    private final CasdoorClient games;
    private final String systemsRoleName;
    private final String gamesRoleName;
    private final String systemsOrg;
    private final String gamesOrg;
    private final RoleChangeNotifier notifier;
    private final String gamesRoleLabel;
    private final String linkProperty;
    private final boolean gamesRoleOwned;

    private final Counter added;
    private final Counter removed;
    private final Counter unchanged;
    private final Counter errors;
    private final Counter unlinked;
    private final AtomicReference<Instant> lastSuccessAt =
            new AtomicReference<>(Instant.EPOCH);

    public GameMasterAdminSyncAction(
            @Qualifier("systemsCasdoorClient") CasdoorClient systemsClient,
            @Qualifier("gamesCasdoorClient") CasdoorClient gamesClient,
            @Value("${app.actions.game-master-sync.systems-role:game-master}") String systemsRoleName,
            @Value("${app.actions.game-master-sync.games-role:mesh-games-admin}") String gamesRoleName,
            @Value("${app.actions.game-master-sync.link-property:meshSub}") String linkProperty,
            RoleChangeNotifier notifier,
            MeterRegistry registry) {
        this.systems = systemsClient;
        this.games   = gamesClient;
        this.systemsRoleName = systemsRoleName;
        this.gamesRoleName   = gamesRoleName;
        this.systemsOrg = systemsClient.organization();
        this.gamesOrg   = gamesClient.organization();
        this.notifier   = notifier;
        this.gamesRoleLabel = gamesRoleName + " on hugmesh." + gamesClient.label();
        this.linkProperty = linkProperty;
        this.gamesRoleOwned = isOwnedRole(gamesRoleName);
        this.added     = Counter.builder("actions.gm_sync.added")
                .description("Users added to hugmesh.games admin during sync")
                .register(registry);
        this.removed   = Counter.builder("actions.gm_sync.removed")
                .description("Users removed from hugmesh.games admin during sync")
                .register(registry);
        this.unchanged = Counter.builder("actions.gm_sync.unchanged")
                .description("Sync ticks where membership matched (no write)")
                .register(registry);
        this.errors    = Counter.builder("actions.gm_sync.errors")
                .description("Sync ticks that failed to read or write Casdoor, or refused an unowned games role")
                .register(registry);
        this.unlinked  = Counter.builder("actions.gm_sync.unlinked")
                .description("Systems role members skipped because no single usable games account is linked to them")
                .register(registry);
    }

    /** True when the sync may own {@code roleName} outright. */
    static boolean isOwnedRole(String roleName) {
        return roleName != null
                && roleName.startsWith(OWNED_ROLE_PREFIX)
                && roleName.length() > OWNED_ROLE_PREFIX.length();
    }

    @PostConstruct
    void start() {
        log.info("[gm-sync] configured: systems={} games={} systemsRole={} gamesRole={} linkProperty={}",
                systems.label(), games.label(), systemsRoleName, gamesRoleName, linkProperty);
        if (!gamesRoleOwned) {
            log.error("[gm-sync] REFUSING games role '{}': the sync replaces the whole member list, so it "
                    + "only writes a role it owns (name starting '{}', e.g. mesh-games-admin). Every tick "
                    + "is a no-op until GM_SYNC_GAMES_ROLE is fixed.", gamesRoleName, OWNED_ROLE_PREFIX);
        }
    }

    /** Test hook — exposes the last successful sync timestamp for
     *  health checks / integration assertions. */
    public Instant lastSuccessAt() { return lastSuccessAt.get(); }

    @Scheduled(
        fixedDelayString = "${app.actions.game-master-sync.interval-ms:60000}",
        initialDelayString = "${app.actions.game-master-sync.initial-delay-ms:5000}")
    public void runTick() {
        log.debug("[gm-sync] tick start");
        runOnce()
            .doOnSuccess(v -> lastSuccessAt.set(Instant.now()))
            .doOnError(e -> {
                errors.increment();
                log.warn("[gm-sync] tick failed: {}", e.getMessage());
            })
            .onErrorResume(e -> Mono.empty())
            .subscribe();
    }

    @CircuitBreaker(name = "casdoor")
    Mono<Void> runOnce() {
        if (!gamesRoleOwned) {
            return Mono.error(new IllegalStateException("games role '" + gamesRoleName
                    + "' is not owned by the sync (must start with '" + OWNED_ROLE_PREFIX + "'); nothing written"));
        }
        return Mono.zip(
                systems.getRoleMembers(systemsRoleName).flatMap(this::resolveLinked),
                // A missing target role is an error, not "no members": otherwise a
                // cluster whose games realm was never seeded reads as in-sync forever.
                games.getRole(gamesRoleName)
                    .switchIfEmpty(Mono.error(new IllegalStateException("games role '" + gamesRoleName
                            + "' does not exist on the games Casdoor; seed it")))
                    .map(CasdoorClient::membersOf))
            .flatMap(t -> reconcile(t.getT1(), t.getT2()));
    }

    /**
     * Map systems role members to games member ids through the explicit
     * link. Key = games member id ({@code <gamesOrg>/<name>}), value = the
     * systems subject it is linked to. Read errors propagate (the tick
     * fails, nothing is written).
     */
    Mono<Map<String, String>> resolveLinked(List<String> systemsMembers) {
        return Flux.fromIterable(systemsMembers)
            .concatMap(member -> {
                String username = systemsUsername(member);
                if (username == null) {
                    unlinked.increment();
                    log.warn("[gm-sync] skipping systems member '{}': not in org '{}'", member, systemsOrg);
                    return Mono.<Map.Entry<String, String>>empty();
                }
                return systems.getUser(username)
                    .filter(CasdoorClient.User::usable)
                    .switchIfEmpty(Mono.fromRunnable(() -> {
                        unlinked.increment();
                        log.warn("[gm-sync] skipping systems member '{}': no usable systems account", member);
                    }))
                    .flatMap(sysUser -> games.findUsersByProperty(linkProperty, sysUser.id())
                        .flatMap(matches -> {
                            List<CasdoorClient.User> usable = matches.stream()
                                    .filter(CasdoorClient.User::usable).toList();
                            if (usable.size() != 1 || matches.size() != 1) {
                                unlinked.increment();
                                log.info("[gm-sync] skipping systems member '{}': {} games account(s) linked "
                                        + "via {} ({} usable); need exactly one", member, matches.size(),
                                        linkProperty, usable.size());
                                return Mono.<Map.Entry<String, String>>empty();
                            }
                            return Mono.just(Map.entry(gamesOrg + "/" + usable.get(0).name(), sysUser.id()));
                        }));
            })
            .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
            .map(m -> (Map<String, String>) m);
    }

    /** The username of a systems member id, or null when it names
     *  another organization. A bare name is taken as the systems org. */
    String systemsUsername(String member) {
        if (member == null || member.isBlank()) return null;
        int slash = member.indexOf('/');
        if (slash < 0) return member;
        if (!member.substring(0, slash).equals(systemsOrg)) return null;
        String name = member.substring(slash + 1);
        return name.isBlank() ? null : name;
    }

    Mono<Void> reconcile(Map<String, String> desiredLinked, List<String> gamesMembers) {
        Set<String> desired = new HashSet<>(desiredLinked.keySet());
        Set<String> current = new HashSet<>(gamesMembers);
        Set<String> toAdd    = diff(desired, current);
        Set<String> toRemove = diff(current, desired);

        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            unchanged.increment();
            log.debug("[gm-sync] in-sync: {} members", current.size());
            return Mono.empty();
        }

        log.info("[gm-sync] reconciling {}: +{} -{} (linked={}, games-before={})",
                gamesRoleName, toAdd.size(), toRemove.size(), desired.size(), gamesMembers.size());

        return games.setRoleMembers(gamesRoleName, List.copyOf(desired))
            .flatMap(ok -> {
                if (!Boolean.TRUE.equals(ok)) {
                    return Mono.error(new IllegalStateException(
                            "write of " + gamesRoleLabel + " was not confirmed"));
                }
                added.increment(toAdd.size());
                removed.increment(toRemove.size());
                return notifyChanges(toAdd, toRemove, desiredLinked);
            })
            .then();
    }

    /**
     * After a CONFIRMED write, tell every added/removed member their
     * games-admin role changed, addressed to their MESH subject. An added
     * member's subject is the link just resolved; a removed member's is
     * read from their games account's link property (skipped when it is
     * gone). Best-effort: never fails the tick.
     */
    private Mono<Void> notifyChanges(Set<String> toAdd, Set<String> toRemove,
                                     Map<String, String> linked) {
        Flux<Void> grants  = Flux.fromIterable(toAdd)
                .flatMap(member -> notifier.roleChangedForSubject(
                        linked.get(member), member, gamesRoleLabel, true, SOURCE_TABLE));
        Flux<Void> revokes = Flux.fromIterable(toRemove)
                .flatMap(member -> games.getUser(gamesUsername(member))
                        .mapNotNull(u -> u.properties().get(linkProperty))
                        .flatMap(sub -> notifier.roleChangedForSubject(
                                sub, member, gamesRoleLabel, false, SOURCE_TABLE))
                        .onErrorResume(e -> {
                            log.warn("[gm-sync] could not resolve removed member '{}' for notification: {}",
                                    member, e.toString());
                            return Mono.empty();
                        }));
        return Flux.merge(grants, revokes).then();
    }

    private static String gamesUsername(String member) {
        int slash = member.indexOf('/');
        return slash < 0 ? member : member.substring(slash + 1);
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
