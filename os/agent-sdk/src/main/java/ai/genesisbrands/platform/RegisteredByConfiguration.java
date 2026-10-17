package ai.genesisbrands.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a stereotype-annotated class (typically a {@code @RestController}) that must be
 * registered only through a conditional {@code @Bean} method in its module's configuration,
 * never by component scan. Every app excludes this annotation from its scan, so a module can
 * ship such a class without the app naming it — adoption stays a Gradle dependency.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisteredByConfiguration {
}
