package com.example.atmoradio.network;

import com.example.atmoradio.AtmoRadioMod;
import com.example.atmoradio.block.RadioBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.codec.PacketCodecs;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Единое место регистрации всех пакетов мода.
 * S2C: PlayPayload / StopPayload - говорят клиенту какой блок что играет.
 * C2S: RequestPlayPayload - клиент (после выбора трека в GUI поиска) просит сервер начать стрим.
 *      RequestStopPayload - кнопка Stop.
 */
public class RadioNetworking {

    public record PlayPayload(BlockPos pos, String url, String title) implements CustomPayload {
        public static final Id<PlayPayload> ID = new Id<>(AtmoRadioMod.id("play"));
        public static final PacketCodec<RegistryByteBuf, PlayPayload> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, PlayPayload::pos,
                PacketCodecs.STRING, PlayPayload::url,
                PacketCodecs.STRING, PlayPayload::title,
                PlayPayload::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record StopPayload(BlockPos pos) implements CustomPayload {
        public static final Id<StopPayload> ID = new Id<>(AtmoRadioMod.id("stop"));
        public static final PacketCodec<RegistryByteBuf, StopPayload> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, StopPayload::pos,
                StopPayload::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record RequestPlayPayload(BlockPos pos, String url, String title) implements CustomPayload {
        public static final Id<RequestPlayPayload> ID = new Id<>(AtmoRadioMod.id("req_play"));
        public static final PacketCodec<RegistryByteBuf, RequestPlayPayload> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, RequestPlayPayload::pos,
                PacketCodecs.STRING, RequestPlayPayload::url,
                PacketCodecs.STRING, RequestPlayPayload::title,
                RequestPlayPayload::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record RequestStopPayload(BlockPos pos) implements CustomPayload {
        public static final Id<RequestStopPayload> ID = new Id<>(AtmoRadioMod.id("req_stop"));
        public static final PacketCodec<RegistryByteBuf, RequestStopPayload> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, RequestStopPayload::pos,
                RequestStopPayload::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(PlayPayload.ID, PlayPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StopPayload.ID, StopPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestPlayPayload.ID, RequestPlayPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestStopPayload.ID, RequestStopPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenSearchPayload.ID, OpenSearchPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(RequestPlayPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerWorld world = context.player().getServerWorld();
                BlockEntity be = world.getBlockEntity(payload.pos());
                if (be instanceof RadioBlockEntity radio) {
                    // TODO: здесь стоит проверить, что игрок достаточно близко к блоку (анти-чит).
                    radio.startStream(payload.url(), payload.title());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RequestStopPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerWorld world = context.player().getServerWorld();
                BlockEntity be = world.getBlockEntity(payload.pos());
                if (be instanceof RadioBlockEntity radio) {
                    radio.stopAndBroadcast();
                }
            });
        });
    }

    public static void broadcastPlay(ServerWorld world, BlockPos pos, String url, String title) {
        PlayPayload payload = new PlayPayload(pos, url, title);
        for (ServerPlayerEntity p : world.getPlayers()) {
            ServerPlayNetworking.send(p, payload);
        }
    }

    public static void broadcastStop(ServerWorld world, BlockPos pos) {
        StopPayload payload = new StopPayload(pos);
        for (ServerPlayerEntity p : world.getPlayers()) {
            ServerPlayNetworking.send(p, payload);
        }
    }

    public static void sendPlayTo(ServerPlayerEntity player, BlockPos pos, String url, String title) {
        ServerPlayNetworking.send(player, new PlayPayload(pos, url, title));
    }

    /** Открытие экрана поиска - в этом скелете используем простой клиентский экран без ExtendedScreenHandler,
     *  т.к. список результатов поиска - чисто клиентская сетевая операция (HTTP), серверу знать о нём не нужно.
     *  Если нужна полная серверная синхронизация поиска между игроками - расширьте на ExtendedScreenHandlerFactory. */
    public static void openSearchScreen(ServerPlayerEntity player, BlockPos pos) {
        ServerPlayNetworking.send(player, new OpenSearchPayload(pos));
    }

    public record OpenSearchPayload(BlockPos pos) implements CustomPayload {
        public static final Id<OpenSearchPayload> ID = new Id<>(AtmoRadioMod.id("open_search"));
        public static final PacketCodec<RegistryByteBuf, OpenSearchPayload> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, OpenSearchPayload::pos,
                OpenSearchPayload::new
        );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
}
