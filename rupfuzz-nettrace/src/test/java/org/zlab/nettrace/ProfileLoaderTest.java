package org.zlab.nettrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.zlab.nettrace.profiles.NettraceProfile;
import org.zlab.nettrace.profiles.ProfileLoader;

class ProfileLoaderTest {
  @Test
  void parsesProfileYaml() throws Exception {
    Path tempProfile = Files.createTempFile("nettrace-profile", ".yaml");
    Files.writeString(
        tempProfile,
        "id: test-profile\n"
            + "description: test profile\n"
            + "targetPrefixes:\n"
            + "  - org.example.service\n"
            + "excludePrefixes:\n"
            + "  - org.example.generated\n"
            + "sendRules:\n"
            + "  - ownerPattern: org.example.net.Sender*\n"
            + "    methodPattern: send*\n"
            + "    descriptorPattern: '*'\n"
            + "    reason: test-send\n"
            + "recvRules:\n"
            + "  - ownerPattern: org.example.net.Receiver*\n"
            + "    methodPattern: receive*\n"
            + "    descriptorPattern: '*'\n"
            + "    reason: test-recv\n");

    NettraceProfile profile = ProfileLoader.load(tempProfile);

    assertThat(profile.id()).isEqualTo("test-profile");
    assertThat(profile.description()).isEqualTo("test profile");
    assertThat(profile.targetPrefixes()).containsExactly("org.example.service");
    assertThat(profile.excludePrefixes()).containsExactly("org.example.generated");
    assertThat(profile.sendRules()).hasSize(1);
    assertThat(profile.recvRules()).hasSize(1);
    assertThat(profile.sendRules().get(0).ownerPattern()).isEqualTo("org.example.net.Sender*");
    assertThat(profile.recvRules().get(0).methodPattern()).isEqualTo("receive*");
  }
}
