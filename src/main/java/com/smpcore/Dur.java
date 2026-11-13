package com.smpcore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Dur {
    private static final Pattern FULL = Pattern.compile("(?:\\d+[smhdwy])+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdwy])", Pattern.CASE_INSENSITIVE);

    private Dur() {}

    /** Returns -1 if not a duration, 0 for permanent, otherwise milliseconds. */
    public static long parse(String s) {
        if (s.equalsIgnoreCase("perm") || s.equalsIgnoreCase("permanent") || s.equalsIgnoreCase("forever")) return 0;
        if (!FULL.matcher(s).matches()) return -1;
        Matcher m = PART.matcher(s);
        long total = 0;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 's' -> total += n * 1000L;
                case 'm' -> total += n * 60_000L;
                case 'h' -> total += n * 3_600_000L;
                case 'd' -> total += n * 86_400_000L;
                case 'w' -> total += n * 604_800_000L;
                case 'y' -> total += n * 31_536_000_000L;
                default -> { }
            }
        }
        return total <= 0 ? -1 : total;
    }

    public static String format(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sb.length() == 0 || (d == 0 && h == 0 && m == 0)) sb.append(sec).append("s");
        return sb.toString().trim();
    }
}
