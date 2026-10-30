package net.hugmesh.actions.casdoor;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G14: {@link CasdoorClient#setRoleMembers} is a read-modify-write, because
 * Casdoor's update-role writes every column of the posted object. Runs against
 * {@link FakeCasdoor}, which has that measured 3.80.0 behaviour.
 */
class CasdoorClientSetRoleMembersTest {

    private static final String ORG = "threewizards";

    @Test
    void replacesOnlyUsers_andKeepsEveryOtherColumn() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG).role("mesh-x", true, ORG + "/old");

        StepVerifier.create(casdoor.client().setRoleMembers("mesh-x", List.of(ORG + "/new")))
                .expectNext(true).verifyComplete();

        Map<String, Object> role = casdoor.roleObject("mesh-x");
        assertThat(role.get("users")).isEqualTo(List.of(ORG + "/new"));
        assertThat(role.get("isEnabled")).isEqualTo(true);
        assertThat(role.get("displayName")).isEqualTo("DN-mesh-x");
        assertThat(role.get("description")).isEqualTo("seeded");
        assertThat(role.get("roles")).isEqualTo(List.of());
        assertThat(role.get("domains")).isEqualTo(List.of("default"));
    }

    @Test
    void missingRole_isAnError_andPostsNothing() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG);

        StepVerifier.create(casdoor.client().setRoleMembers("mesh-x", List.of()))
                .expectError(IllegalStateException.class).verify();

        assertThat(casdoor.roleWrites).isEmpty();
    }

    @Test
    void disabledRole_isNotWritten() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG).role("mesh-x", false, ORG + "/old");

        StepVerifier.create(casdoor.client().setRoleMembers("mesh-x", List.of(ORG + "/new")))
                .expectNext(false).verifyComplete();

        assertThat(casdoor.roleWrites).isEmpty();
        assertThat(casdoor.members("mesh-x")).containsExactly(ORG + "/old");
    }

    @Test
    void okResponseThatDidNotTakeEffect_isNotConfirmed() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG).role("mesh-x", true, ORG + "/old");
        casdoor.dropWrites = true;

        StepVerifier.create(casdoor.client().setRoleMembers("mesh-x", List.of(ORG + "/new")))
                .expectNext(false).verifyComplete();
    }

    @Test
    void rejectedWrite_isNotConfirmed() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG).role("mesh-x", true, ORG + "/old");
        casdoor.rejectWritesWith = "Unauthorized operation";

        StepVerifier.create(casdoor.client().setRoleMembers("mesh-x", List.of(ORG + "/old")))
                .expectNext(false).verifyComplete();
    }

    @Test
    void writeThatLeavesTheRoleDisabled_isNotConfirmed() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG).role("mesh-x", true, ORG + "/old");
        casdoor.disableOnWrite = true;

        StepVerifier.create(casdoor.client().setRoleMembers("mesh-x", List.of(ORG + "/new")))
                .expectNext(false).verifyComplete();
    }

    @Test
    void rejectedRead_isAnError_notAnEmptyList() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG).role("mesh-x", true, ORG + "/old");
        casdoor.rejectAllWith = "Unauthorized operation";

        StepVerifier.create(casdoor.client().getRoleMembers("mesh-x"))
                .expectError(IllegalStateException.class).verify();
        StepVerifier.create(casdoor.client().getUser("alice"))
                .expectError(IllegalStateException.class).verify();
        StepVerifier.create(casdoor.client().findUsersByProperty("meshSub", "s"))
                .expectError(IllegalStateException.class).verify();
    }

    @Test
    void missingRole_readsAsNoMembers() {
        StepVerifier.create(new FakeCasdoor(ORG).client().getRoleMembers("mesh-x"))
                .expectNext(List.of()).verifyComplete();
    }

    @Test
    void getUser_readsIdFlagsAndProperties_andMissingIsEmpty() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG)
                .user("alice", "id-a", Map.of("meshSub", "s1"))
                .user("banned", "id-b", null, true);

        StepVerifier.create(casdoor.client().getUser("alice"))
                .assertNext(u -> {
                    assertThat(u.id()).isEqualTo("id-a");
                    assertThat(u.usable()).isTrue();
                    assertThat(u.properties()).containsEntry("meshSub", "s1");
                }).verifyComplete();
        StepVerifier.create(casdoor.client().getUser("banned"))
                .assertNext(u -> assertThat(u.usable()).isFalse()).verifyComplete();
        StepVerifier.create(casdoor.client().getUser("ghost")).verifyComplete();
    }

    @Test
    void findUsersByProperty_isExact_notTheServersSubstringMatch() {
        FakeCasdoor casdoor = new FakeCasdoor(ORG)
                .user("exact", "1", Map.of("meshSub", "sub"))
                .user("longer", "2", Map.of("meshSub", "sub-2"))
                .user("otherkey", "3", Map.of("note", "sub"))
                .user("noprops", "4", null);

        StepVerifier.create(casdoor.client().findUsersByProperty("meshSub", "sub"))
                .assertNext(list -> assertThat(list).extracting(CasdoorClient.User::name)
                        .containsExactly("exact"))
                .verifyComplete();
    }
}
