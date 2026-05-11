package systems.snapdragon.actions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Snapdragon Actions — a platform-wide service that runs scheduled
 * cross-stack reconciliation jobs.
 *
 * <p>Current actions:
 *
 * <ul>
 *   <li>{@code GameMasterAdminSyncAction} — propagates the
 *       {@code game-master} role from snapdragon.systems Casdoor
 *       into the admin role on snapdragon.games Casdoor. Runs every
 *       {@code app.actions.game-master-sync.interval} (default 60 s).</li>
 * </ul>
 *
 * <p>The service is INTENTIONALLY stateless — every tick reads source-
 * of-truth membership from snapdragon.systems Casdoor and reconciles
 * the snapdragon.games Casdoor against it. No local DB; no event
 * sourcing. A missed tick is corrected on the next one.
 *
 * <p>Designed to run in any container with network access to the
 * two Casdoor instances. The compose stack in this repo wires it
 * against a dev pair of Casdoor servers; production runs in the
 * snapdragon.systems cluster with prod URLs / client credentials.
 */
@SpringBootApplication
@EnableScheduling
public class SnapdragonActionsApplication {

	public static void main(String[] args) {
		SpringApplication.run(SnapdragonActionsApplication.class, args);
	}
}
