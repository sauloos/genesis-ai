package ai.genesisbrands.standing;

import ai.genesisbrands.platform.AgentCustomOption;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks option values against an agent's declared options. Pure. Every value is a string
 * (see {@link AgentCustomOption}); unknown keys are dropped, never stored.
 */
final class OptionValues {

    static final int MAX_TEXT = 4000;

    private OptionValues() {}

    /**
     * The effective options: each declared option's default, overlaid by {@code adminDefaults},
     * then by {@code clientValues} for every key the admin has not locked.
     *
     * @throws IllegalArgumentException naming the option, for a value the option does not allow
     */
    static Map<String, String> resolve(List<AgentCustomOption> declared, Map<String, String> adminDefaults,
                                       Set<String> locked, Map<String, String> clientValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AgentCustomOption option : declared) {
            String value = option.defaultValue();
            if (adminDefaults != null && adminDefaults.containsKey(option.key())) value = adminDefaults.get(option.key());
            if (clientValues != null && clientValues.containsKey(option.key()) && !locked.contains(option.key())) {
                value = clientValues.get(option.key());
            }
            out.put(option.key(), normalize(option, value));
        }
        return out;
    }

    static String normalize(AgentCustomOption option, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        List<String> allowed = option.allowedValues() == null ? List.of() : option.allowedValues();
        return switch (option.type()) {
            case BOOLEAN -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw invalid(option, "must be true or false");
                yield value.toLowerCase();
            }
            case SELECT -> {
                if (!allowed.isEmpty() && !allowed.contains(value)) throw invalid(option, "must be one of " + allowed);
                yield value;
            }
            case MULTI_SELECT -> {
                List<String> picked = Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
                for (String v : picked) {
                    if (!allowed.isEmpty() && !allowed.contains(v)) throw invalid(option, "'" + v + "' is not one of " + allowed);
                }
                // Keep the declared order, so equal selections always read the same.
                yield allowed.isEmpty() ? String.join(",", picked)
                    : allowed.stream().filter(picked::contains).collect(Collectors.joining(","));
            }
            case NUMBER -> {
                long n;
                try {
                    n = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw invalid(option, "must be a whole number");
                }
                if (allowed.size() == 2) {
                    long min = Long.parseLong(allowed.get(0));
                    long max = Long.parseLong(allowed.get(1));
                    if (n < min || n > max) throw invalid(option, "must be between " + min + " and " + max);
                }
                yield Long.toString(n);
            }
            case STRING -> {
                if (value.length() > 200) throw invalid(option, "must be 200 characters or fewer");
                yield value;
            }
            case TEXT -> {
                if (value.length() > MAX_TEXT) throw invalid(option, "must be " + MAX_TEXT + " characters or fewer");
                yield value;
            }
        };
    }

    private static IllegalArgumentException invalid(AgentCustomOption option, String why) {
        return new IllegalArgumentException(option.label() + " " + why + ".");
    }
}
