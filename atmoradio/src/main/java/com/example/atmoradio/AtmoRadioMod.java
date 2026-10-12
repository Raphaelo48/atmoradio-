package com.example.atmoradio;

import com.example.atmoradio.block.RadioBlock;
import com.example.atmoradio.block.RadioBlockEntity;
import com.example.atmoradio.network.RadioNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

public class AtmoRadioMod implements ModInitializer {
    public static final String MOD_ID = "atmoradio";

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    public static final RadioBlock RADIO_BLOCK = new RadioBlock(
            AbstractBlock.Settings.create()
                    .mapColor(MapColor.BLACK)
                    .strength(2.0f, 3.0f)
                    .sounds(BlockSoundGroup.WOOD)
                    .nonOpaque()
    );

    public static final BlockItem RADIO_ITEM = new BlockItem(RADIO_BLOCK, new Item.Settings());

    public static BlockEntityType<RadioBlockEntity> RADIO_BLOCK_ENTITY;

    @Override
    public void onInitialize() {
        Registry.register(Registries.BLOCK, id("radio"), RADIO_BLOCK);
        Registry.register(Registries.ITEM, id("radio"), RADIO_ITEM);

        RADIO_BLOCK_ENTITY = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                id("radio"),
                FabricBlockEntityTypeBuilder.create(RadioBlockEntity::new, RADIO_BLOCK).build()
        );

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> entries.add(RADIO_ITEM));

        RadioNetworking.registerCommon();
    }
}
