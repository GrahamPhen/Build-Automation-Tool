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
}
