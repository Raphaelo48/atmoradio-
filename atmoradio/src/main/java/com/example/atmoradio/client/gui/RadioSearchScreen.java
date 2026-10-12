package com.example.atmoradio.client.gui;

import com.example.atmoradio.client.sound.RadioSoundManager;
import com.example.atmoradio.network.RadioNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Стильный ванильный GUI: строка поиска сверху, прокручиваемый список найденных
 * треков по центру, Play/Stop снизу. Все HTTP-запросы идут асинхронно, чтобы не
 * подвешивать рендер-поток игры.
 */
public class RadioSearchScreen extends Screen {
    private static final int LIST_TOP = 46;
    private static final int LIST_ITEM_HEIGHT = 18;

    private final BlockPos radioPos;
    private final TrackProvider provider = TrackProvider.itunesPreview();

    private TextFieldWidget searchBox;
    private ButtonWidget playButton;
    private ButtonWidget stopButton;
    private List<TrackProvider.Track> results = new ArrayList<>();
    private int selectedIndex = -1;
    private boolean searching = false;
    private String statusText = "";

    public RadioSearchScreen(BlockPos radioPos) {
        super(Text.translatable("gui.atmoradio.search.title"));
        this.radioPos = radioPos;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int boxWidth = Math.min(280, this.width - 40);

        searchBox = new TextFieldWidget(this.textRenderer, centerX - boxWidth / 2, 20, boxWidth - 70, 20,
                Text.translatable("gui.atmoradio.search.box"));
        searchBox.setMaxLength(100);
        addSelectableChild(searchBox);
        setInitialFocus(searchBox);

        ButtonWidget searchButton = ButtonWidget.builder(Text.translatable("gui.atmoradio.search.button"), btn -> doSearch())
                .dimensions(centerX + boxWidth / 2 - 60, 20, 60, 20)
                .build();
        addDrawableChild(searchButton);

        playButton = ButtonWidget.builder(Text.translatable("gui.atmoradio.play"), btn -> doPlay())
                .dimensions(centerX - 130, this.height - 30, 125, 20)
                .build();
        playButton.active = false;
        addDrawableChild(playButton);

        stopButton = ButtonWidget.builder(Text.translatable("gui.atmoradio.stop"), btn -> doStop())
                .dimensions(centerX + 5, this.height - 30, 125, 20)
                .build();
        addDrawableChild(stopButton);
    }

    private void doSearch() {
        String query = searchBox.getText().trim();
        if (query.isEmpty() || searching) return;
        searching = true;
        statusText = Text.translatable("gui.atmoradio.search.loading").getString();
        selectedIndex = -1;
        playButton.active = false;

        CompletableFuture.supplyAsync(() -> {
            try {
                return provider.search(query);
            } catch (Exception e) {
                return List.<TrackProvider.Track>of();
            }
        }).thenAccept(found -> this.client.execute(() -> {
            this.results = found;
            this.searching = false;
            this.statusText = found.isEmpty()
                    ? Text.translatable("gui.atmoradio.search.empty").getString()
                    : "";
        }));
    }

    private void doPlay() {
        if (selectedIndex < 0 || selectedIndex >= results.size()) return;
        TrackProvider.Track track = results.get(selectedIndex);
        String fullTitle = track.artist().isEmpty() ? track.title() : track.artist() + " - " + track.title();
        ClientPlayNetworking.send(new RadioNetworking.RequestPlayPayload(radioPos, track.streamUrl(), fullTitle));
        this.close();
    }

    private void doStop() {
        ClientPlayNetworking.send(new RadioNetworking.RequestStopPayload(radioPos));
        this.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 6, 0xFFFFFF);

        int centerX = this.width / 2;
        int listWidth = Math.min(300, this.width - 20);
        int listLeft = centerX - listWidth / 2;
        int listBottom = this.height - 40;

        // фон списка в ванильном стиле (тёмная панель)
        context.fill(listLeft, LIST_TOP, listLeft + listWidth, listBottom, 0x88000000);

        if (!statusText.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, statusText, centerX, LIST_TOP + 10, 0xAAAAAA);
        }

        for (int i = 0; i < results.size(); i++) {
            int y = LIST_TOP + 2 + i * LIST_ITEM_HEIGHT;
            if (y + LIST_ITEM_HEIGHT > listBottom) break;
            TrackProvider.Track t = results.get(i);
            boolean hovered = mouseX >= listLeft && mouseX <= listLeft + listWidth && mouseY >= y && mouseY < y + LIST_ITEM_HEIGHT;
            boolean selected = i == selectedIndex;
            if (selected) {
                context.fill(listLeft, y, listLeft + listWidth, y + LIST_ITEM_HEIGHT, 0xAA4A90D9);
            } else if (hovered) {
                context.fill(listLeft, y, listLeft + listWidth, y + LIST_ITEM_HEIGHT, 0x55FFFFFF);
            }
            String label = t.artist().isEmpty() ? t.title() : t.artist() + " \u2013 " + t.title();
            context.drawText(this.textRenderer, Text.literal(label), listLeft + 6, y + 5, 0xFFFFFF, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int centerX = this.width / 2;
        int listWidth = Math.min(300, this.width - 20);
        int listLeft = centerX - listWidth / 2;

        if (mouseX >= listLeft && mouseX <= listLeft + listWidth && mouseY >= LIST_TOP && mouseY <= this.height - 40) {
            int index = (int) ((mouseY - LIST_TOP - 2) / LIST_ITEM_HEIGHT);
            if (index >= 0 && index < results.size()) {
                selectedIndex = index;
                playButton.active = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
