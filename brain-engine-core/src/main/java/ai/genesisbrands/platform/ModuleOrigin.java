package ai.genesisbrands.platform;

import java.security.CodeSource;

/**
 * Distinguishes a class defined in brain-engine-core (the shared platform module) from
 * one defined in a tenant's own module (genesis-brands, genesis-os), by inspecting where
 * its bytecode was actually loaded from. Both in local Gradle bootRun (per-module
 * build/classes directories) and in a packaged Spring Boot fat jar (brain-engine-core
 * lands in BOOT-INF/lib/brain-engine-core-*.jar, a tenant's own classes land directly in
 * BOOT-INF/classes/), the two locations are always distinct paths, so this needs no
 * per-feature special-casing as new extension points are added.
 *
 * <p>This only distinguishes brain-engine-core from "some app's own module" — it cannot
 * tell genesis-os's own classes apart from genesis-brands' own classes, since a packaged
 * app's own classes always land in the same unmarked BOOT-INF/classes/ regardless of
 * which Gradle module built them. Callers that need to know whether the *running app
 * itself* is the platform (genesis-os) rather than a tenant must check that separately,
 * e.g. via {@code spring.application.name} — see AdminController.isPlatformApp().
 */
public final class ModuleOrigin {

    private ModuleOrigin() {}

    public static boolean isCore(Class<?> clazz) {
        try {
            CodeSource source = clazz.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return true;
            return source.getLocation().toString().contains("brain-engine-core");
        } catch (Exception e) {
            return true;
        }
    }
}
