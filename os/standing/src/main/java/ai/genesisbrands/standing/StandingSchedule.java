package ai.genesisbrands.standing;

import ai.genesisbrands.platform.standing.Cadence;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * When a subscription runs next. Pure: no clock, no database. Times are wall-clock in the
 * client's own time zone, so "Monday 09:00" stays 09:00 across daylight-saving changes.
 *
 * @param dayOfWeek  1 (Monday) to 7, for weekly and fortnightly
 * @param dayOfMonth 1 to 31, clamped to the month's last day, for monthly
 */
public record StandingSchedule(Cadence cadence, int dayOfWeek, int dayOfMonth, LocalTime timeOfDay, ZoneId zone) {

    public StandingSchedule {
        if (cadence == null || timeOfDay == null || zone == null) {
            throw new IllegalArgumentException("cadence, time of day and time zone are required");
        }
        if (dayOfWeek < 1 || dayOfWeek > 7) throw new IllegalArgumentException("day of week must be 1-7");
        if (dayOfMonth < 1 || dayOfMonth > 31) throw new IllegalArgumentException("day of month must be 1-31");
    }

    /**
     * The first slot strictly after {@code after}. {@code previousSlot} is the slot that just
     * ran, or null for a new (or resumed) subscription: a fortnightly cadence counts its two
     * weeks from the previous slot, and starts at the next matching weekday when there is none.
     */
    public Instant next(Instant after, Instant previousSlot) {
        ZonedDateTime from = after.atZone(zone);
        return switch (cadence) {
            case DAILY -> firstAfter(from, from.toLocalDate(), 1);
            case WEEKLY -> nextWeekday(from);
            case FORTNIGHTLY -> {
                if (previousSlot == null) yield nextWeekday(from);
                Instant candidate = previousSlot.atZone(zone).toLocalDate().plusWeeks(2)
                    .atTime(timeOfDay).atZone(zone).toInstant();
                // A slot missed for longer than a fortnight (downtime, a long pause) catches up
                // once, on the next matching weekday, rather than firing for every missed slot.
                yield candidate.isAfter(after) ? candidate : nextWeekday(from);
            }
            case MONTHLY -> {
                LocalDate thisMonth = clampedDay(from.toLocalDate());
                ZonedDateTime slot = thisMonth.atTime(timeOfDay).atZone(zone);
                yield slot.toInstant().isAfter(after)
                    ? slot.toInstant()
                    : clampedDay(from.toLocalDate().withDayOfMonth(1).plusMonths(1)).atTime(timeOfDay).atZone(zone).toInstant();
            }
        };
    }

    private Instant nextWeekday(ZonedDateTime from) {
        LocalDate day = from.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dayOfWeek)));
        return firstAfter(from, day, 7);
    }

    /** {@code day} at the time of day, or {@code stepDays} later when that is not after {@code from}. */
    private Instant firstAfter(ZonedDateTime from, LocalDate day, int stepDays) {
        Instant slot = day.atTime(timeOfDay).atZone(zone).toInstant();
        return slot.isAfter(from.toInstant()) ? slot : day.plusDays(stepDays).atTime(timeOfDay).atZone(zone).toInstant();
    }

    private LocalDate clampedDay(LocalDate anyDayOfMonth) {
        return anyDayOfMonth.withDayOfMonth(Math.min(dayOfMonth, anyDayOfMonth.lengthOfMonth()));
    }
}
