package com.locus.app.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the common forms of the OpenStreetMap opening_hours tag ("Mo-Sa 09:00-21:00; Su off", "24/7",
 * "Mo-Fr 09:00-12:00,14:00-18:00", overnight times). Anything fancier (sunrise, months, week numbers) gives
 * UNKNOWN - the app then shows nothing rather than a wrong "closed". Pure Java, unit-tested.
 */
public final class OpeningHours {
    public enum State { OPEN, CLOSED, UNKNOWN }

    public static final class Status {
        public final State state;
        /** OPEN: minute of the day it closes. CLOSED: minute it next opens today, or -1. */
        public final int changeAtMinute;
        /** True for "24/7" - the app says "Open 24 hours". */
        public final boolean allDay;

        Status(State state, int changeAtMinute, boolean allDay) {
            this.state = state;
            this.changeAtMinute = changeAtMinute;
            this.allDay = allDay;
        }
    }

    private static final String[] DAYS = {"mo", "tu", "we", "th", "fr", "sa", "su"};
    private static final Pattern RULE = Pattern.compile("^([A-Za-z,\\- ]*?)\\s*(\\d.*|off|closed)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$");

    private OpeningHours() {
    }

    /** @param day 0 = Monday ... 6 = Sunday; @param minute minute of the day (0..1439) */
    public static Status check(String text, int day, int minute) {
        Status unknown = new Status(State.UNKNOWN, -1, false);
        if (text == null || text.trim().isEmpty()) return unknown;
        @SuppressWarnings("unchecked")
        List<int[]>[] week = new List[7];
        for (int i = 0; i < 7; i++) week[i] = new ArrayList<>();
        boolean all = false;

        String[] rules = text.trim().split(";|(?<=[0-9]),\\s*(?=[A-Za-z])");
        for (String raw : rules) {
            String rule = raw.trim();
            if (rule.isEmpty()) continue;
            if (rule.equalsIgnoreCase("24/7")) {
                for (int i = 0; i < 7; i++) {
                    week[i].clear();
                    week[i].add(new int[]{0, 1440});
                }
                all = true;
                continue;
            }
            Matcher m = RULE.matcher(rule);
            if (!m.matches()) return unknown;
            boolean[] days = new boolean[7];
            boolean holiday = false;
            String dayText = m.group(1).trim();
            if (dayText.isEmpty()) {
                java.util.Arrays.fill(days, true);
            } else {
                for (String tok : dayText.split(",")) {
                    tok = tok.trim().toLowerCase(Locale.ROOT);
                    if (tok.isEmpty()) continue;
                    if (tok.equals("ph") || tok.equals("sh")) {
                        holiday = true;
                        continue;
                    }
                    String[] ends = tok.split("-");
                    int a = indexOf(ends[0].trim());
                    int b = ends.length == 2 ? indexOf(ends[1].trim()) : a;
                    if (a < 0 || b < 0 || ends.length > 2) return unknown;
                    for (int d = a; ; d = (d + 1) % 7) {
                        days[d] = true;
                        if (d == b) break;
                    }
                }
            }
            if (holiday) continue; // public-holiday rules are ignored
            String rest = m.group(2).trim().toLowerCase(Locale.ROOT);
            List<int[]> ranges = new ArrayList<>();
            if (!rest.equals("off") && !rest.equals("closed")) {
                for (String t : rest.split(",")) {
                    Matcher tm = TIME.matcher(t.trim());
                    if (!tm.matches()) return unknown;
                    int start = Integer.parseInt(tm.group(1)) * 60 + Integer.parseInt(tm.group(2));
                    int end = Integer.parseInt(tm.group(3)) * 60 + Integer.parseInt(tm.group(4));
                    if (start > 1440 || end > 1560) return unknown;
                    if (end <= start) end += 1440; // overnight: 18:00-02:00
                    ranges.add(new int[]{start, end});
                }
            }
            for (int d = 0; d < 7; d++) {
                if (!days[d]) continue;
                week[d].clear();
                week[d].addAll(ranges);
            }
            all = false;
        }

        // today's ranges, and the part of yesterday's overnight ranges that spills into today
        int yesterday = (day + 6) % 7;
        for (int[] r : week[yesterday]) {
            if (r[1] > 1440 && minute < r[1] - 1440) return new Status(State.OPEN, r[1] - 1440, false);
        }
        int next = -1;
        for (int[] r : week[day]) {
            if (minute >= r[0] && minute < r[1]) return new Status(State.OPEN, r[1] % 1440, all && r[1] == 1440);
            if (r[0] > minute && (next < 0 || r[0] < next)) next = r[0];
        }
        return new Status(State.CLOSED, next, false);
    }

    /** 0 = Monday ... 6 = Sunday for java.util.Calendar.DAY_OF_WEEK (1 = Sunday ... 7 = Saturday). */
    public static int dayIndex(int calendarDayOfWeek) {
        return (calendarDayOfWeek + 5) % 7;
    }

    public static String hhmm(int minute) {
        minute = ((minute % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60);
    }

    private static int indexOf(String d) {
        for (int i = 0; i < 7; i++) {
            if (DAYS[i].equals(d)) return i;
        }
        return -1;
    }
}
