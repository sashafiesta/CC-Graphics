package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.CCGraphicsDataComponents;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerComputer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists the per-computer {@code graphics_disabled} override to NBT
 * ({@code ccgraphics:GraphicsDisabled}) and to the data-component map (so picking
 * the block up preserves the setting), then pushes the flag onto the running
 * {@code Terminal} each server tick.
 */
@Mixin(value = AbstractComputerBlockEntity.class, remap = false)
abstract class AbstractComputerBlockEntityMixin extends BlockEntity {
    protected AbstractComputerBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
    private static final String CCGRAPHICS_NBT_DISABLED = "ccgraphics:GraphicsDisabled";

    @Shadow
    public abstract @Nullable ServerComputer getServerComputer();

    @Unique
    private boolean ccgraphics$graphicsDisabled = false;

    @Inject(method = "loadServer", at = @At("TAIL"))
    private void ccgraphics$onLoadServer(CompoundTag nbt, HolderLookup.Provider registries, CallbackInfo ci) {
        ccgraphics$graphicsDisabled = nbt.getBoolean(CCGRAPHICS_NBT_DISABLED);
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"), remap = true)
    private void ccgraphics$onSaveAdditional(CompoundTag nbt, HolderLookup.Provider registries, CallbackInfo ci) {
        if (ccgraphics$graphicsDisabled) {
            nbt.putBoolean(CCGRAPHICS_NBT_DISABLED, true);
        }
    }

    @Inject(method = "applyImplicitComponents", at = @At("TAIL"), remap = true)
    private void ccgraphics$onApplyImplicitComponents(BlockEntity.DataComponentInput component, CallbackInfo ci) {
        var value = component.get(CCGraphicsDataComponents.GRAPHICS_DISABLED);
        ccgraphics$graphicsDisabled = value != null && value;
    }

    @Inject(method = "collectSafeComponents", at = @At("TAIL"))
    private void ccgraphics$onCollectSafeComponents(DataComponentMap.Builder builder, CallbackInfo ci) {
        if (ccgraphics$graphicsDisabled) {
            builder.set(CCGraphicsDataComponents.GRAPHICS_DISABLED, true);
        }
    }

    @Inject(method = "serverTick", at = @At("TAIL"))
    private void ccgraphics$onServerTick(CallbackInfo ci) {
        var computer = getServerComputer();
        if (computer != null) {
            var terminal = ((ServerComputerAccessor) computer).getTerminal();
            ((IGraphicsTerminal) terminal).ccgraphics$setGraphicsDisabled(ccgraphics$graphicsDisabled);
        }
    }
}
