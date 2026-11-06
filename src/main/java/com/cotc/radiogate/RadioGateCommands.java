package com.cotc.radiogate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /radiogate pending | list | denied | approve <id> | deny <id> | revoke <url> | add <url>}, for operators (op_level, default 2),
 * the console, and the owner of a singleplayer/LAN world.
 */
public final class RadioGateCommands {
    private RadioGateCommands() {
    }


    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("radiogate")
                .requires(RadioGate::isStaff)
                .then(Commands.literal("pending").executes(RadioGateCommands::pending))
                .then(Commands.literal("list").executes(ctx -> showDecisions(ctx, true)))
                .then(Commands.literal("denied").executes(ctx -> showDecisions(ctx, false)))
                .then(Commands.literal("approve").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> decideById(ctx, true))))
                .then(Commands.literal("deny").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> decideById(ctx, false))))
                .then(Commands.literal("add").then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(RadioGateCommands::add)))
                .then(Commands.literal("revoke").then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(RadioGateCommands::revoke))));
    }

    private static GateStore store(CommandContext<CommandSourceStack> ctx) {
        GateStore store = RadioGate.store();
        if (store == null) {
            ctx.getSource().sendFailure(Component.literal("Radio Gate is not loaded yet."));
        }
        return store;
    }

    private static int pending(CommandContext<CommandSourceStack> ctx) {
        GateStore store = store(ctx);
        if (store == null) {
            return 0;
        }
        List<GateStore.Request> pending = store.pendingRequests();
        if (pending.isEmpty()) {
            ctx.getSource().sendSuccess(() -> RadioGate.prefix().append(Component.literal("Nothing is waiting for approval.").withStyle(ChatFormatting.GRAY)), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> RadioGate.prefix().append(Component.literal(pending.size() + " waiting for approval:").withStyle(ChatFormatting.GRAY)), false);
        for (GateStore.Request r : pending) {
            Component row = Component.literal(" #" + r.id() + " ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(r.playerName() + " ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(Urls.forDisplay(r.url())).withStyle(style -> style
                            .withColor(ChatFormatting.YELLOW)
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(Urls.forDisplay(r.url()))))))
                    .append(Component.literal(" "))
                    .append(action("[Approve]", ChatFormatting.GREEN, "/radiogate approve " + r.id()))
                    .append(Component.literal(" "))
                    .append(action("[Deny]", ChatFormatting.RED, "/radiogate deny " + r.id()));
            ctx.getSource().sendSuccess(() -> row, false);
        }
        return pending.size();
    }

    private static Component action(String label, ChatFormatting color, String command) {
        return Component.literal(label).withStyle(style -> style.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static int showDecisions(CommandContext<CommandSourceStack> ctx, boolean approvedList) {
        GateStore store = store(ctx);
        if (store == null) {
            return 0;
        }
        List<GateStore.Decision> decisions = approvedList ? store.approvedList() : store.deniedList();
        String label = approvedList ? "approved" : "denied";
        if (decisions.isEmpty()) {
            ctx.getSource().sendSuccess(() -> RadioGate.prefix().append(Component.literal("No " + label + " stations yet.").withStyle(ChatFormatting.GRAY)), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> RadioGate.prefix().append(Component.literal(decisions.size() + " " + label + ":").withStyle(ChatFormatting.GRAY)), false);
        for (GateStore.Decision d : decisions) {
            Component row = Component.literal("  " + Urls.forDisplay(d.url())).withStyle(style -> style
                    .withColor(approvedList ? ChatFormatting.GREEN : ChatFormatting.RED)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(Urls.forDisplay(d.url()) + "\nby " + d.decidedBy()))));
            ctx.getSource().sendSuccess(() -> row, false);
        }
        return decisions.size();
    }

    private static int decideById(CommandContext<CommandSourceStack> ctx, boolean approve) {
        GateStore store = store(ctx);
        if (store == null) {
            return 0;
        }
        int id = IntegerArgumentType.getInteger(ctx, "id");
        GateStore.Request request = store.request(id);
        if (request == null) {
            ctx.getSource().sendFailure(Component.literal("No request #" + id + " is waiting. It may already be decided - /radiogate pending"));
            return 0;
        }
        List<GateStore.Request> same = new ArrayList<>();
        for (GateStore.Request r : store.pendingRequests()) {
            if (r.url().equals(request.url())) {
                same.add(r);
            }
        }
        String by = ctx.getSource().getTextName();
        if (approve) {
            String problem = Urls.problem(request.url(), RgConfig.schemes(), RgConfig.blockedHosts());
            if (problem != null) {
                store.deny(request.url(), by + " (unsafe link)", System.currentTimeMillis());
                RadioGate.changed();
                ctx.getSource().sendFailure(Component.literal("That link is not allowed (" + problem + "); it was denied instead."));
                return 0;
            }
            store.approve(request.url(), by, System.currentTimeMillis());
        } else {
            store.deny(request.url(), by, System.currentTimeMillis());
        }
        RadioGate.changed();
        ctx.getSource().sendSuccess(() -> RadioGate.prefix()
                .append(Component.literal(approve ? "Approved " : "Denied ").withStyle(approve ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal(Urls.forDisplay(request.url())).withStyle(ChatFormatting.GRAY)), true);
        for (GateStore.Request r : same) {
            notifyRequester(r, approve);
        }
        return 1;
    }

    private static int add(CommandContext<CommandSourceStack> ctx) {
        GateStore store = store(ctx);
        if (store == null) {
            return 0;
        }
        String url = StringArgumentType.getString(ctx, "url");
        String problem = Urls.problem(url, RgConfig.schemes(), RgConfig.blockedHosts());
        if (problem != null) {
            ctx.getSource().sendFailure(Component.literal("Not allowed: " + problem));
            return 0;
        }
        String host = Urls.host(url);
        if (RgConfig.RESOLVE_HOSTNAMES.get() && Urls.isName(host)) {
            switch (HostChecker.status(host)) {
                case PENDING -> {
                    ctx.getSource().sendFailure(Component.literal("Checking " + host + " ... run the command again in a few seconds."));
                    return 0;
                }
                case PRIVATE -> {
                    ctx.getSource().sendFailure(Component.literal(host + " points to a private address."));
                    return 0;
                }
                case UNKNOWN_HOST -> {
                    ctx.getSource().sendFailure(Component.literal("Can't find " + host));
                    return 0;
                }
                default -> {
                }
            }
        }
        List<GateStore.Request> affected = new ArrayList<>();
        for (GateStore.Request r : store.pendingRequests()) {
            if (r.url().equals(url)) {
                affected.add(r);
            }
        }
        store.approve(url, ctx.getSource().getTextName(), System.currentTimeMillis());
        RadioGate.changed();
        ctx.getSource().sendSuccess(() -> RadioGate.prefix()
                .append(Component.literal("Approved ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(Urls.forDisplay(url)).withStyle(ChatFormatting.GRAY)), true);
        for (GateStore.Request r : affected) {
            notifyRequester(r, true);
        }
        return 1;
    }

    private static int revoke(CommandContext<CommandSourceStack> ctx) {
        GateStore store = store(ctx);
        if (store == null) {
            return 0;
        }
        String url = StringArgumentType.getString(ctx, "url");
        if (!store.forget(url)) {
            ctx.getSource().sendFailure(Component.literal("That station is not on either list."));
            return 0;
        }
        RadioGate.changed();
        ctx.getSource().sendSuccess(() -> RadioGate.prefix()
                .append(Component.literal("Revoked ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Urls.forDisplay(url)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" - it stops playing for everyone.").withStyle(ChatFormatting.GRAY)), true);
        return 1;
    }

    private static void notifyRequester(GateStore.Request request, boolean approve) {
        if (RadioGate.server() == null) {
            return;
        }
        ServerPlayer player = RadioGate.server().getPlayerList().getPlayer(request.playerId());
        if (player != null) {
            RadioGate.tellPlayer(player, approve
                    ? Component.literal("Your station was approved. Start it again to listen.").withStyle(ChatFormatting.GREEN)
                    : Component.literal("Your station was not approved.").withStyle(ChatFormatting.RED));
        }
    }
}
