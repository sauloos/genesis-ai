package ai.genesisbrands.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleOriginTest {

    @Test
    void osLibraryModules_areCore_inBootRunAndFatJar() {
        assertThat(ModuleOrigin.isCoreLocation("file:/repo/os/runtime/build/classes/java/main/")).isTrue();
        assertThat(ModuleOrigin.isCoreLocation("file:/repo/os/agents/summarizer/build/classes/java/main/")).isTrue();
        assertThat(ModuleOrigin.isCoreLocation("jar:nested:/app/app.jar/!BOOT-INF/lib/os-runtime-plain.jar!/")).isTrue();
        assertThat(ModuleOrigin.isCoreLocation("jar:nested:/app/app.jar/!BOOT-INF/lib/os-agents-summarizer.jar!/")).isTrue();
    }

    @Test
    void appModules_areNotCore() {
        assertThat(ModuleOrigin.isCoreLocation("file:/repo/os/app/build/classes/java/main/")).isFalse();
        assertThat(ModuleOrigin.isCoreLocation("file:/repo/genesis-brands/build/classes/java/main/")).isFalse();
        assertThat(ModuleOrigin.isCoreLocation("jar:nested:/app/app.jar/!BOOT-INF/classes/!/")).isFalse();
    }
}
