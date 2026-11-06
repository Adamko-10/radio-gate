package com.cotc.radiogate;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * URL safety rules, shared by the server and the client. Project Radio opens the link with {@code new URL(s)}, so a link is only
 * accepted when {@link URI} and {@link URL} read exactly the same scheme/host/port out of the exact same string, and the host is
 * a public one. Approvals match the exact string (no trimming, no normalising), so what an operator saw is what plays.
 */
public final class Urls {
    public static final int MAX_LENGTH = 512;
    private static final List<String> HTTP_ONLY = List.of("http", "https");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern DOTTED = Pattern.compile("[0-9.]+");
    private static final String[] LOCAL_SUFFIXES = {"localhost", "local", "localdomain", "internal", "lan", "intranet", "corp",
            "private", "home.arpa", "home", "arpa", "onion", "invalid", "test", "example"};

    private Urls() {
    }

    /** Null if the link is acceptable, else a short reason (for logs, not for players). */
    @Nullable
    public static String problem(@Nullable String raw, List<String> schemes, List<String> blockedHosts) {
        if (raw == null || raw.isEmpty()) {
            return "empty";
        }
        if (raw.length() > MAX_LENGTH) {
            return "too long";
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= ' ' || c >= 127 || c == '\\' || c == '§') {
                return "illegal character";
            }
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            return "not a valid link";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTP_ONLY.contains(scheme) || !schemes.contains(scheme)) {
            return "scheme " + scheme + " is not allowed";
        }
        if (uri.isOpaque() || uri.getRawAuthority() == null || uri.getRawAuthority().indexOf('@') >= 0 || uri.getUserInfo() != null) {
            return "no user info allowed";
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "no host";
        }
        URL url;
        try {
            url = new URL(raw);
        } catch (MalformedURLException e) {
            return "not a valid link";
        }
        if (!scheme.equals(url.getProtocol().toLowerCase(Locale.ROOT)) || url.getHost() == null || !host.equalsIgnoreCase(url.getHost())
                || url.getPort() != uri.getPort() || url.getUserInfo() != null) {
            return "link is read two ways";
        }
        String hostProblem = hostProblem(host);
        if (hostProblem != null) {
            return hostProblem;
        }
        String h = normalHost(host);
        for (String rule : blockedHosts) {
            if (matchesRule(h, rule)) {
                return "host is blocked";
            }
        }
        return null;
    }

    /** The check a client uses (no operator rules): public http(s) only. */
    @Nullable
    public static String clientProblem(@Nullable String raw) {
        return problem(raw, HTTP_ONLY, List.of());
    }

    private static boolean matchesRule(String host, String rule) {
        if (rule.startsWith("*.")) {
            String suffix = rule.substring(2);
            return host.equals(suffix) || host.endsWith("." + suffix);
        }
        return host.equals(rule);
    }

    /** Lower-case host without brackets or a trailing dot. */
    public static String normalHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    /** True if the host is a DNS name (not an IP literal): only those are looked up. */
    public static boolean isName(String host) {
        String h = normalHost(host);
        return !h.contains(":") && !DOTTED.matcher(h).matches();
    }

    @Nullable
    static String hostProblem(String rawHost) {
        String h = normalHost(rawHost);
        if (h.isEmpty()) {
            return "no host";
        }
        if (rawHost.startsWith("[")) {
            if (h.indexOf('%') >= 0 || !h.matches("[0-9a-f:.]+")) {
                return "bad IPv6 address";
            }
            try {
                byte[] a = InetAddress.getByName(h).getAddress();
                return isPublic(a) ? null : "non-public address";
            } catch (Exception e) {
                return "bad IPv6 address";
            }
        }
        if (h.contains(":")) {
            return "bad host";
        }
        if (DOTTED.matcher(h).matches()) {
            String[] parts = h.split("\\.", -1);
            if (parts.length != 4) {
                return "unusual IP address";
            }
            byte[] a = new byte[4];
            for (int i = 0; i < 4; i++) {
                String p = parts[i];
                if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.startsWith("0"))) {
                    return "unusual IP address";
                }
                int v = Integer.parseInt(p);
                if (v > 255) {
                    return "unusual IP address";
                }
                a[i] = (byte) v;
            }
            return isPublic(a) ? null : "non-public address";
        }
        String[] labels = h.split("\\.", -1);
        if (labels.length < 2) {
            return "single-word host";
        }
        for (String l : labels) {
            if (!LABEL.matcher(l).matches()) {
                return "bad host name";
            }
        }
        String tld = labels[labels.length - 1];
        if (!tld.chars().anyMatch(Character::isLetter)) {
            return "unusual IP address";
        }
        for (String suffix : LOCAL_SUFFIXES) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) {
                return "local host name";
            }
        }
        return null;
    }

    /** Public, routable unicast address (IPv4 4 bytes, IPv6 16 bytes). */
    public static boolean isPublic(byte[] a) {
        if (a.length == 4) {
            int b0 = a[0] & 255;
            int b1 = a[1] & 255;
            int b2 = a[2] & 255;
            if (b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224) {
                return false;
            }
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return false;
            }
            if (b0 == 169 && b1 == 254) {
                return false;
            }
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return false;
            }
            if (b0 == 192 && b1 == 168) {
                return false;
            }
            if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) {
                return false;
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19 || b1 == 51 && b2 == 100)) {
                return false;
            }
            return !(b0 == 203 && b1 == 0 && b2 == 113);
        }
        if (a.length != 16) {
            return false;
        }
        boolean zero10 = true;
        for (int i = 0; i < 10; i++) {
            zero10 &= a[i] == 0;
        }
        boolean zero12 = zero10 && a[10] == 0 && a[11] == 0;
        if (zero10 && (a[10] & 255) == 0xff && (a[11] & 255) == 0xff || zero12) {
            return isPublic(new byte[]{a[12], a[13], a[14], a[15]}); // ::ffff:a.b.c.d and ::a.b.c.d
        }
        if ((a[0] & 255) == 0x00 && (a[1] & 255) == 0x64 && (a[2] & 255) == 0xff && (a[3] & 255) == 0x9b) {
            return isPublic(new byte[]{a[12], a[13], a[14], a[15]}); // NAT64
        }
        if ((a[0] & 255) == 0x20 && (a[1] & 255) == 0x02) {
            return isPublic(new byte[]{a[2], a[3], a[4], a[5]}); // 6to4
        }
        if ((a[0] & 255) == 0x20 && (a[1] & 255) == 0x01 && (a[2] & 255) == 0x00 && (a[3] & 255) <= 0x01) {
            return false; // Teredo
        }
        if ((a[0] & 255) == 0x20 && (a[1] & 255) == 0x01 && (a[2] & 255) == 0x0d && (a[3] & 255) == 0xb8) {
            return false; // documentation
        }
        return (a[0] & 0xe0) == 0x20; // global unicast 2000::/3 only
    }

    public static boolean allPublic(InetAddress[] all) {
        if (all == null || all.length == 0) {
            return false;
        }
        for (InetAddress a : all) {
            if (!isPublic(a.getAddress())) {
                return false;
            }
        }
        return true;
    }

    /** SHA-256 of the exact link, hex: what the server tells clients about approved stations. */
    public static String hash(String url) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String host(String raw) {
        try {
            String h = new URI(raw).getHost();
            return h == null ? "" : normalHost(h);
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /** Shortened for chat. */
    public static String forDisplay(String url) {
        if (url == null) {
            return "";
        }
        return url.length() <= 70 ? url : url.substring(0, 67) + "...";
    }
}
