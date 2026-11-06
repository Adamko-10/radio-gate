package com.cotc.radiogate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.neoforged.neoforge.common.ModConfigSpec;

/** {@code config/radiogate-common.toml}. Only the server reads these (the client is told the result). */
public final class RgConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue OPS_BYPASS;
    public static final ModConfigSpec.IntValue OP_LEVEL;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_SCHEMES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCKED_HOSTS;
    public static final ModConfigSpec.IntValue MAX_PENDING_PER_PLAYER;
    public static final ModConfigSpec.BooleanValue RESOLVE_HOSTNAMES;
    public static final ModConfigSpec.IntValue REACH_BLOCKS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Master switch. true (default) = Radio Gate checks every station. false = Radio Gate does nothing at all",
                "and Project Radio behaves as if this mod were not installed (any link plays, local Walkman files work).",
                "Only for testing; leave it on.").define("enabled", true);
        OPS_BYPASS = b.comment("true (default): when an operator (see op_level, or the owner of a singleplayer/LAN world) starts a station that is not",
                "approved yet, that station is approved on the spot for everyone. false: operators go through the same request",
                "list as everybody else and have to approve their own request with /radiogate approve.",
                "Either way a link has to pass the safety checks (public http/https address) first.").define("ops_bypass", true);
        OP_LEVEL = b.comment("Permission level that counts as an operator for approvals and for /radiogate (default 2).")
                .defineInRange("op_level", 2, 1, 4);
        ALLOWED_SCHEMES = b.comment("Link types that may be approved. Only \"http\" and \"https\" can ever be allowed: file:, jar:, data:, ftp: and so on",
                "are refused no matter what is written here. Remove \"http\" to accept https links only. Default [\"http\", \"https\"].")
                .defineListAllowEmpty("allowed_schemes", () -> new ArrayList<>(Arrays.asList("http", "https")), () -> "https", o -> o instanceof String);
        BLOCKED_HOSTS = b.comment("Extra hosts that are never allowed, on top of the built-in rule that already refuses localhost, single-word hosts,",
                ".local/.lan/.internal names and every loopback, LAN, link-local and other non-public IP address.",
                "Entries are an exact host (\"radio.example.com\") or a suffix with a star (\"*.example.com\" also blocks example.com). Default empty.")
                .defineListAllowEmpty("blocked_hosts", () -> new ArrayList<String>(), () -> "*.example.com", o -> o instanceof String);
        MAX_PENDING_PER_PLAYER = b.comment("How many station requests one player may have waiting for an operator at the same time (default 3).")
                .defineInRange("max_pending_per_player", 3, 1, 50);
        RESOLVE_HOSTNAMES = b.comment("true (default): the first time a station with a host NAME is requested, the server looks the name up and refuses it",
                "if it points to a loopback/LAN/non-public address. The player is asked to try again a few seconds later.",
                "false: only the written address is checked.").define("resolve_hostnames", true);
        REACH_BLOCKS = b.comment("How far (in blocks) a player may be from a radio block or speaker when their packet changes it (default 16).")
                .defineInRange("reach_blocks", 16, 4, 64);
        SPEC = b.build();
    }

    private RgConfig() {
    }

    /** Schemes that are really allowed: the configured ones, but never anything but http/https. */
    public static List<String> schemes() {
        List<String> out = new ArrayList<>();
        try {
            for (String s : ALLOWED_SCHEMES.get()) {
                String l = s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
                if ((l.equals("http") || l.equals("https")) && !out.contains(l)) {
                    out.add(l);
                }
            }
        } catch (IllegalStateException e) {
            out.add("http");
            out.add("https");
        }
        return out;
    }

    public static List<String> blockedHosts() {
        List<String> out = new ArrayList<>();
        try {
            for (String s : BLOCKED_HOSTS.get()) {
                if (s != null && !s.isBlank()) {
                    out.add(s.toLowerCase(Locale.ROOT).trim());
                }
            }
        } catch (IllegalStateException e) {
            // config not loaded yet: no extra rules
        }
        return out;
    }

    /** Config values are only readable once the file is loaded; before that the gate is on. */
    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
