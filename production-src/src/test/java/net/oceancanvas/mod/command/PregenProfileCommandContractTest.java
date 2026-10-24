package net.oceancanvas.mod.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps the release scale lane bound to an explicit, user-visible Pregen profile command. */
final class PregenProfileCommandContractTest {
    @Test
    void commandSurfaceKeepsExplicitOvernightProfileSelection() throws Exception {
        String source = Files.readString(Path.of("src/main/java/net/oceancanvas/mod/command/PregenCommand.java"));
        assertTrue(source.contains("Commands.literal(\"profile\")"), "profile command must remain registered");
        assertTrue(source.contains("\"quiet\", \"balanced\", \"overnight\", \"custom\""),
                "overnight must remain an explicit supported profile");
        assertTrue(source.contains("setPregenProfile(profile)"), "profile selection must persist through project data");
        assertTrue(source.contains("Pregen profile: \" + profile.name() + \".\""),
                "automation requires an exact observable profile confirmation");
    }
}
