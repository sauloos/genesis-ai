package ai.genesisbrands.agent.occasions;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The commercial dates that move from year to year, computed rather than left to the model,
 * which gets them wrong often enough to matter. Fixed dates (Christmas, Valentine's Day) are
 * left to the model; anything it proposes is still checked to fall inside the window.
 */
final class KnownDates {

    record Known(LocalDate date, String name) {}

    private KnownDates() {}

    /** Every known moving date for the region that falls between from and to, inclusive. */
    static List<Known> between(LocalDate from, LocalDate to, String region) {
        List<Known> out = new ArrayList<>();
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            for (Known k : forYear(year, region == null ? "" : region)) {
                if (!k.date().isBefore(from) && !k.date().isAfter(to)) out.add(k);
            }
        }
        out.sort(Comparator.comparing(Known::date));
        return out;
    }

    static List<Known> forYear(int year, String region) {
        List<Known> out = new ArrayList<>();
        LocalDate easter = easter(year);
        out.add(new Known(easter.minusDays(2), "Good Friday"));
        out.add(new Known(easter, "Easter Sunday"));
        LocalDate thanksgivingUs = nth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4);
        out.add(new Known(thanksgivingUs.plusDays(1), "Black Friday"));
        out.add(new Known(thanksgivingUs.plusDays(4), "Cyber Monday"));
        switch (region) {
            case "United States" -> {
                out.add(new Known(nth(year, Month.MAY, DayOfWeek.SUNDAY, 2), "Mother's Day"));
                out.add(new Known(nth(year, Month.JUNE, DayOfWeek.SUNDAY, 3), "Father's Day"));
                out.add(new Known(thanksgivingUs, "Thanksgiving"));
                out.add(new Known(nth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day"));
                out.add(new Known(LocalDate.of(year, Month.MAY, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), "Memorial Day"));
                out.add(new Known(nth(year, Month.NOVEMBER, DayOfWeek.SATURDAY, 4), "Small Business Saturday"));
            }
            case "United Kingdom", "Ireland" -> {
                out.add(new Known(easter.minusDays(21), "Mother's Day (Mothering Sunday)"));
                out.add(new Known(nth(year, Month.JUNE, DayOfWeek.SUNDAY, 3), "Father's Day"));
                if (region.equals("United Kingdom")) {
                    out.add(new Known(easter.plusDays(1), "Easter Monday"));
                    out.add(new Known(nth(year, Month.MAY, DayOfWeek.MONDAY, 1), "Early May bank holiday"));
                    out.add(new Known(LocalDate.of(year, Month.MAY, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), "Spring bank holiday"));
                    out.add(new Known(LocalDate.of(year, Month.AUGUST, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), "Summer bank holiday"));
                }
            }
            case "Canada" -> {
                out.add(new Known(nth(year, Month.MAY, DayOfWeek.SUNDAY, 2), "Mother's Day"));
                out.add(new Known(nth(year, Month.JUNE, DayOfWeek.SUNDAY, 3), "Father's Day"));
                out.add(new Known(nth(year, Month.OCTOBER, DayOfWeek.MONDAY, 2), "Thanksgiving"));
                out.add(new Known(nth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labour Day"));
            }
            case "Australia" -> {
                out.add(new Known(nth(year, Month.MAY, DayOfWeek.SUNDAY, 2), "Mother's Day"));
                out.add(new Known(nth(year, Month.SEPTEMBER, DayOfWeek.SUNDAY, 1), "Father's Day"));
                out.add(new Known(easter.plusDays(1), "Easter Monday"));
            }
            case "Brazil" -> {
                out.add(new Known(nth(year, Month.MAY, DayOfWeek.SUNDAY, 2), "Dia das Mães (Mother's Day)"));
                out.add(new Known(nth(year, Month.AUGUST, DayOfWeek.SUNDAY, 2), "Dia dos Pais (Father's Day)"));
                out.add(new Known(easter.minusDays(47), "Carnaval (Tuesday)"));
                out.add(new Known(easter.plusDays(60), "Corpus Christi"));
            }
            case "Portugal", "Spain" -> {
                out.add(new Known(nth(year, Month.MAY, DayOfWeek.SUNDAY, 1), "Mother's Day"));
                out.add(new Known(easter.plusDays(60), "Corpus Christi"));
            }
            case "Germany" -> {
                out.add(new Known(nth(year, Month.MAY, DayOfWeek.SUNDAY, 2), "Muttertag (Mother's Day)"));
                out.add(new Known(easter.plusDays(1), "Easter Monday"));
                out.add(new Known(easter.plusDays(39), "Ascension Day (Vatertag)"));
                out.add(new Known(LocalDate.of(year, Month.DECEMBER, 24).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(3), "First Sunday of Advent"));
            }
            case "France" -> {
                out.add(new Known(easter.plusDays(1), "Easter Monday"));
                out.add(new Known(easter.plusDays(39), "Ascension Day"));
                out.add(new Known(nth(year, Month.JUNE, DayOfWeek.SUNDAY, 3), "Fête des Pères (Father's Day)"));
            }
            default -> { }
        }
        return out;
    }

    /** Western (Gregorian) Easter Sunday, by the anonymous Gregorian algorithm. */
    static LocalDate easter(int year) {
        int a = year % 19, b = year / 100, c = year % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    private static LocalDate nth(int year, Month month, DayOfWeek day, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day));
    }
}
