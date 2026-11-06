package com.cotc.radiogate;

import be.n2styles.project_radio.RadioBlockEntity;
import be.n2styles.project_radio.RadioPayload;
import be.n2styles.project_radio.SpeakerBlockEntity;
import be.n2styles.project_radio.SpeakerPayload;
import com.cotc.radiogate.mixin.RadioNetworkHandlerAccessor;
import com.cotc.radiogate.net.ApprovedPayload;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Radio Gate: live radio only, one operator approval per station, for Project Radio.
 * <p>Server side: every Project Radio packet that can start or change a stream goes through {@link #handleRadio} /
 * {@link #handleSpeaker} (mixin on the packet handlers), every block entity sync is cleaned ({@code RadioBlockEntityMixin}).
 * Client side: the server tells each client which stations are approved, and the client refuses to start anything else
 * ({@code client.ClientGate}).
 */
@Mod(RadioGate.MOD_ID)
public class RadioGate {
    public static final String MOD_ID = "radiogate";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Set while the original Project Radio handler is called from {@link #handleRadio} so our hook lets that call through. */
    public static final ThreadLocal<Boolean> PASS = new ThreadLocal<>();

    private static volatile MinecraftServer server;
    private static volatile GateStore store;

    public RadioGate(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, RgConfig.SPEC);
        modBus.addListener(RadioGate::registerPayloads);
        modBus.addListener(RadioGate::onConfigReload);
        NeoForge.EVENT_BUS.addListener(RadioGate::onServerStarted);
        NeoForge.EVENT_BUS.addListener(RadioGate::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) -> RadioGateCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, RadioGate::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RadioGate::onPlayerLoggedOut);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------------

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(ApprovedPayload.TYPE, ApprovedPayload.CODEC, RadioGate::onApprovedPayload);
    }

    private static void onApprovedPayload(ApprovedPayload payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) {
            ctx.enqueueWork(() -> com.cotc.radiogate.client.ClientGate.update(payload.enabled(), payload.hashes()));
        }
    }

    private static void onConfigReload(ModConfigEvent.Reloading event) {
        if (server != null) {
            server.execute(RadioGate::changed);
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        store = event.getServer().overworld().getDataStorage().computeIfAbsent(GateStore.FACTORY, GateStore.FILE_ID);
        LOGGER.info("[RadioGate] armed - {} stations approved, {} denied, {} awaiting review (gate {})", store.approvedList().size(),
                store.deniedList().size(), store.pendingRequests().size(), RgConfig.enabled() ? "ON" : "OFF");
        purgePortables();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        server = null;
        store = null;
        Gate.clear();
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // HIGHEST: the client must know the approved set before Project Radio replays any running portable radio to it
            syncTo(player);
            purgePortables();
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Gate.forget(event.getEntity().getUUID());
    }

    @Nullable
    public static MinecraftServer server() {
        return server;
    }

    @Nullable
    public static GateStore store() {
        return store;
    }

    // ---- the decision ----------------------------------------------------------------------------------------------------

    /** True if this exact link may play for anyone right now (server side, nothing is submitted). */
    public static boolean playbackAllowed(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        if (!RgConfig.enabled()) {
            return true;
        }
        GateStore s = store;
        return s != null && s.isApproved(url) && Urls.problem(url, RgConfig.schemes(), RgConfig.blockedHosts()) == null;
    }

    /** Call after any change to the approved list or the config: pushes it to clients and drops stale replays. */
    public static void changed() {
        purgePortables();
        syncAll();
    }

    public static void syncAll() {
        MinecraftServer srv = server;
        if (srv != null) {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                syncTo(p);
            }
        }
    }

    public static void syncTo(ServerPlayer player) {
        GateStore s = store;
        List<String> hashes = s == null ? List.of() : new ArrayList<>(s.approvedHashes());
        try {
            PacketDistributor.sendToPlayer(player, new ApprovedPayload(RgConfig.enabled(), hashes));
        } catch (RuntimeException e) {
            LOGGER.debug("[RadioGate] could not sync to {}", player.getGameProfile().getName(), e);
        }
    }

    /** Project Radio keeps every running portable radio and replays it to each player who joins: drop the ones that aren't approved any more. */
    public static void purgePortables() {
        try {
            RadioNetworkHandlerAccessor.radiogate$portables().values().removeIf(p -> !playbackAllowed(p.url()));
        } catch (Throwable t) {
            LOGGER.warn("[RadioGate] could not purge replayed radios", t);
        }
    }

    // ---- the two C2S packets ---------------------------------------------------------------------------------------------

    /**
     * Server thread. The Radio screen's Launch / Stop. Nothing reaches Project Radio's handler (and so the broadcast to every
     * client) unless it passed here; what reaches it is rebuilt: the owner is always the sender.
     */
    public static void handleRadio(RadioPayload payload, IPayloadContext ctx) {
        if (!RgConfig.enabled()) {
            callThrough(() -> RadioNetworkHandlerAccessor.radiogate$callRadio(payload, ctx));
            return;
        }
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos pos = payload.pos();
        if (!pos.equals(BlockPos.ZERO) && !inReach(player, pos, RadioBlockEntity.class)) {
            return;
        }
        String url = "";
        if (payload.isPlaying()) {
            url = payload.url();
            if (url == null || url.isEmpty() || !Gate.allows(player, url)) {
                return;
            }
        }
        RadioPayload clean = new RadioPayload(pos, url, payload.isPlaying(), player.getUUID(), payload.frequency());
        callThrough(() -> RadioNetworkHandlerAccessor.radiogate$callRadio(clean, ctx));
    }

    /** Server thread. The speaker's frequency box: no stream in it, but it must be a speaker in reach. */
    public static void handleSpeaker(SpeakerPayload payload, IPayloadContext ctx) {
        if (RgConfig.enabled()) {
            if (!(ctx.player() instanceof ServerPlayer player) || !inReach(player, payload.pos(), SpeakerBlockEntity.class)) {
                return;
            }
        }
        callThrough(() -> RadioNetworkHandlerAccessor.radiogate$callSpeaker(payload, ctx));
    }

    private static final java.util.concurrent.atomic.AtomicLong FORWARDED = new java.util.concurrent.atomic.AtomicLong();

    /** How many client packets have been handed on to Project Radio so far (for tests and the log). */
    public static long forwarded() {
        return FORWARDED.get();
    }

    private static void callThrough(Runnable original) {
        FORWARDED.incrementAndGet();
        PASS.set(Boolean.TRUE);
        try {
            original.run();
        } finally {
            PASS.remove();
        }
    }

    private static boolean inReach(ServerPlayer player, BlockPos pos, Class<? extends BlockEntity> type) {
        Level level = player.level();
        int reach = RgConfig.REACH_BLOCKS.get();
        if (!level.isLoaded(pos) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > (double) reach * reach) {
            return false;
        }
        return type.isInstance(level.getBlockEntity(pos));
    }

    // ---- staff + messages ------------------------------------------------------------------------------------------------

    /** Operators (op_level+) and the owner of a singleplayer/LAN world, who isn't an op when cheats are off. */
    public static boolean isStaff(ServerPlayer player) {
        return player.hasPermissions(RgConfig.OP_LEVEL.get()) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    public static boolean isStaff(CommandSourceStack src) {
        return src.hasPermission(RgConfig.OP_LEVEL.get()) || src.getPlayer() != null && isStaff(src.getPlayer());
    }

    public static MutableComponent prefix() {
        return Component.literal("[RadioGate] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    public static void tellPlayer(ServerPlayer player, Component body) {
        player.sendSystemMessage(prefix().append(body));
    }

    /** Tells every online operator about a new request, with clickable Approve / Deny buttons. */
    public static void notifyStaff(GateStore.Request request) {
        MinecraftServer srv = server;
        if (srv == null) {
            return;
        }
        Component line = prefix()
                .append(Component.literal(request.playerName()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" wants to play a radio station (#" + request.id() + ")").withStyle(ChatFormatting.GRAY));
        Component urlLine = Component.literal("  " + Urls.forDisplay(request.url())).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(Urls.forDisplay(request.url())))));
        Component buttons = Component.literal("  ")
                .append(button("[Approve]", ChatFormatting.GREEN, "/radiogate approve " + request.id(), "Everyone can use this station from now on"))
                .append(Component.literal(" "))
                .append(button("[Deny]", ChatFormatting.RED, "/radiogate deny " + request.id(), "Refuse this station"));
        for (ServerPlayer staff : srv.getPlayerList().getPlayers()) {
            if (isStaff(staff)) {
                staff.sendSystemMessage(line);
                staff.sendSystemMessage(urlLine);
                staff.sendSystemMessage(buttons);
            }
        }
        LOGGER.info("[RadioGate] request #{} from {}: {}", request.id(), request.playerName(), Urls.forDisplay(request.url()));
    }

    static Component button(String label, ChatFormatting color, String command, String tooltip) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(tooltip))));
    }
}
