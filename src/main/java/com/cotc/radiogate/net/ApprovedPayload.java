package com.cotc.radiogate.net;

import com.cotc.radiogate.RadioGate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: whether the gate is on, and the SHA-256 of every approved link. Clients only start a stream that is in this set.
 */
public record ApprovedPayload(boolean enabled, List<String> hashes) implements CustomPacketPayload {
    public static final Type<ApprovedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RadioGate.MOD_ID, "approved"));
    private static final int MAX = 100_000;
    public static final StreamCodec<FriendlyByteBuf, ApprovedPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeBoolean(p.enabled());
        buf.writeVarInt(p.hashes().size());
        for (String h : p.hashes()) {
            buf.writeUtf(h, 64);
        }
    }, buf -> {
        boolean enabled = buf.readBoolean();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX) {
            throw new IllegalArgumentException("too many hashes");
        }
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUtf(64));
        }
        return new ApprovedPayload(enabled, list);
    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
