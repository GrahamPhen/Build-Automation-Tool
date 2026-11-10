package com.graham.startbuild;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SchematicModel#groundLayer}: which layer goes level with the ground. The numbers are the real bottom
 * layers of queued schematics (blocks per layer after the concrete display base is left out, of which grass /
 * dirt / water), measured from the files.
 */
class GroundLayerTest {

    @Test
    void ownGrassIsTheGround() {
        // medieval_tavern_b1 69x69: concrete base, then a grass island, then plants and the house floor
        assertEquals(1, SchematicModel.groundLayer(69 * 69, new int[]{0, 3769, 2331, 160}, new int[]{0, 3769, 0, 0}, 3));
        // medieval_fisherman_house_b1: four layers of landscaping with a pond (water counts as the creator's land)
        assertEquals(3, SchematicModel.groundLayer(69 * 69, new int[]{3769, 3624, 3769, 3769, 1040}, new int[]{3769, 3448, 3291, 3325, 0}, 6));
        // Medieval_Castle: no base, a grass layer under the walls
        assertEquals(0, SchematicModel.groundLayer(32 * 48, new int[]{1255, 1044, 630}, new int[]{759, 228, 98}, 2));
    }

    @Test
    void floorOnAPlinthGoesLevelWithTheGround() {
        // medieval_mansion_b1 49x46: concrete base, three layers of stone plinth, the floor, the door on it
        assertEquals(4, SchematicModel.groundLayer(49 * 46, new int[]{0, 1922, 1772, 1616, 1000, 443}, new int[]{0, 304, 133, 0, 12, 0}, 5));
    }

    @Test
    void floorOnTheBottomLayerIsFlushNotAStepUp() {
        // medieval_house_b175: concrete base, the floor, the door on it
        assertEquals(1, SchematicModel.groundLayer(55 * 39, new int[]{0, 1278, 736}, new int[]{0, 70, 0}, 2));
        // Grand_Medieval_Mansion: floor in the base layer itself (its concrete taken out around it)
        assertEquals(0, SchematicModel.groundLayer(47 * 35, new int[]{821, 616, 531}, new int[]{0, 30, 0}, 1));
    }

    @Test
    void thingsWithoutAFloorStandOnTheGround() {
        // Giant_Fallen_Log_House: the door is up the log's side - the log is not sunk 4 deep
        assertEquals(0, SchematicModel.groundLayer(31 * 68, new int[]{0, 884, 806, 787, 835, 838}, new int[]{0, 0, 0, 0, 0, 0}, 5));
        // a statue: no door, no land of its own
        assertEquals(-1, SchematicModel.groundLayer(80 * 96, new int[]{894, 258, 158}, new int[]{0, 0, 0}, -1));
        // nothing at all
        assertEquals(-1, SchematicModel.groundLayer(10, new int[]{0, 0}, new int[]{0, 0}, -1));
    }
}
