package com.example.atmoradio.block;

import com.example.atmoradio.AtmoRadioMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Физический блок радио. Вся "умная" логика (поиск/стрим/звук) живёт в RadioBlockEntity
 * и на клиенте в RadioSoundInstance - блок только открывает GUI и держит ориентацию.
 */
public class RadioBlock extends Block implements BlockEntityProvider {
    public static final DirectionProperty FACING = DirectionProperty.of("facing",
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);

    public RadioBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof RadioBlockEntity radio) {
                radio.openScreen((net.minecraft.server.network.ServerPlayerEntity) player);
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        // При разрушении блока звук должен оборваться -> шлём стоп-пакет всем наблюдателям.
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof RadioBlockEntity radio) {
            radio.stopAndBroadcast();
        }
        super.onStateReplaced(state, world, pos, moved);
    }

    @Override
    @Nullable
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new RadioBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return null; // тикер не нужен: состояние меняется событийно через сеть
    }
}
