package com.cotc.radiogate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Decides whether a station a player started may play for everyone. Called on the server thread by {@link RadioGate#handleRadio}.
 */
public final class Gate {
    /** At most one reply line per player every 2 s (the radio screen can be clicked fast). */
    private static final long REPLY_COOLDOWN_MS = 2000L;
    private static final Map<UUID, Long> lastReply = new ConcurrentHashMap<>();

    private Gate() {
    }

    /** True = let it play. False = refused, and the player has been told (or is being told) why. */
    public static boolean allows(ServerPlayer player, String url) {
        String problem = Urls.problem(url, RgConfig.schemes(), RgConfig.blockedHosts());
        if (problem != null) {
            RadioGate.LOGGER.info("[RadioGate] refused link from {}: {} ({})", player.getGameProfile().getName(), problem, Urls.forDisplay(url));
            reply(player, Component.literal("That is not a usable radio link.").withStyle(ChatFormatting.RED));
            return false;
        }
        GateStore store = RadioGate.store();
        if (store == null) {
            reply(player, Component.literal("Radio is unavailable right now. Try again in a moment.").withStyle(ChatFormatting.RED));
            return false;
        }
        if (store.isApproved(url)) {
            return true;
        }
        if (store.isDenied(url)) {
            reply(player, Component.literal("That station is not allowed on this server.").withStyle(ChatFormatting.RED));
            return false;
        }
        String host = Urls.host(url);
        if (RgConfig.RESOLVE_HOSTNAMES.get() && Urls.isName(host)) {
            switch (HostChecker.status(host)) {
                case PENDING -> {
                    reply(player, Component.literal("Checking that address... try again in a few seconds.").withStyle(ChatFormatting.YELLOW));
                    return false;
                }
                case PRIVATE -> {
                    RadioGate.LOGGER.info("[RadioGate] refused link from {}: {} points to a non-public address", player.getGameProfile().getName(), host);
                    reply(player, Component.literal("That is not a usable radio link.").withStyle(ChatFormatting.RED));
                    return false;
                }
                case UNKNOWN_HOST -> {
                    reply(player, Component.literal("Can't find that address.").withStyle(ChatFormatting.RED));
                    return false;
                }
                default -> {
                }
            }
        }
        if (RgConfig.OPS_BYPASS.get() && RadioGate.isStaff(player)) {
            store.approve(url, player.getGameProfile().getName() + " (auto)", System.currentTimeMillis());
            RadioGate.LOGGER.info("[RadioGate] {} started a new station, approved for everyone: {}", player.getGameProfile().getName(), Urls.forDisplay(url));
            RadioGate.changed();
            RadioGate.tellPlayer(player, Component.literal("New station approved for everyone.").withStyle(ChatFormatting.GREEN));
            return true;
        }
        if (store.isPending(url)) {
            reply(player, Component.literal("That station is already waiting for an operator.").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        int max = RgConfig.MAX_PENDING_PER_PLAYER.get();
        if (store.pendingCountFor(player.getUUID()) >= max) {
            reply(player, Component.literal("You already have " + max + " stations waiting. Wait for those first.").withStyle(ChatFormatting.RED));
            return false;
        }
        GateStore.Request request = store.submit(url, player.getGameProfile().getName(), player.getUUID(), System.currentTimeMillis());
        RadioGate.tellPlayer(player, Component.literal("Sent to the operators. You can use this station once it is approved.").withStyle(ChatFormatting.YELLOW));
        RadioGate.notifyStaff(request);
        return false;
    }

    private static void reply(ServerPlayer player, Component message) {
        long now = System.currentTimeMillis();
        Long previous = lastReply.get(player.getUUID());
        if (previous == null || now - previous >= REPLY_COOLDOWN_MS) {
            lastReply.put(player.getUUID(), now);
            RadioGate.tellPlayer(player, message);
        }
    }

    public static void forget(UUID playerId) {
        lastReply.remove(playerId);
    }

    public static void clear() {
        lastReply.clear();
    }
}
