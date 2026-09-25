package com.bento.crm.whatsapp.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The hours in which unsolicited WhatsApp messages may go out: Monday to Saturday between
 * {@code startHour} and {@code endHour} in {@code zone}. A marketing message landing at 03:00 is
 * how a number earns blocks, and blocks cost a Meta number its quality rating and a personal
 * number its account.
 */
public record BusinessHours(boolean enabled, ZoneId zone, int startHour, int endHour) {

    /**
     * @return when sending may resume if {@code at} falls outside business hours, or {@code null}
     *         if sending at {@code at} is fine
     */
    public Instant nextWindow(Instant at) {
        if (!enabled) {
            return null;
        }
        ZonedDateTime local = at.atZone(zone);

        boolean sunday = local.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean tooEarly = local.getHour() < startHour;
        boolean tooLate = local.getHour() >= endHour;

        if (!sunday && !tooEarly && !tooLate) {
            return null;
        }

        ZonedDateTime next = (tooEarly && !sunday)
                ? local.with(LocalTime.of(startHour, 0))
                : local.plusDays(1).with(LocalTime.of(startHour, 0));

        while (next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1).with(LocalTime.of(startHour, 0));
        }
        return next.toInstant();
    }

    /** Start of the local day containing {@code at}, for per-day caps. */
    public Instant startOfDay(Instant at) {
        return at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }
}
