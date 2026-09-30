package com.graham.startbuild;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NaturalBuilderCleanupTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void failedScaffoldBreakYieldsToOtherWorkWithoutLosingTheSupport() throws Exception {
        var builder = new NaturalBuilder(SchematicModel.blank(1, 1, 1), BlockPos.ZERO, 0);
        // Temporary supports may be outside the schematic, so model-cell attempt limits cannot help.
        var target = new BlockPos(2, 1, 0);
        Set<BlockPos> supports = field(builder, "scaffolds");
        Map<BlockPos, Long> retryAt = field(builder, "scaffoldRetryAt");
        supports.add(target);
        builder.ticks = 100;
        var level = cleanupLevel(Blocks.COBBLESTONE.defaultBlockState());
        verifyScaffoldBreak(builder, level, target);
        assertTrue(retryAt.getOrDefault(target, 0L) > builder.ticks,
                "an unchanged support must back off instead of winning every nextAction decision");
        assertTrue(supports.contains(target), "keep the support tracked for later cleanup");
        assertEquals(0, builder.lastProgressTick, "a failed click is not progress");
        assertTrue(builder.lastProblem().contains(target.toString()), "the stalled target must be identifiable");
    }

    @Test void successfulScaffoldBreakClearsItsRetryAndKeepsModelWorkPending() throws Exception {
        var model = SchematicModel.blank(1, 1, 1);
        model.states[0] = Blocks.STONE.defaultBlockState();
        var builder = new NaturalBuilder(model, BlockPos.ZERO, 0);
        // A temporary support can occupy an unfinished model cell; removing it is not that cell's completion.
        Set<BlockPos> supports = field(builder, "scaffolds");
        Map<BlockPos, Long> retryAt = field(builder, "scaffoldRetryAt");
        supports.add(BlockPos.ZERO);
        retryAt.put(BlockPos.ZERO, 1300L);
        builder.ticks = 1400;
        verifyScaffoldBreak(builder, cleanupLevel(Blocks.AIR.defaultBlockState()), BlockPos.ZERO);
        assertFalse(supports.contains(BlockPos.ZERO));
        assertFalse(retryAt.containsKey(BlockPos.ZERO));
        assertEquals(1, builder.remaining(), "removing a support must not pretend the build block is done");
        assertEquals(builder.ticks, builder.lastProgressTick);
    }

    @Test void permanentlyFailedSupportCannotHangTheFinalCleanup() throws Exception {
        var builder = new NaturalBuilder(SchematicModel.blank(1, 1, 1), BlockPos.ZERO, 0);
        var target = new BlockPos(2, 1, 0);
        Set<BlockPos> supports = field(builder, "scaffolds");
        supports.add(target);
        var level = cleanupLevel(Blocks.COBBLESTONE.defaultBlockState());
        level.target = target; // exposed support: surrounding cells are air, so it cannot be waived as hidden
        var layer = NaturalBuilder.class.getDeclaredField("layer");
        layer.setAccessible(true);
        layer.setInt(builder, 1); // all normal layers have been processed
        var next = NaturalBuilder.class.getDeclaredMethod("nextAction",
                net.minecraft.client.Minecraft.class, net.minecraft.client.player.LocalPlayer.class, ClientLevel.class);
        next.setAccessible(true);
        for (int pass = 0; pass < 20 && !builder.isFinished(); pass++) {
            builder.ticks += 40;
            verifyScaffoldBreak(builder, level, target);
            next.invoke(builder, null, null, level);
        }
        assertTrue(builder.isFinished(), "existing final cleanup must still stop after its bounded passes");
        assertTrue(supports.contains(target), "an unremoved exposed support remains reportable");
    }

    @Test void replayFenceGateWithoutASecondStepMustExhaustItsPlacementAttempts() throws Exception {
        // Preserved Briarwood replay: block-update state13507 repeats at(-18932,79,-29830), alternating with air.
        var have = Block.stateById(13507);
        assertSame(Blocks.SPRUCE_FENCE_GATE, have.getBlock());
        assertEquals(net.minecraft.core.Direction.WEST, have.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertFalse(have.getValue(BlockStateProperties.OPEN));
        assertFalse(have.getValue(BlockStateProperties.POWERED));
        var want = have.setValue(BlockStateProperties.POWERED, true);
        assertFalse(NaturalBuilder.matches(have, want), "retain exact final-state validation");
        assertEquals(have, NaturalBuilder.placeState(want));
        assertNull(NaturalBuilder.specialUse(have, want), "no real second click can set this powered bit");
        var model = SchematicModel.blank(1, 1, 1);
        model.states[0] = want;
        var target = new BlockPos(-18932, 79, -29830);
        var builder = new NaturalBuilder(model, target, 0);
        for (int attempt = 0; attempt < 4; attempt++) {
            builder.ticks += 7;
            verifyAction(builder, cleanupLevel(Blocks.AIR.defaultBlockState()), target, "BREAK", want, false);
            verifyAction(builder, cleanupLevel(have), target, "PLACE", want, false);
        }
        byte[] status = field(builder, "status");
        assertEquals(3, status[0], "the break/place cycle must park instead of resetting forever as a first step");
        assertEquals(1, builder.remaining());
    }

    @Test void realSecondClicksAndChestPartnerWaitingStillGetFirstStepGrace() throws Exception {
        var gate = Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        var lever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, true);
        var chest = Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        for (var want : new BlockState[]{gate, lever, chest}) {
            var model = SchematicModel.blank(1, 1, 1);
            model.states[0] = want;
            var builder = new NaturalBuilder(model, BlockPos.ZERO, 0);
            for (int click = 0; click < 4; click++) {
                verifyAction(builder, cleanupLevel(NaturalBuilder.placeState(want)), BlockPos.ZERO, "PLACE", want, false);
            }
            byte[] status = field(builder, "status");
            assertEquals(1, status[0], "an actionable next click or chest partner must remain eligible");
            verifyAction(builder, cleanupLevel(want), BlockPos.ZERO, "PLACE", want, false);
            assertEquals(0, builder.remaining());
            assertEquals(1, builder.placed);
        }
    }

    private static void verifyScaffoldBreak(NaturalBuilder builder, ClientLevel level, BlockPos target) throws Exception {
        verifyAction(builder, level, target, "BREAK", null, true);
    }

    private static void verifyAction(NaturalBuilder builder, ClientLevel level, BlockPos target,
                                     String kindName, BlockState want, boolean scaffold) throws Exception {
        Class<?> kind = Class.forName("com.graham.startbuild.NaturalBuilder$Kind");
        Object breaking = java.util.Arrays.stream(kind.getEnumConstants())
                .filter(value -> value.toString().equals(kindName)).findFirst().orElseThrow();
        Class<?> action = Class.forName("com.graham.startbuild.NaturalBuilder$Action");
        var constructor = action.getDeclaredConstructor(kind, BlockPos.class, BlockState.class, boolean.class);
        constructor.setAccessible(true);
        Object cleanup = constructor.newInstance(breaking, target, want, scaffold);
        var verify = NaturalBuilder.class.getDeclaredMethod("verify", ClientLevel.class, action);
        verify.setAccessible(true);
        verify.invoke(builder, level, cleanup);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(NaturalBuilder builder, String name) throws Exception {
        Field field = NaturalBuilder.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(builder);
    }

    private static CleanupLevel cleanupLevel(BlockState state) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var level = (CleanupLevel) ((sun.misc.Unsafe) field.get(null)).allocateInstance(CleanupLevel.class);
        level.state = state;
        return level;
    }

    private static class CleanupLevel extends ClientLevel {
        BlockState state;
        BlockPos target;
        private CleanupLevel() { super(null, null, null, null, 0, 0, null, false, 0, 0); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return target == null || target.equals(pos) ? state : Blocks.AIR.defaultBlockState();
        }
    }
}
