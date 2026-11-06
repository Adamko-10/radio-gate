package com.cotc.radiogate.client;

import be.n2styles.project_radio.RadioStreamer;
import com.cotc.radiogate.Urls;
import com.cotc.radiogate.mixin.RadioStreamerAccessor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client side of Radio Gate. Nothing starts unless the server has said so: the link must be a public http(s) address AND its hash
 * must be in the set the server sent. Before the first message from the server (and outside a world) nothing is allowed.
 */
public final class ClientGate {
    private static volatile boolean synced;
    private static volatile boolean gateOn = true;
    private static volatile Set<String> approved = Set.of();
    private static volatile long lastMessage;

    private ClientGate() {
    }

    public static boolean allowed(String url) {
        if (!synced) {
            return false;
        }
        if (!gateOn) {
            return true;
        }
        return url != null && Urls.clientProblem(url) == null && approved.contains(Urls.hash(url));
    }

    /** Local files (the Walkman) only exist when the server runs with the gate switched off. */
    public static boolean localFilesAllowed() {
        return synced && !gateOn;
    }

    public static void update(boolean enabled, List<String> hashes) {
        approved = new HashSet<>(hashes);
        gateOn = enabled;
        synced = true;
        revalidate();
    }

    /** Leaving a server: forget everything and stop whatever is playing. */
    public static void reset() {
        synced = false;
        gateOn = true;
        approved = Set.of();
        try {
            RadioStreamer.stopAll();
        } catch (Throwable ignored) {
            // nothing was running
        }
    }

    /** Stops every running stream the server no longer approves (after a revoke). */
    public static void revalidate() {
        try {
            RadioStreamerAccessor.radiogate$activeRadios().entrySet().removeIf(e -> {
                if (allowed(e.getValue().url)) {
                    return false;
                }
                e.getValue().stop();
                return true;
            });
        } catch (Throwable ignored) {
            // accessor not available yet
        }
    }

    /** One short line on the action bar (at most every 3 s). */
    public static void refused(String url) {
        long now = System.currentTimeMillis();
        if (now - lastMessage < 3000L) {
            return;
        }
        lastMessage = now;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("This station is not approved on this server."), true);
            }
        });
    }
}
