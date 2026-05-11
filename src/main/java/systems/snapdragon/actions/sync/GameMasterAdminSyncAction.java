package systems.snapdragon.actions.sync;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import systems.snapdragon.actions.casdoor.CasdoorClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Propagates the {@code game-master} role from snapdragon.systems
 * Casdoor into the {@code admin} role on snapdragon.games Casdoor.
 *
 * <p>Tick (every {@code app.actions.game-master-sync.interval}):
 *
 * <ol>
 *   <li>Read snapdragon.systems' {@code game-master} role membership.</li>
 *   <li>Read snapdragon.games' {@code admin} role membership.</li>
 *   <li>Compute the desired games-admin set by mapping each systems
 *       user to its games-side identifier (same username, different
 *       organization — Casdoor stores members as
 *       {@code <org>/<username>} so the mapping is a string swap).</li>
 *   <li>If the desired set != current set, POST the new membership
 *       to snapdragon.games Casdoor.</li>
 *   <li>Emit per-action metrics: {@code actions.gm_sync.added /
 *       removed / unchanged / errors}.</li>
 * </ol>
 *
 * <p>Idempotent — running the action twice in a row with no upstream
 * change is a no-op. Stateless — the only state is on the two
 * Casdoor servers; this service never accumulates anything beyond
 * the metric counters.
 *
 * <p>Resilience4j circuit breaker on the per-call effect — a stalled
 * Casdoor opens the breaker, the action skips that side, and the
 * next tick retries when the breaker half-opens. Default policy is
 * the standard {@code @CircuitBreaker} defaults; tune via
 * {@code resilience4j.circuitbreaker.instances.casdoor.*}.
 */
@Service
public class GameMasterAdminSyncAction {

    private static final Logger log = LoggerFactory.getLogger(GameMasterAdminSyncAction.class);

    private final CasdoorClient systems;
    private final CasdoorClient games;
    private final String systemsRoleName;
    private final String gamesRoleName;
    private final String systemsOrg;
    private final String gamesOrg;

    private final Counter added;
    private final Counter removed;
    private final Counter unchanged;
    private final Counter errors;
    private final AtomicReference<Instant> lastSuccessAt =
            new AtomicReference<>(Instant.EPOCH);

    public GameMasterAdminSyncAction(
            CasdoorClient systemsClient,
            CasdoorClient gamesClient,
            @Value("${app.actions.game-master-sync.systems-role:game-master}") String systemsRoleName,
            @Value("${app.actions.game-master-sync.games-role:admin}") String gamesRoleName,
            MeterRegistry registry) {
        this.systems = systemsClient;
        this.games   = gamesClient;
        this.systemsRoleName = systemsRoleName;
        this.gamesRoleName   = gamesRoleName;
        this.systemsOrg = systemsClient.organization();
        this.gamesOrg   = gamesClient.organization();
        this.added     = Counter.builder("actions.gm_sync.added")
                .description("Users added to snapdragon.games admin during sync")
                .register(registry);
        this.removed   = Counter.builder("actions.gm_sync.removed")
                .description("Users removed from snapdragon.games admin during sync")
                .register(registry);
        this.unchanged = Counter.builder("actions.gm_sync.unchanged")
                .description("Sync ticks where membership matched (no write)")
                .register(registry);
        this.errors    = Counter.builder("actions.gm_sync.errors")
                .description("Sync ticks that failed to read or write Casdoor")
                .register(registry);
    }

    @PostConstruct
    void start() {
        log.info("[gm-sync] configured: systems={} games={} systemsRole={} gamesRole={}",
                systems.label(), games.label(), systemsRoleName, gamesRoleName);
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
        return Mono.zip(
                systems.getRoleMembers(systemsRoleName),
                games.getRoleMembers(gamesRoleName))
            .flatMap(t -> reconcile(t.getT1(), t.getT2()));
    }

    /**
     * Compute the diff between current games-admin membership and
     * the desired set (systems-game-masters mapped to the games
     * Casdoor organization), then POST the new list to games
     * Casdoor only when it actually changes.
     */
    Mono<Void> reconcile(List<String> systemsMembers, List<String> gamesMembers) {
        Set<String> desired = new HashSet<>();
        for (String m : systemsMembers) {
            desired.add(swapOrganization(m, systemsOrg, gamesOrg));
        }
        Set<String> current = new HashSet<>(gamesMembers);
        Set<String> toAdd    = diff(desired, current);
        Set<String> toRemove = diff(current, desired);

        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            unchanged.increment();
            log.debug("[gm-sync] in-sync: {} members", current.size());
            return Mono.empty();
        }

        log.info("[gm-sync] reconciling: +{} -{} (systems={}, games-before={})",
                toAdd.size(), toRemove.size(), systemsMembers.size(), gamesMembers.size());

        return games.setRoleMembers(gamesRoleName, List.copyOf(desired))
            .doOnNext(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    added.increment(toAdd.size());
                    removed.increment(toRemove.size());
                }
            })
            .then();
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }

    /**
     * Rewrite the organization prefix of a Casdoor member identifier.
     * Casdoor stores role members as {@code <org>/<username>}; the
     * sync maps users between organizations of equal name.
     */
    static String swapOrganization(String member, String fromOrg, String toOrg) {
        if (member == null) return null;
        String fromPrefix = fromOrg + "/";
        if (member.startsWith(fromPrefix)) {
            return toOrg + "/" + member.substring(fromPrefix.length());
        }
        return toOrg + "/" + member;  // bare username → prepend target org
    }
}
