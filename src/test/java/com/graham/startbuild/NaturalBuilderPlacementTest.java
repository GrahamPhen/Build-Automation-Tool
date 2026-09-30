package com.graham.startbuild;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.StairBlock;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NaturalBuilderPlacementTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void openDecorationsRequireARealSecondClickAndRetainFinalCorrectness() {
        for (var block : new net.minecraft.world.level.block.Block[]{Blocks.SPRUCE_TRAPDOOR, Blocks.OAK_TRAPDOOR, Blocks.SPRUCE_FENCE_GATE}) {
            var want = block.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
            var placed = NaturalBuilder.placeState(want);
            assertFalse(placed.getValue(BlockStateProperties.OPEN));
            assertFalse(NaturalBuilder.matches(placed, want));
            assertSame(block.asItem(), NaturalBuilder.specialUse(placed, want));
            assertTrue(NaturalBuilder.matches(placed.setValue(BlockStateProperties.OPEN, true), want));
            assertNull(NaturalBuilder.specialUse(want, want));
        }
    }

    @Test void poweredLeverAndTrapdoorAreStillCheckedExactly() {
        var lever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, true);
        var initial = NaturalBuilder.placeState(lever);
        assertFalse(NaturalBuilder.matches(initial, lever));
        assertSame(Items.LEVER, NaturalBuilder.specialUse(initial, lever));
        var trapdoor = Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.POWERED, true);
        assertFalse(NaturalBuilder.matches(trapdoor.setValue(BlockStateProperties.POWERED, false), trapdoor));
    }

    @Test void bookshelfClicksSelectTheActualVanillaSlotOnEveryFacing() {
        var block = (ChiseledBookShelfBlock) Blocks.CHISELED_BOOKSHELF;
        var pos = new BlockPos(-17355, 79, -26296);
        for (var facing : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            for (int slot = 0; slot < 6; slot++) {
                var hit = NaturalBuilder.bookshelfHit(pos, facing, slot);
                assertEquals(slot, block.getHitSlot(new BlockHitResult(hit, facing, pos, false), facing).orElseThrow());
            }
        }
        var want = block.defaultBlockState().setValue(ChiseledBookShelfBlock.SLOT_0_OCCUPIED, true)
                .setValue(ChiseledBookShelfBlock.SLOT_5_OCCUPIED, true);
        var have = NaturalBuilder.placeState(want);
        assertFalse(NaturalBuilder.matches(have, want));
        assertEquals(0, NaturalBuilder.nextBookSlot(have, want));
        assertSame(Items.BOOK, NaturalBuilder.specialUse(have, want));
        have = have.setValue(ChiseledBookShelfBlock.SLOT_0_OCCUPIED, true);
        assertEquals(5, NaturalBuilder.nextBookSlot(have, want));
        assertFalse(NaturalBuilder.matches(have, want));
        have = have.setValue(ChiseledBookShelfBlock.SLOT_5_OCCUPIED, true);
        assertTrue(NaturalBuilder.matches(have, want));
        assertNull(NaturalBuilder.specialUse(have, want));
    }

    @Test void doubleChestWaitsForItsPartnerWithoutDisablingVanillaJoining() {
        for (var type : new ChestType[]{ChestType.LEFT, ChestType.RIGHT}) {
            var want = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, type);
            var first = NaturalBuilder.placeState(want);
            assertTrue(NaturalBuilder.waitingForChestPartner(first, want));
            assertFalse(NaturalBuilder.matches(first, want));
            assertFalse(NaturalBuilder.placementSneaks(want, Blocks.STONE.defaultBlockState()));
            assertTrue(NaturalBuilder.placementSneaks(want, first));
            assertFalse(NaturalBuilder.waitingForChestPartner(want, want));
        }
        var input = NaturalBuilder.placementInput(Input.EMPTY);
        assertTrue(input.shift());
        assertFalse(input.jump());
    }

    @Test void slabEscapeMustKeepActualFeetRatherThanGridCenter() {
        var bottomSlab = new AABB(0, 90, 0, 1, 90.5, 1);
        assertTrue(NaturalBuilder.escapeBody(new Vec3(.5, 90.02, .5)).intersects(bottomSlab));
        assertFalse(NaturalBuilder.escapeBody(new Vec3(.5, 90.5, .5)).intersects(bottomSlab));
    }

    @Test void actualVanillaPlacementReproducesWrongScaffoldFaceAndStackedSingleChests() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var level = (PlacementLevel) ((sun.misc.Unsafe) field.get(null)).allocateInstance(PlacementLevel.class);
        level.states = new HashMap<>();
        var target = new BlockPos(0, 66, 0);
        level.states.put(target.below(), Blocks.COBBLESTONE.defaultBlockState());
        var below = new PlacementContext(level, target.below(), Direction.UP, new Vec3(.5, 66, .5), Direction.NORTH, true, Items.SPRUCE_STAIRS);
        var stairs = NaturalBuilder.itemPlacementState((BlockItem) Items.SPRUCE_STAIRS, below);
        assertEquals(Half.BOTTOM, stairs.getValue(StairBlock.HALF), "the old first scaffold cannot make a top stair");
        var wantedTop = stairs.setValue(StairBlock.HALF, Half.TOP);
        assertFalse(NaturalBuilder.temporaryFaceFits(Direction.UP, wantedTop));
        assertTrue(NaturalBuilder.temporaryFaceFits(Direction.NORTH, wantedTop));
        var side = target.south();
        level.states.put(side, Blocks.COBBLESTONE.defaultBlockState());
        var upperSide = new PlacementContext(level, side, Direction.NORTH, new Vec3(.5, 66.75, 1), Direction.NORTH, true, Items.SPRUCE_STAIRS);
        assertEquals(Half.TOP, NaturalBuilder.itemPlacementState((BlockItem) Items.SPRUCE_STAIRS, upperSide).getValue(StairBlock.HALF));
        var trapdoor = NaturalBuilder.itemPlacementState((BlockItem) Items.SPRUCE_TRAPDOOR,
                new PlacementContext(level, side, Direction.NORTH, new Vec3(.5, 66.25, 1), Direction.NORTH, true, Items.SPRUCE_TRAPDOOR));
        assertEquals(Direction.NORTH, trapdoor.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertTrue(NaturalBuilder.canUseTemporaryFace(trapdoor));
        assertTrue(NaturalBuilder.temporaryFaceFits(Direction.NORTH, trapdoor));
        assertFalse(NaturalBuilder.temporaryFaceFits(Direction.SOUTH, trapdoor));
        level.states.clear();
        var chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        level.states.put(target.below(), chest);
        level.states.put(target.east(), chest);
        var stacked = new PlacementContext(level, target.below(), Direction.UP, new Vec3(.5, 66, .5), Direction.SOUTH, true, Items.CHEST);
        var single = NaturalBuilder.itemPlacementState((BlockItem) Items.CHEST, stacked);
        assertEquals(ChestType.SINGLE, single.getValue(ChestBlock.TYPE));
        var wantedLeft = chest.setValue(ChestBlock.TYPE, ChestType.LEFT);
        assertTrue(NaturalBuilder.waitingForChestPartner(single, wantedLeft));
        assertTrue(NaturalBuilder.hasSingleChestPartner(level, target, wantedLeft), "the completed single partner must trigger repair rather than waiting forever");
        assertEquals(target.east(), NaturalBuilder.chestPartner(target, wantedLeft));
        var joined = new PlacementContext(level, target.east(), Direction.WEST, new Vec3(1, 66.5, .5), Direction.SOUTH, true, Items.CHEST);
        assertEquals(ChestType.LEFT, NaturalBuilder.itemPlacementState((BlockItem) Items.CHEST, joined).getValue(ChestBlock.TYPE));
    }

    @Test void bannersUseActualPoseValidationWithoutWaivingFinalRotation() {
        var wanted = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getValue(net.minecraft.resources.Identifier.withDefaultNamespace("white_banner"))
                .defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 12);
        assertFalse(NaturalBuilder.needsFacingMargin(wanted));
        assertFalse(NaturalBuilder.matches(wanted.setValue(BlockStateProperties.ROTATION_16, 11), wanted));
        assertTrue(NaturalBuilder.needsFacingMargin(Blocks.CHEST.defaultBlockState()));
    }

    /** Only the read-only world methods used by the vanilla placement query; no game is started. */
    private static class PlacementLevel extends ClientLevel {
        Map<BlockPos, BlockState> states;
        private PlacementLevel() { super(null, null, null, null, 0, 0, null, false, 0, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
        @Override public boolean hasNeighborSignal(BlockPos pos) { return false; }
        @Override public boolean isUnobstructed(BlockState state, BlockPos pos, CollisionContext context) { return true; }
    }

    private static class PlacementContext extends BlockPlaceContext {
        private final Direction direction;
        private final boolean shift;
        PlacementContext(PlacementLevel level, BlockPos against, Direction face, Vec3 hit, Direction direction, boolean shift,
                         net.minecraft.world.item.Item item) {
            super(level, null, InteractionHand.MAIN_HAND, ItemStack.EMPTY, new BlockHitResult(hit, face, against, false));
            this.direction = direction; this.shift = shift;
        }
        @Override public Direction getHorizontalDirection() { return direction; }
        @Override public boolean isSecondaryUseActive() { return shift; }
        @Override public Direction[] getNearestLookingDirections() { return new Direction[]{getClickedFace().getOpposite(), direction, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP}; }
    }
}
