package ai.genesisbrands.standing;

import ai.genesisbrands.platform.standing.Cadence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandingScheduleTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalTime NINE = LocalTime.of(9, 0);

    private static Instant london(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(LONDON).toInstant();
    }

    @Test
    void dailyIsLaterTodayOrTomorrow() {
        var s = new StandingSchedule(Cadence.DAILY, 1, 1, NINE, LONDON);
        assertEquals(london("2026-10-19T09:00"), s.next(london("2026-10-19T08:00"), null));
        assertEquals(london("2026-10-20T09:00"), s.next(london("2026-10-19T09:00"), null));
    }

    @Test
    void weeklyPicksTheNextMatchingWeekday() {
        // 2026-10-19 is a Monday.
        var s = new StandingSchedule(Cadence.WEEKLY, 3, 1, NINE, LONDON);
        assertEquals(london("2026-10-21T09:00"), s.next(london("2026-10-19T12:00"), null));
        var monday = new StandingSchedule(Cadence.WEEKLY, 1, 1, NINE, LONDON);
        assertEquals(london("2026-10-26T09:00"), monday.next(london("2026-10-19T09:00"), london("2026-10-19T09:00")));
    }

    @Test
    void fortnightlyCountsFromThePreviousSlot() {
        var s = new StandingSchedule(Cadence.FORTNIGHTLY, 1, 1, NINE, LONDON);
        assertEquals(london("2026-10-26T09:00"), s.next(london("2026-10-20T10:00"), null));
        assertEquals(london("2026-11-02T09:00"), s.next(london("2026-10-19T09:00"), london("2026-10-19T09:00")));
        // Missed by more than a fortnight: catch up once at the next Monday.
        assertEquals(london("2026-12-07T09:00"), s.next(london("2026-12-01T12:00"), london("2026-10-19T09:00")));
    }

    @Test
    void monthlyClampsToTheLastDay() {
        var s = new StandingSchedule(Cadence.MONTHLY, 1, 31, NINE, LONDON);
        assertEquals(london("2026-10-31T09:00"), s.next(london("2026-10-19T12:00"), null));
        assertEquals(london("2026-11-30T09:00"), s.next(london("2026-10-31T09:00"), london("2026-10-31T09:00")));
        assertEquals(london("2027-02-28T09:00"), s.next(london("2027-01-31T10:00"), null));
    }

    @Test
    void keepsWallClockTimeAcrossDaylightSaving() {
        // UK clocks go back on 2026-10-25.
        var s = new StandingSchedule(Cadence.WEEKLY, 1, 1, NINE, LONDON);
        Instant next = s.next(london("2026-10-19T09:00"), london("2026-10-19T09:00"));
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), next);
        assertEquals(Instant.parse("2026-10-19T08:00:00Z"), london("2026-10-19T09:00"));
    }

    @Test
    void rejectsOutOfRangeDays() {
        assertThrows(IllegalArgumentException.class, () -> new StandingSchedule(Cadence.WEEKLY, 8, 1, NINE, LONDON));
        assertThrows(IllegalArgumentException.class, () -> new StandingSchedule(Cadence.MONTHLY, 1, 0, NINE, LONDON));
    }
}
