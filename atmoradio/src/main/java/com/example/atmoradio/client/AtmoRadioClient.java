package com.example.atmoradio.client;

import com.example.atmoradio.client.sound.RadioSoundManager;
import com.example.atmoradio.network.RadioNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

public class AtmoRadioClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.PlayPayload.ID, (payload, context) ->
                context.client().execute(() ->
                        RadioSoundManager.INSTANCE.play(payload.pos(), payload.url(), payload.title())));

        ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.StopPayload.ID, (payload, context) ->
                context.client().execute(() ->
                        RadioSoundManager.INSTANCE.stop(payload.pos())));

        ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.OpenSearchPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    MinecraftClient.getInstance().setScreen(
                            new com.example.atmoradio.client.gui.RadioSearchScreen(payload.pos()));
                }));

        // Частицы нот + окклюзия/дистанция пересчитываются каждый клиентский тик.
        ClientTickEvents.END_CLIENT_TICK.register(RadioSoundManager.INSTANCE::tick);
    }
}
