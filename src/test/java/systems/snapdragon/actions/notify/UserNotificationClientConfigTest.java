package systems.snapdragon.actions.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link UserNotificationClientConfig}: the bean is GATED config-on
 * (dark by default) so dev/test/CI leave it absent and
 * {@link RoleChangeNotifier} degrades to a logged no-op — see
 * {@link RoleChangeNotifierTest} for that behaviour.
 */
class UserNotificationClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(UserNotificationClientConfig.class)
            .withPropertyValues(
                    "app.casdoor.systems.base-url=http://casdoor:8000",
                    "app.casdoor.systems.client-id=snapdragon-actions",
                    "app.casdoor.systems.client-secret=secret");

    @Test
    void beanIsAbsentByDefault_shipsDark() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(UserNotificationClient.class));
    }

    @Test
    void beanIsPresentWhenGateOn() {
        runner.withPropertyValues("app.actions.notify.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(UserNotificationClient.class));
    }
}
