package ai.genesisbrands.platform;

import java.security.CodeSource;

/**
 * Distinguishes a class defined in a Genesis OS library module (os:runtime, os:agent-sdk,
 * os:agents:*) from one defined in an app's own module (genesis-brands, os:app), by
 * inspecting where its bytecode was actually loaded from. Both in local Gradle bootRun
 * (per-module os/<module>/build/classes directories) and in a packaged Spring Boot fat jar
 * (OS modules land in BOOT-INF/lib/os-runtime-*.jar, os-agents-<id>-*.jar, …; an app's own
 * classes land directly in BOOT-INF/classes/), the two locations are always distinct paths,
 * so this needs no per-feature special-casing as new extension points are added.
 *
 * <p>This only distinguishes OS library modules from "some app's own module" — it cannot
 * tell os:app's own classes apart from genesis-brands' own classes, since a packaged
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
            return isCoreLocation(source.getLocation().toString());
        } catch (Exception e) {
            return true;
        }
    }

    static boolean isCoreLocation(String location) {
        return location.matches(".*/os/(runtime|agent-sdk|agents)/.*")              // bootRun
            || location.matches(".*/os-(runtime|agent-sdk|agents-)[^/]*\\.jar.*");  // fat jar
    }
}
