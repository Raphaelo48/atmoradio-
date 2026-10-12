package com.example.atmoradio.block;

import com.example.atmoradio.AtmoRadioMod;
import com.example.atmoradio.network.RadioNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

/**
 * Серверная "истина" о том, что сейчас играет это радио.
 * Реальное воспроизведение звука происходит только на клиенте (RadioSoundInstance),
 * сервер лишь хранит состояние для синхронизации новых/приближающихся игроков.
 */
public class RadioBlockEntity extends BlockEntity {
    private String currentStreamUrl = "";
    private String currentTitle = "";
    private boolean playing = false;

    public RadioBlockEntity(BlockPos pos, BlockState state) {
        super(AtmoRadioMod.RADIO_BLOCK_ENTITY, pos, state);
    }

    public void openScreen(ServerPlayerEntity player) {
        // Открываем кастомный экран поиска через ExtendedScreenHandlerFactory на сетевом слое.
        RadioNetworking.openSearchScreen(player, getPos());
    }

    /** Вызывается, когда с сервера пришёл запрос Play (после выбора трека в поиске). */
    public void startStream(String url, String title) {
        this.currentStreamUrl = url;
        this.currentTitle = title;
        this.playing = true;
        markDirty();
        if (world instanceof ServerWorld sw) {
            RadioNetworking.broadcastPlay(sw, getPos(), url, title);
        }
    }

    public void stopAndBroadcast() {
        this.playing = false;
        this.currentStreamUrl = "";
        markDirty();
        if (world instanceof ServerWorld sw) {
            RadioNetworking.broadcastStop(sw, getPos());
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    public String getCurrentStreamUrl() {
        return currentStreamUrl;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.currentStreamUrl = view.getString("StreamUrl", "");
        this.currentTitle = view.getString("Title", "");
        this.playing = view.getBoolean("Playing", false);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putString("StreamUrl", currentStreamUrl);
        view.putString("Title", currentTitle);
        view.putBoolean("Playing", playing);
    }

    /** Когда чанк догружается игроку - догоняем его состоянием, если радио уже играет. */
    public void syncToPlayer(ServerPlayerEntity player) {
        if (playing && world instanceof ServerWorld sw) {
            RadioNetworking.sendPlayTo(player, getPos(), currentStreamUrl, currentTitle);
        }
    }
}
