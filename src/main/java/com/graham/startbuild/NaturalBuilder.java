package com.graham.startbuild;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a schematic the way a player does in creative: flies around it, looks at a face, swings, and
 * the block appears. Every block is a real use-item-on click sent by the player, so on a Flashback
 * recording it looks like a hand build. Nothing is /setblock'd and no printer is involved.
 *
 * Why this replaced Baritone's builder (measured, see docs/PROBLEM-baritone-era.md): Baritone gives a floating cell a goal
 * in mid-air ("stand on top of it") and walks at it for ever; it only makes goals for block types on the
 * hotbar; and it can only match an item's upward-click state, so axis=x logs were either never placed or
 * placed with the wrong grain. This builder instead:
 *
 *   - builds bottom-up, and only ever picks a cell that has something to click against right now;
 *   - works out, by asking the block itself (getStateForPlacement), which face and look direction produce
 *     the EXACT state the schematic wants, and checks the result afterwards;
 *   - for a cell with nothing to click against, places a temporary block, places the real one against
 *     it, then breaks the temporary one - which is what a real builder does;
 *   - picks blocks from the creative inventory itself (like middle-click), so it never runs out;
 *   - never waits on anything: a cell that cannot be done right now is parked and retried later, so one
 *     hard block can never stall the run.
 */
final class NaturalBuilder {

    // ------------------------------------------------------------------ tuning
    /** Max eye-to-hit distance we plan for. Creative reach is 5.0; stay well inside it. */
    private static final double REACH = 4.3;
    /** Blocks per tick when flying far. Creative sprint-flying is about 1.1; this reads as purposeful. */
    private static final double FLY_SPEED = 0.55;
    private static final float MAX_YAW_STEP = 40f;
    private static final float MAX_PITCH_STEP = 30f;
    private static final int VERIFY_TICKS = 2;
    private static final int MAX_ATTEMPTS = 4;
    private static final int SCAFFOLD_SEARCH_DEPTH = 5;

    private final SchematicModel model;
    private final BlockPos origin;
    private final int ticksPerBlock;

    /** Per cell: 0 = air/not ours, 1 = to do, 2 = done, 3 = parked (retry later), 4 = impossible. */
    private final byte[] status;
    private final Map<Integer, Integer> attempts = new HashMap<>();
    private int remaining;
    private int layer;
    private int parkedPasses;

    /** Actions planned ahead (a scaffold sequence). */
    private final Deque<Action> queue = new ArrayDeque<>();
    /** Temporary blocks we placed and still have to remove. */
    private final Set<BlockPos> scaffolds = new HashSet<>();
    /** Temporary blocks that could not be reached: not tried again before this tick. */
    private final Map<BlockPos, Long> scaffoldRetryAt = new HashMap<>();
    /** Set by fly() when the player is blocked mid-route: MOVE plans the route again. */
    private boolean replan;
    private int scaffoldCleanups;
    /** Per cell: do not try to plan it again before this tick (it just failed to plan). */
    private final int[] retryAfter;

    private Action current;
    private Phase phase = Phase.PLAN;
    private int phaseTicks;
    private int cooldown;
    private List<Vec3> route = new ArrayList<>();
    private Vec3 lastPos;
    private int stuckTicks;
    private int nextHotbar;
    private int highestBuiltY;

    long placed;
    long broken;
    long lastProgressTick;
    long ticks;
    private boolean finished;
    private String lastProblem = "";

    private enum Phase { PLAN, MOVE, AIM, VERIFY }

    private enum Kind { PLACE, BREAK, USE, USE_AIR }

    private static final class Action {
        final Kind kind;
        final BlockPos target;          // the cell to fill (PLACE) or clear (BREAK)
        final BlockState want;          // what should be there after a PLACE (null for scaffold)
        final boolean scaffold;
        boolean dig;                    // BREAK made only to get out of an enclosed space
        int forCell = -1;               // scaffold steps: the stuck cell the sequence is for
        BlockPos against;               // PLACE: the block we click; BREAK: == target
        Direction face;                 // face of `against` we click
        Vec3 hit;                       // where on that face
        Vec3 stand;                     // where the player should be (feet)
        Item item;

        Action(Kind kind, BlockPos target, BlockState want, boolean scaffold) {
            this.kind = kind;
            this.target = target;
            this.want = want;
            this.scaffold = scaffold;
        }
    }

    /**
     * Clearing mode (terraforming): the model's AIR cells are the work - each is broken by hand - and the
     * layers are worked TOP-DOWN, the way a player digs into a hill or fells a tree.
     */
    private final boolean clearing;

    NaturalBuilder(SchematicModel model, BlockPos origin, int ticksPerBlock) {
        this(model, origin, ticksPerBlock, false);
    }

    NaturalBuilder(SchematicModel model, BlockPos origin, int ticksPerBlock, boolean clearing) {
        this.model = model;
        this.origin = origin;
        this.clearing = clearing;
        this.ticksPerBlock = Math.max(1, ticksPerBlock);
        this.status = new byte[model.states.length];
        this.retryAfter = new int[model.states.length];
        for (int i = 0; i < status.length; i++) {
            BlockState s = model.states[i];
            if (s != null && (clearing ? s.isAir() : !s.isAir() && !isSecondaryPart(s))) {
                status[i] = 1;
                remaining++;
            }
        }
        // Clearing starts at the top of the work, so routes cruise above it.
        this.highestBuiltY = clearing ? origin.getY() + model.sizeY : origin.getY();
    }

    /** Model y of work layer `n`: bottom-up when building, top-down when clearing. */
    private int ly(int n) {
        return clearing ? model.sizeY - 1 - n : n;
    }

    boolean isFinished() {
        return finished;
    }

    int remaining() {
        return remaining;
    }

    int layer() {
        return layer;
    }

    String lastProblem() {
        return lastProblem;
    }

    // ================================================================== main tick

    void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || finished) {
            return;
        }
        keepFlying(player);

        if (cooldown > 0) {
            cooldown--;
        }
        phaseTicks++;

        switch (phase) {
            case PLAN -> {
                if (cooldown > 0) {
                    hover(player);
                    return;
                }
                current = nextAction(mc, player, level);
                if (current == null) {
                    hover(player);
                    return;
                }
                if (!routeTo(mc, player, level)) {
                    park(current, "no way to get there");
                    cooldown = 2;
                    return;
                }
                enter(Phase.MOVE);
            }
            case MOVE -> {
                if (!stillValid(level, current)) {
                    enter(Phase.PLAN);
                    return;
                }
                if (fly(player, level)) {
                    enter(Phase.AIM);
                } else if (replan) {
                    // Bumped into something the plan did not expect (a block placed since): plan again.
                    replan = false;
                    if (!routeTo(mc, player, level)) {
                        park(current, "no way to get there");
                        enter(Phase.PLAN);
                    }
                } else if (phaseTicks > 20 * 20) {
                    // Could not get there in 20 s: give up on this one for now, try something else.
                    park(current, "could not fly into position");
                    enter(Phase.PLAN);
                }
            }
            case AIM -> {
                hover(player);
                if (!stillValid(level, current)) {
                    enter(Phase.PLAN);
                    return;
                }
                if (current.hit.distanceTo(player.getEyePosition()) > REACH + 0.6) {
                    if (routeTo(mc, player, level)) {
                        enter(Phase.MOVE);
                    } else {
                        park(current, "no way to get there");
                        enter(Phase.PLAN);
                    }
                    return;
                }
                if (aim(player, current.hit) || phaseTicks > 12) {
                    act(mc, player, level, current);
                    enter(Phase.VERIFY);
                }
            }
            case VERIFY -> {
                hover(player);
                aim(player, current.hit);
                if (phaseTicks < VERIFY_TICKS) {
                    return;
                }
                verify(level, current);
                cooldown = ticksPerBlock;
                enter(Phase.PLAN);
            }
        }
    }

    private void enter(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    // ================================================================== choosing work

    private Action nextAction(Minecraft mc, LocalPlayer player, ClientLevel level) {
        // 1. Finish any planned sequence (scaffold -> real block -> remove scaffold).
        while (!queue.isEmpty()) {
            Action a = queue.pollFirst();
            if (prepare(mc, player, level, a)) {
                return a;
            }
        }
        // 2. Remove any temporary block that is no longer needed (one that could not be reached is left
        //    for a minute rather than retried every tick - that used to stall the whole build).
        if (!scaffolds.isEmpty()) {
            for (BlockPos s : new ArrayList<>(scaffolds)) {
                if (level.getBlockState(s).isAir()) {
                    scaffolds.remove(s);
                    continue;
                }
                if (scaffoldRetryAt.getOrDefault(s, 0L) > ticks) continue;
                Action b = new Action(Kind.BREAK, s, null, true);
                if (prepare(mc, player, level, b)) {
                    return b;
                }
            }
        }

        // 3. The normal case: the nearest cell that can be clicked into place right now, working upwards.
        while (layer < model.sizeY) {
            int y = ly(layer);
            Action a = bestInLayers(mc, player, level, y, y);
            if (a != null) {
                return a;
            }
            if (!hasOpen(y)) {
                layer++;
                if (!clearing) highestBuiltY = Math.max(highestBuiltY, origin.getY() + layer);
                continue;
            }
            if (!clearing) {
                // Everything left in this layer is stuck for now. A cell under an overhang can be clicked
                // from below once the block above it exists, so let the next layer help before resorting
                // to temporary blocks.
                if (layer + 1 < model.sizeY) {
                    Action up = bestInLayers(mc, player, level, ly(layer + 1), ly(layer + 1));
                    if (up != null) {
                        return up;
                    }
                }
                Action sc = scaffoldFor(mc, player, level, y);
                if (sc != null) {
                    return sc;
                }
            }
            // Nothing works in this layer at the moment: park what is left and move on. Parked cells are
            // retried once the layers above exist (and again at the end).
            parkLayer(y);
            layer++;
        }

        // 4. All layers passed. Retry parked cells a few times; the rest is reported, never waited on.
        if (parkedPasses < 3 && unpark()) {
            parkedPasses++;
            layer = 0;
            return nextAction(mc, player, level);
        }
        // No temporary block may be left in the finished take: retry any that were set aside.
        scaffolds.removeIf(s -> level.getBlockState(s).isAir());
        if (!scaffolds.isEmpty() && scaffoldCleanups++ < 5) {
            scaffoldRetryAt.clear();
            return null;                    // hover a tick; step 2 above picks them up next time
        }
        if (!scaffolds.isEmpty()) {
            StartBuildMod.LOGGER.warn("[StartBuild] {} temporary block(s) could not be removed: {}", scaffolds.size(), scaffolds);
        }
        finished = true;
        return null;
    }

    private boolean hasOpen(int y) {
        for (int z = 0; z < model.sizeZ; z++) {
            for (int x = 0; x < model.sizeX; x++) {
                if (status[model.index(x, y, z)] == 1) return true;
            }
        }
        return false;
    }

    private void parkLayer(int y) {
        for (int z = 0; z < model.sizeZ; z++) {
            for (int x = 0; x < model.sizeX; x++) {
                int i = model.index(x, y, z);
                if (status[i] == 1) status[i] = 3;
            }
        }
    }

    private boolean unpark() {
        boolean any = false;
        for (int i = 0; i < status.length; i++) {
            if (status[i] == 3) {
                status[i] = 1;
                any = true;
            }
        }
        return any;
    }

    /**
     * Nearest actionable cell in layers [fromY, toY], judged from where the player is now. Cells that just
     * failed to plan are skipped for a few seconds (re-solving them every tick froze the game on big
     * layers) - but when nothing else is left they get one full retry before the layer counts as stuck.
     */
    private Action bestInLayers(Minecraft mc, LocalPlayer player, ClientLevel level, int fromY, int toY) {
        skippedForRetry = 0;
        Action a = scanLayers(mc, player, level, fromY, toY);
        if (a == null && skippedForRetry > 0) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = 0; z < model.sizeZ; z++) {
                    for (int x = 0; x < model.sizeX; x++) retryAfter[model.index(x, y, z)] = 0;
                }
            }
            a = scanLayers(mc, player, level, fromY, toY);
        }
        return a;
    }

    private int skippedForRetry;

    private Action scanLayers(Minecraft mc, LocalPlayer player, ClientLevel level, int fromY, int toY) {
        Vec3 eye = player.getEyePosition();
        // Cheap pass: candidates sorted by distance; the expensive click-solving runs lazily in that order.
        List<long[]> cands = new ArrayList<>();
        for (int y = fromY; y <= toY; y++) {
            for (int z = 0; z < model.sizeZ; z++) {
                for (int x = 0; x < model.sizeX; x++) {
                    int i = model.index(x, y, z);
                    if (status[i] != 1) continue;
                    if (retryAfter[i] > ticks) {
                        skippedForRetry++;
                        continue;
                    }
                    BlockPos p = world(x, y, z);
                    BlockState have = level.getBlockState(p);
                    BlockState want = model.states[i];
                    if (want.isAir()) {
                        // Clearing: done once it is gone; water/lava cannot be broken, so it is left.
                        if (have.isAir() || !have.getFluidState().isEmpty()) {
                            markDone(i);
                            continue;
                        }
                        cands.add(new long[]{(long) (eye.distanceTo(Vec3.atCenterOf(p)) * 1000), i});
                        continue;
                    }
                    if (matches(have, want)) {
                        markDone(i);
                        continue;
                    }
                    boolean special = specialUse(have, want) != null;
                    boolean needsBreak = !special && !have.isAir() && !have.canBeReplaced();
                    if (!special && !needsBreak && !hasSolidNeighbour(level, p)) continue;
                    double d = eye.distanceTo(Vec3.atCenterOf(p));
                    cands.add(new long[]{(long) (d * 1000), i});
                }
            }
        }
        cands.sort((a, b) -> Long.compare(a[0], b[0]));
        int tried = 0;
        for (long[] c : cands) {
            if (++tried > 120) break;         // bound the work per decision; the rest waits its turn
            int i = (int) c[1];
            int y = i / (model.sizeX * model.sizeZ);
            int rem = i % (model.sizeX * model.sizeZ);
            int z = rem / model.sizeX;
            int x = rem % model.sizeX;
            BlockPos p = world(x, y, z);
            BlockState have = level.getBlockState(p);
            Action a;
            Item use = specialUse(have, model.states[i]);
            if (model.states[i].isAir()) {
                a = new Action(Kind.BREAK, p, model.states[i], false);
            } else if (use != null) {
                a = new Action(isWater(model.states[i]) ? Kind.USE_AIR : Kind.USE, p, model.states[i], false);
                a.item = use;
            } else if (!have.isAir() && !have.canBeReplaced()) {
                a = new Action(Kind.BREAK, p, model.states[i], false);
            } else {
                a = new Action(Kind.PLACE, p, model.states[i], false);
            }
            if (prepare(mc, player, level, a)) {
                return a;
            }
            // Could not be planned from here: skip it for 5 s instead of re-solving it every tick.
            retryAfter[i] = (int) ticks + 100;
        }
        return null;
    }

    // ================================================================== temporary blocks

    /**
     * For a stuck cell, find a short chain of air cells (air in the world AND in the schematic) from a
     * solid block to a spot that lets us click the real block into place with the right orientation.
     * Queues: place chain -> place real block -> break chain (reverse order).
     */
    private Action scaffoldFor(Minecraft mc, LocalPlayer player, ClientLevel level, int y) {
        Vec3 eye = player.getEyePosition();
        List<Integer> stuck = new ArrayList<>();
        for (int z = 0; z < model.sizeZ; z++) {
            for (int x = 0; x < model.sizeX; x++) {
                int i = model.index(x, y, z);
                if (status[i] == 1) stuck.add(i);
            }
        }
        stuck.sort((a, b) -> Double.compare(eye.distanceToSqr(Vec3.atCenterOf(worldOf(a))),
                eye.distanceToSqr(Vec3.atCenterOf(worldOf(b)))));
        int tried = 0;
        for (int i : stuck) {
            if (++tried > 25) break;
            BlockPos t = worldOf(i);
            BlockState want = model.states[i];
            if (want.getBlock() instanceof FallingBlock || !want.isCollisionShapeFullBlock(level, t)
                    || isWater(want)) {
                continue;       // needs real ground/support (sand, plants, water) - a temporary block cannot help
            }
            for (Direction d : preferredDirections(want)) {
                BlockPos n = t.relative(d);
                if (!freeForScaffold(level, n)) continue;
                List<BlockPos> chain = chainTo(level, n, t);
                if (chain == null) continue;
                // Would clicking n's face (towards t) give the exact state we want? Check it now, with the
                // chain treated as solid, so we never build a scaffold that cannot help.
                if (!axisOk(d.getOpposite(), want)) continue;

                Item scaffoldItem = scaffoldItemFor(want);
                for (BlockPos c : chain) {
                    Action a = new Action(Kind.PLACE, c, null, true);
                    a.item = scaffoldItem;
                    a.forCell = i;
                    queue.addLast(a);
                }
                queue.addLast(new Action(Kind.PLACE, t, want, false));
                for (int k = chain.size() - 1; k >= 0; k--) {
                    Action b = new Action(Kind.BREAK, chain.get(k), null, true);
                    b.forCell = i;
                    queue.addLast(b);
                }
                StartBuildMod.LOGGER.info("[StartBuild] temporary support for {} at {}: {} block(s)",
                        name(want), t, chain.size());
                while (!queue.isEmpty()) {
                    Action a = queue.pollFirst();
                    if (prepare(mc, player, level, a)) return a;
                }
                // The plan could not start: park this cell so it is not re-planned every tick.
                queue.clear();
                status[i] = 3;
                return null;
            }
            status[i] = 3;      // no scaffold possible for this one right now
        }
        return null;
    }

    /** Cells from an anchored spot to `end` (inclusive, anchored end first), or null. BFS through air. */
    private List<BlockPos> chainTo(ClientLevel level, BlockPos end, BlockPos avoid) {
        Map<BlockPos, BlockPos> prev = new HashMap<>();
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(end);
        prev.put(end, end);
        Map<BlockPos, Integer> depth = new HashMap<>();
        depth.put(end, 1);
        while (!q.isEmpty()) {
            BlockPos c = q.pollFirst();
            if (hasSolidNeighbourExcept(level, c, avoid)) {
                List<BlockPos> chain = new ArrayList<>();
                for (BlockPos k = c; ; k = prev.get(k)) {
                    chain.add(k);
                    if (k.equals(end)) break;
                }
                return chain;           // anchored end first, `end` last
            }
            if (depth.get(c) >= SCAFFOLD_SEARCH_DEPTH) continue;
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (prev.containsKey(n) || n.equals(avoid) || !freeForScaffold(level, n)) continue;
                prev.put(n, c);
                depth.put(n, depth.get(c) + 1);
                q.addLast(n);
            }
        }
        return null;
    }

    /** A temporary block may only go where the world is empty and the schematic wants air (or nothing). */
    private boolean freeForScaffold(ClientLevel level, BlockPos p) {
        if (!level.getBlockState(p).isAir()) return false;
        int x = p.getX() - origin.getX(), y = p.getY() - origin.getY(), z = p.getZ() - origin.getZ();
        if (y < 0) return false;
        if (model.inside(x, y, z) && status[model.index(x, y, z)] != 0) return false;
        return !playerBox().intersects(new AABB(p));
    }

    private Item scaffoldItemFor(BlockState want) {
        Item item = want.getBlock().asItem();
        if (item == Items.AIR || want.getBlock() instanceof FallingBlock) {
            return Blocks.COBBLESTONE.asItem();
        }
        return item;
    }

    // ================================================================== planning one action

    /** Fills in how to do `a` (which face to click, where to stand). @return false if it cannot be done now. */
    private boolean prepare(Minecraft mc, LocalPlayer player, ClientLevel level, Action a) {
        if (a.kind == Kind.BREAK) {
            BlockState have = level.getBlockState(a.target);
            if (have.isAir()) {
                if (a.scaffold) scaffolds.remove(a.target);
                return false;
            }
            Direction face = bestVisibleFace(level, player, a.target);
            a.against = a.target;
            a.face = face;
            a.hit = Vec3.atCenterOf(a.target).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            a.stand = standFor(level, player, a.hit, a.target, a.target);
            return a.stand != null;
        }

        if (a.kind == Kind.USE || a.kind == Kind.USE_AIR) {
            boolean fromBelow = a.kind == Kind.USE_AIR || a.want.getBlock() == Blocks.NETHER_PORTAL;
            BlockPos on = fromBelow ? a.target.below() : a.target;
            if (fromBelow && !isSolid(level, on)) return false;
            a.against = on;
            a.face = Direction.UP;
            a.hit = Vec3.atCenterOf(on).add(0, 0.5, 0);
            a.stand = standFor(level, player, a.hit, a.target, on);
            return a.stand != null;
        }

        // PLACE
        if (!level.getBlockState(a.target).canBeReplaced()) return false;
        if (a.want != null) {
            if (a.want.getBlock() instanceof FallingBlock && !isSolid(level, a.target.below())) return false;
            // Plants and the like wait for their ground (farmland, grass) to exist.
            if (!placeState(a.want).canSurvive(level, a.target)) return false;
            Item item = placeItem(a.want);
            if (item == Items.AIR) {
                impossible(a, "no item for " + name(a.want));
                return false;
            }
            a.item = item;
        }
        List<Direction> dirs = (a.want == null) ? List.of(Direction.values()) : preferredDirections(a.want);
        for (Direction d : dirs) {
            BlockPos n = a.target.relative(d);
            BlockState ns = level.getBlockState(n);
            if (!isClickable(ns)) continue;
            Direction face = d.getOpposite();
            // Pillars take their axis from the clicked face: skip faces that cannot give the wanted axis.
            if (a.want != null && a.want.hasProperty(BlockStateProperties.AXIS)
                    && face.getAxis() != a.want.getValue(BlockStateProperties.AXIS)) continue;
            for (Vec3 hit : hitPoints(n, face)) {
                Vec3 stand = standFor(level, player, hit, a.target, n);
                if (stand == null) continue;
                if (a.want != null && !simulate(player, level, a.target, n, face, hit, stand, a.want)) continue;
                a.against = n;
                a.face = face;
                a.hit = hit;
                a.stand = stand;
                return true;
            }
        }
        return false;
    }

    /** Pillars want a click along their axis, so try those faces first; everything else: below first. */
    private static List<Direction> preferredDirections(BlockState want) {
        List<Direction> out = new ArrayList<>();
        if (want.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis axis = want.getValue(BlockStateProperties.AXIS);
            for (Direction d : Direction.values()) if (d.getAxis() == axis) out.add(d);
            for (Direction d : Direction.values()) if (d.getAxis() != axis) out.add(d);
            return out;
        }
        out.add(Direction.DOWN);
        out.add(Direction.NORTH);
        out.add(Direction.SOUTH);
        out.add(Direction.EAST);
        out.add(Direction.WEST);
        out.add(Direction.UP);
        return out;
    }

    private static List<Vec3> hitPoints(BlockPos against, Direction face) {
        Vec3 c = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        List<Vec3> out = new ArrayList<>(3);
        out.add(c);
        if (face.getAxis() != Direction.Axis.Y) {
            out.add(c.add(0, 0.25, 0));     // top half of the face (slabs/stairs "top")
            out.add(c.add(0, -0.25, 0));    // bottom half
        }
        return out;
    }

    /**
     * Asks the block what a click at `hit` on `face` of `against`, from `stand`, would produce - the same
     * call the game makes when you really click. The player's look is set temporarily for the question.
     */
    private boolean simulate(LocalPlayer player, ClientLevel level, BlockPos target, BlockPos against,
                             Direction face, Vec3 hit, Vec3 stand, BlockState want) {
        Item item = placeItem(want);
        if (!(item instanceof BlockItem blockItem)) return false;
        float oy = player.getYRot(), ox = player.getXRot();
        try {
            Vec3 eye = stand.add(0, player.getEyeHeight(), 0);
            float[] rot = lookAngles(eye, hit);
            player.setYRot(rot[0]);
            player.setXRot(rot[1]);
            BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(item),
                    new BlockHitResult(hit, face, against, false));
            if (!ctx.getClickedPos().equals(target) || !ctx.canPlace()) return false;
            want = placeState(want);
            BlockState result = blockItem.getBlock().getStateForPlacement(ctx);
            return result != null && matches(result, want);
        } catch (Throwable t) {
            return false;
        } finally {
            player.setYRot(oy);
            player.setXRot(ox);
        }
    }

    /** Pillars (logs) take their axis from the clicked face. */
    private static boolean axisOk(Direction face, BlockState want) {
        if (!want.hasProperty(BlockStateProperties.AXIS)) return true;
        return face.getAxis() == want.getValue(BlockStateProperties.AXIS);
    }

    /**
     * A spot (feet position) from which `hit` is in reach, the body is in free air and not in `target`, and
     * the face at `hit` of block `against` can actually be SEEN - a player does not click through walls or
     * leaves, and it would look wrong on camera.
     */
    private Vec3 standFor(ClientLevel level, LocalPlayer player, Vec3 hit, BlockPos target, BlockPos against) {
        double eyeH = player.getEyeHeight();
        Vec3 feet = player.position();
        // Staying put reads most naturally, and is the common case once the builder is working an area.
        if (feet.add(0, eyeH, 0).distanceTo(hit) <= REACH && bodyFree(level, feet, target)
                && visible(level, player, feet.add(0, eyeH, 0), hit, against)) {
            return feet;
        }
        Vec3 best = null;
        double bestCost = Double.MAX_VALUE;
        double[] radii = {2.2, 3.0, 1.4};
        double[] heights = {1.6, 2.4, 0.9, 3.2};
        for (double h : heights) {
            for (double r : radii) {
                for (int k = 0; k < 12; k++) {
                    double ang = k * Math.PI / 6;
                    Vec3 cand = new Vec3(target.getX() + 0.5 + Math.cos(ang) * r, target.getY() + h,
                            target.getZ() + 0.5 + Math.sin(ang) * r);
                    if (cand.add(0, eyeH, 0).distanceTo(hit) > REACH) continue;
                    if (!bodyFree(level, cand, target)) continue;
                    double cost = cand.distanceTo(feet);
                    if (cost >= bestCost) continue;
                    if (!visible(level, player, cand.add(0, eyeH, 0), hit, against)) continue;
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = cand;
                    }
                }
            }
            if (best != null) return best;
        }
        return best;
    }

    /** The first block the eye->hit ray meets is `against` (or nothing solid is in the way). */
    private static boolean visible(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 hit, BlockPos against) {
        Vec3 into = hit.add(Vec3.atCenterOf(against).subtract(hit).normalize().scale(0.05));
        BlockHitResult r = level.clip(new ClipContext(eye, into, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return r.getType() == HitResult.Type.MISS || r.getBlockPos().equals(against);
    }

    private boolean bodyFree(ClientLevel level, Vec3 feet, BlockPos target) {
        AABB box = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        if (box.intersects(new AABB(target))) return false;
        Minecraft mc = Minecraft.getInstance();
        return level.noCollision(mc.player, box.inflate(0.05));
    }

    // ================================================================== doing it

    private void act(Minecraft mc, LocalPlayer player, ClientLevel level, Action a) {
        if (a.kind == Kind.BREAK) {
            Item tool = toolFor(level.getBlockState(a.target));
            if (tool != null) holdItem(mc, player, tool);
            mc.gameMode.startDestroyBlock(a.target, a.face);
            player.swing(InteractionHand.MAIN_HAND);
            broken++;
            return;
        }
        if (!holdItem(mc, player, a.item)) {
            impossible(a, "could not pick " + a.item);
            return;
        }
        if (a.kind == Kind.USE_AIR) {
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
            return;
        }
        BlockHitResult hit = new BlockHitResult(a.hit, a.face, a.against, false);
        InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (r.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        if (a.scaffold) {
            scaffolds.add(a.target);
        }
    }

    private void verify(ClientLevel level, Action a) {
        BlockState have = level.getBlockState(a.target);
        if (a.kind == Kind.BREAK) {
            if (have.isAir()) {
                if (a.scaffold) scaffolds.remove(a.target);
                if (a.want != null && a.want.isAir()) markDone(indexOf(a.target));   // clearing: that cell is done
                if (a.dig && !clearing) {
                    // A build block broken to get out: rebuild it later (parked, so not the very next
                    // action - that would shut the player in again).
                    int di = indexOf(a.target);
                    if (di >= 0 && status[di] == 2) {
                        status[di] = 3;
                        remaining++;
                    }
                }
                progress();
                return;
            }
            // Not gone yet: it is simply picked again - but something that never breaks is parked.
            int bi = indexOf(a.target);
            if (!a.scaffold && bi >= 0 && attempts.merge(bi, 1, Integer::sum) >= 12) {
                status[bi] = 3;
                lastProblem = "could not break " + name(have) + " at " + a.target;
            }
            return;
        }
        if (a.scaffold) {
            if (!have.isAir()) progress();
            return;
        }
        int i = indexOf(a.target);
        if (!matches(have, a.want) && a.kind == Kind.PLACE && !placeState(a.want).equals(a.want)
                && matches(have, placeState(a.want))) {
            progress();         // first step of a two-step block (dirt before farmland, pot before plant)
            return;
        }
        if (matches(have, a.want)) {
            placed++;
            markDone(i);
            highestBuiltY = Math.max(highestBuiltY, a.target.getY());
            progress();
            return;
        }
        int n = attempts.merge(i, 1, Integer::sum);
        if (!have.isAir()) {
            // Wrong state (e.g. orientation): the next plan sees a wrong block and breaks it, then retries.
            lastProblem = "placed " + have + " but wanted " + a.want;
            StartBuildMod.LOGGER.info("[StartBuild] {} at {} (attempt {})", lastProblem, a.target, n);
        }
        if (n >= ((a.kind == Kind.PLACE) ? MAX_ATTEMPTS : 12) && i >= 0) {
            status[i] = 3;     // park; retried in a later pass
        }
    }

    private void progress() {
        lastProgressTick = ticks;
    }

    // ================================================================== creative inventory

    /** The tool a player would dig this with (creative breaks instantly anyway; this is for the look). */
    private static Item toolFor(BlockState s) {
        if (s.is(BlockTags.MINEABLE_WITH_AXE)) return Items.DIAMOND_AXE;
        if (s.is(BlockTags.MINEABLE_WITH_SHOVEL)) return Items.DIAMOND_SHOVEL;
        if (s.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Items.DIAMOND_PICKAXE;
        if (s.is(BlockTags.LEAVES)) return Items.SHEARS;
        return null;
    }

    private int findHotbar(Inventory inv, Item item) {
        for (int s = 0; s < 9; s++) {
            ItemStack st = inv.getItem(s);
            if (!st.isEmpty() && st.getItem() == item) return s;
        }
        return -1;
    }

    /** Puts `item` in the hand the way creative pick-block does. */
    private boolean holdItem(Minecraft mc, LocalPlayer player, Item item) {
        if (item == null || item == Items.AIR) return false;
        Inventory inv = player.getInventory();
        int slot = findHotbar(inv, item);
        if (slot < 0) {
            slot = nextHotbar;
            nextHotbar = (nextHotbar + 1) % 9;
            ItemStack stack = new ItemStack(item, 64);
            inv.setItem(slot, stack.copy());
            mc.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
        }
        if (inv.getSelectedSlot() != slot) {
            inv.setSelectedSlot(slot);
        }
        return true;
    }

    // ================================================================== flying

    private void keepFlying(LocalPlayer player) {
        if (!player.getAbilities().flying && player.getAbilities().mayfly) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
    }

    private void hover(LocalPlayer player) {
        player.setDeltaMovement(0, 0, 0);
    }

    /** Fly along `route`. @return true once at the end of it. */
    private boolean fly(LocalPlayer player, ClientLevel level) {
        if (route.isEmpty()) {
            hover(player);
            return true;
        }
        Vec3 pos = player.position();
        Vec3 wp = route.get(0);
        Vec3 delta = wp.subtract(pos);
        double dist = delta.length();
        if (dist < 0.25) {
            route.remove(0);
            if (route.isEmpty()) {
                hover(player);
                return true;
            }
            return false;
        }
        double speed = Math.min(FLY_SPEED, Math.max(0.12, dist * 0.45));
        Vec3 v = delta.normalize().scale(speed);
        player.setDeltaMovement(v);
        // Look where we are going when travelling, like a player would.
        if (dist > 3) {
            float[] rot = lookAngles(player.getEyePosition(), wp.add(0, player.getEyeHeight(), 0));
            turnTowards(player, rot[0], Mth.clamp(rot[1], -20f, 45f));
        }
        // Stuck against something the route did not know about: ask MOVE to plan again.
        if (lastPos != null && lastPos.distanceTo(pos) < 0.02) {
            if (++stuckTicks > 15) {
                replan = true;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastPos = pos;
        return false;
    }

    // ================================================================== path finding

    private static final int AIR_MAX_NODES = 25000;
    private static final int DIG_MAX_NODES = 25000;
    /** Extra cost of going through one of our own blocks (it has to be broken, then rebuilt). */
    private static final int DIG_COST = 8;

    /**
     * Sets `route` to `current.stand`. Straight if the line is clear; otherwise an A* path through the
     * air, round walls and out through doors and gaps. If the player is shut in (under a roof it just
     * built, say) and there is no way round, the path goes through the fewest blocks: the first one is
     * broken (current becomes that break; a build block broken this way is rebuilt later).
     *
     * @return false if the stand cannot be reached at all
     */
    private boolean routeTo(Minecraft mc, LocalPlayer player, ClientLevel level) {
        Vec3 goal = current.stand;
        Vec3 from = player.position();
        if (clearLine(level, from, goal)) {
            route = new ArrayList<>(List.of(goal));
            return true;
        }
        List<BlockPos> cells = findPath(level, player, goal);
        if (cells == null) return false;
        for (int k = 0; k < cells.size(); k++) {
            BlockPos c = cells.get(k);
            BlockPos solid = !free(level, c) ? c : !free(level, c.above()) ? c.above() : null;
            if (solid == null) continue;
            // Dig through: stand in the last free cell before it, break it, the rest is planned after.
            BlockPos prev = k > 0 ? cells.get(k - 1) : BlockPos.containing(from);
            Action dig = new Action(Kind.BREAK, solid, null, false);
            Direction face = Direction.getApproximateNearest(prev.getX() - c.getX(), prev.getY() - c.getY(),
                    prev.getZ() - c.getZ());
            dig.against = solid;
            dig.face = face;
            dig.hit = Vec3.atCenterOf(solid).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            dig.stand = feetAt(prev);
            dig.dig = true;
            StartBuildMod.LOGGER.info("[StartBuild] shut in: breaking {} at {} to get out", name(level.getBlockState(solid)), solid);
            current = dig;
            route = smooth(level, from, cells.subList(0, k), dig.stand);
            return true;
        }
        route = smooth(level, from, cells, goal);
        return true;
    }

    /** The player fits in a cell pair (feet, head) with no collision. */
    private static boolean free(ClientLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty();
    }

    /**
     * A block the player may break to get out: only this stage's own cells (build blocks are rebuilt,
     * clearing cells are going anyway) and temporary blocks - never the landscape, new earth from an
     * earlier stage, or anything else nothing would put back.
     */
    private boolean diggable(ClientLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!s.getFluidState().isEmpty() || s.getDestroySpeed(level, p) < 0) return false;
        if (scaffolds.contains(p)) return true;
        int i = indexOf(p);
        return i >= 0 && status[i] != 0;
    }

    private static Vec3 feetAt(BlockPos c) {
        return new Vec3(c.getX() + 0.5, c.getY() + 0.02, c.getZ() + 0.5);
    }

    /**
     * A* over feet cells (the player is one cell wide, two tall): first through the air only, and only if
     * that finds nothing, through this stage's own blocks as well. Null if unreachable.
     */
    private List<BlockPos> findPath(ClientLevel level, LocalPlayer player, Vec3 goal) {
        BlockPos start = BlockPos.containing(player.position());
        BlockPos end = BlockPos.containing(goal);
        int[] box = {
                Math.min(start.getX(), end.getX()) - 40,
                Math.max(level.getMinY(), Math.min(start.getY(), end.getY()) - 10),
                Math.min(start.getZ(), end.getZ()) - 40,
                Math.max(start.getX(), end.getX()) + 40,
                Math.min(level.getMaxY() - 2, Math.max(Math.max(start.getY(), end.getY()), highestBuiltY) + 20),
                Math.max(start.getZ(), end.getZ()) + 40};
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Close enough: from here a short straight hop reaches the exact stand.
        FlightPath.Goal reached = (x, y, z) -> Math.abs(x - end.getX()) + Math.abs(y - end.getY())
                + Math.abs(z - end.getZ()) <= 1 && clearLine(level, feetAt(new BlockPos(x, y, z)), goal);
        List<int[]> cells = FlightPath.find(start.getX(), start.getY(), start.getZ(), end.getX(), end.getY(), end.getZ(),
                box, (x, y, z) -> free(level, m.set(x, y, z)) ? 0 : FlightPath.BLOCKED, reached, AIR_MAX_NODES);
        if (cells == null) {
            cells = FlightPath.find(start.getX(), start.getY(), start.getZ(), end.getX(), end.getY(), end.getZ(), box,
                    (x, y, z) -> {
                        m.set(x, y, z);
                        if (free(level, m)) return 0;
                        return diggable(level, m.immutable()) ? DIG_COST : FlightPath.BLOCKED;
                    }, reached, DIG_MAX_NODES);
        }
        if (cells == null) return null;
        List<BlockPos> out = new ArrayList<>(cells.size());
        for (int[] c : cells) out.add(new BlockPos(c[0], c[1], c[2]));
        return out;
    }

    /** Cell path -> few waypoints: from each point, jump to the furthest one in a clear straight line. */
    private List<Vec3> smooth(ClientLevel level, Vec3 from, List<BlockPos> cells, Vec3 goal) {
        List<Vec3> pts = new ArrayList<>();
        for (BlockPos c : cells) pts.add(feetAt(c));
        pts.add(goal);
        List<Vec3> out = new ArrayList<>();
        Vec3 at = from;
        int i = 0;
        while (i < pts.size()) {
            int best = i;
            for (int j = Math.min(pts.size() - 1, i + 24); j > i; j--) {
                if (clearLine(level, at, pts.get(j))) {
                    best = j;
                    break;
                }
            }
            at = pts.get(best);
            out.add(at);
            i = best + 1;
        }
        return out;
    }

    private boolean clearLine(ClientLevel level, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        int steps = (int) Math.ceil(len / 0.4);
        Minecraft mc = Minecraft.getInstance();
        for (int s = 1; s <= steps; s++) {
            Vec3 p = a.add(d.scale(s / (double) steps));
            AABB box = new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 1.8, p.z + 0.3);
            if (!level.noCollision(mc.player, box)) return false;
        }
        return true;
    }

    // ================================================================== looking

    /** @return true when the view is on target. */
    private boolean aim(LocalPlayer player, Vec3 hit) {
        float[] rot = lookAngles(player.getEyePosition(), hit);
        return turnTowards(player, rot[0], rot[1]);
    }

    private boolean turnTowards(LocalPlayer player, float yaw, float pitch) {
        float cy = player.getYRot();
        float cp = player.getXRot();
        float dy = Mth.wrapDegrees(yaw - cy);
        float dp = pitch - cp;
        float sy = Mth.clamp(dy, -MAX_YAW_STEP, MAX_YAW_STEP);
        float sp = Mth.clamp(dp, -MAX_PITCH_STEP, MAX_PITCH_STEP);
        player.setYRot(cy + sy);
        player.setXRot(Mth.clamp(cp + sp, -90f, 90f));
        player.setYHeadRot(player.getYRot());
        return Math.abs(dy) < 3f && Math.abs(dp) < 3f;
    }

    private static float[] lookAngles(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        float pitch = (float) -(Mth.atan2(dy, h) * (180.0 / Math.PI));
        return new float[]{Mth.wrapDegrees(yaw), pitch};
    }

    /** The face turned most towards the player, preferring faces that are open to the air (what you can see). */
    private Direction bestVisibleFace(ClientLevel level, LocalPlayer player, BlockPos p) {
        Vec3 eye = player.getEyePosition();
        Vec3 c = Vec3.atCenterOf(p);
        Direction best = Direction.UP;
        double bestDot = -99;
        Vec3 to = eye.subtract(c).normalize();
        for (Direction d : Direction.values()) {
            double dot = d.getStepX() * to.x + d.getStepY() * to.y + d.getStepZ() * to.z;
            if (!level.getBlockState(p.relative(d)).canBeReplaced()) dot -= 3;   // covered face
            if (dot > bestDot) {
                bestDot = dot;
                best = d;
            }
        }
        return best;
    }

    // ================================================================== helpers

    private boolean stillValid(ClientLevel level, Action a) {
        if (a == null) return false;
        if (a.kind == Kind.BREAK) return !level.getBlockState(a.target).isAir();
        if (a.kind == Kind.USE || a.kind == Kind.USE_AIR) return !matches(level.getBlockState(a.target), a.want);
        return level.getBlockState(a.target).canBeReplaced() && isClickable(level.getBlockState(a.against));
    }

    private static boolean isClickable(BlockState s) {
        if (s.isAir() || s.canBeReplaced()) return false;
        Block b = s.getBlock();
        // Clicking these would open a screen or toggle them instead of placing against them.
        if (b instanceof BaseEntityBlock) return false;
        String n = b.getClass().getSimpleName();
        return !(n.contains("Door") || n.contains("Gate") || n.contains("Button") || n.contains("Lever")
                || n.contains("Table") || n.contains("Bed") || n.contains("Anvil") || n.contains("Note")
                || n.contains("Repeater") || n.contains("Comparator") || n.contains("Cake") || n.contains("Bell"));
    }

    private boolean isSolid(ClientLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isAir() && !s.canBeReplaced();
    }

    private boolean hasSolidNeighbour(ClientLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (isClickable(level.getBlockState(p.relative(d)))) return true;
        }
        return false;
    }

    private boolean hasSolidNeighbourExcept(ClientLevel level, BlockPos p, BlockPos except) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (n.equals(except)) continue;
            if (isClickable(level.getBlockState(n))) return true;
        }
        return false;
    }

    /**
     * Same block and same properties, except the ones the game recomputes from the neighbours after
     * placement (fence/wall/pane/mushroom connections, stair corner shape) - those settle by themselves
     * once the neighbours are built, so demanding them at click time would be impossible.
     */
    static boolean matches(BlockState have, BlockState want) {
        if (have == null || want == null) return false;
        if (have.equals(want)) return true;
        if (have.getBlock() != want.getBlock()) return false;
        Block b = want.getBlock();
        boolean connecting = b instanceof HugeMushroomBlock || b instanceof CrossCollisionBlock
                || b instanceof WallBlock || b instanceof PipeBlock;
        for (Property<?> p : want.getProperties()) {
            if (!have.hasProperty(p)) return false;
            if (have.getValue(p).equals(want.getValue(p))) continue;
            String n = p.getName();
            if (connecting && (n.equals("north") || n.equals("south") || n.equals("east") || n.equals("west")
                    || n.equals("up") || n.equals("down"))) continue;
            if (b instanceof StairBlock && n.equals("shape")) continue;
            // Worked out by the game from the surroundings when placed: leaf distance to a log, grass
            // "snowy" from the block above. A schematic's value cannot be clicked into place.
            if (n.equals("waterlogged") || n.equals("moisture") || n.equals("distance") || n.equals("snowy")) continue;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ two-step blocks

    private static boolean isWater(BlockState s) {
        return s.getBlock() == Blocks.WATER;
    }

    /** What the first click puts down for `want` (dirt before farmland, an empty pot before a potted plant). */
    static BlockState placeState(BlockState want) {
        Block b = want.getBlock();
        if (b == Blocks.FARMLAND || b == Blocks.DIRT_PATH) return Blocks.DIRT.defaultBlockState();
        if (b instanceof FlowerPotBlock && b != Blocks.FLOWER_POT) return Blocks.FLOWER_POT.defaultBlockState();
        if (b instanceof CropBlock) return b.defaultBlockState();
        return want;
    }

    private static Item placeItem(BlockState want) {
        return placeState(want).getBlock().asItem();
    }

    /**
     * The item to USE on a cell that already holds the first step: hoe on dirt (farmland), shovel on dirt
     * (path), the plant on an empty pot, bone meal on a young crop, a bucket for water. Null otherwise.
     */
    private static Item specialUse(BlockState have, BlockState want) {
        Block wb = want.getBlock();
        Block hb = have.getBlock();
        // Water: a bucket into air, or into water that is only flowing there (a source is wanted).
        if (isWater(want)) {
            if (!have.getFluidState().isEmpty()) return have.getFluidState().isSource() ? null : Items.WATER_BUCKET;
            return have.isAir() || have.canBeReplaced() ? Items.WATER_BUCKET : null;
        }
        if (wb == Blocks.NETHER_PORTAL) return have.isAir() ? Items.FLINT_AND_STEEL : null;   // light the frame
        if ((hb == Blocks.DIRT || hb == Blocks.GRASS_BLOCK) && wb == Blocks.FARMLAND) return Items.WOODEN_HOE;
        if ((hb == Blocks.DIRT || hb == Blocks.GRASS_BLOCK) && wb == Blocks.DIRT_PATH) return Items.WOODEN_SHOVEL;
        if (hb == Blocks.FLOWER_POT && wb instanceof FlowerPotBlock pot && wb != Blocks.FLOWER_POT) {
            Item plant = pot.getPotted().asItem();
            return (plant == Items.AIR) ? null : plant;
        }
        if (hb == wb && wb instanceof CropBlock && !have.equals(want)) return Items.BONE_MEAL;
        return null;
    }

    /** The upper half of a door/tall plant is placed together with its lower half. */
    private static boolean isSecondaryPart(BlockState s) {
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        return s.hasProperty(BlockStateProperties.BED_PART)
                && s.getValue(BlockStateProperties.BED_PART).toString().equalsIgnoreCase("head");
    }

    private void markDone(int i) {
        if (i >= 0 && status[i] != 2) {
            if (status[i] == 1 || status[i] == 3) remaining--;
            status[i] = 2;
        }
    }

    private void park(Action a, String why) {
        lastProblem = why + " (" + (a == null ? "?" : a.target) + ")";
        if (a != null && a.scaffold) {
            if (a.kind == Kind.BREAK) scaffoldRetryAt.put(a.target, ticks + 1200);
            if (a.kind == Kind.PLACE) {
                // The support cannot be built: set the cell it was for aside too, otherwise the same
                // sequence is planned again at once - place one support, break it, repeat for ever (each
                // step counts as progress, so the watchdog would never notice).
                if (a.forCell >= 0 && status[a.forCell] == 1) status[a.forCell] = 3;
                queue.removeIf(q -> !q.scaffold || q.kind == Kind.PLACE);   // keep only the clean-up breaks
            }
            return;
        }
        if (a == null) return;
        int i = indexOf(a.target);
        if (i >= 0 && status[i] == 1) status[i] = 3;
    }

    private void impossible(Action a, String why) {
        lastProblem = why;
        StartBuildMod.LOGGER.warn("[StartBuild] cannot place {}: {}", a.target, why);
        int i = indexOf(a.target);
        if (i >= 0 && status[i] != 2) {
            if (status[i] == 1 || status[i] == 3) remaining--;
            status[i] = 4;
        }
    }

    private BlockPos world(int x, int y, int z) {
        return new BlockPos(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
    }

    private BlockPos worldOf(int i) {
        int y = i / (model.sizeX * model.sizeZ);
        int rem = i % (model.sizeX * model.sizeZ);
        return world(rem % model.sizeX, y, rem / model.sizeX);
    }

    private int indexOf(BlockPos p) {
        int x = p.getX() - origin.getX(), y = p.getY() - origin.getY(), z = p.getZ() - origin.getZ();
        return model.inside(x, y, z) ? model.index(x, y, z) : -1;
    }

    private AABB playerBox() {
        LocalPlayer p = Minecraft.getInstance().player;
        return (p == null) ? new AABB(0, 0, 0, 0, 0, 0) : p.getBoundingBox();
    }

    private static String name(BlockState s) {
        return (s == null) ? "?" : s.getBlock().toString();
    }

    // ================================================================== watchdog hooks

    /** Forget every plan and retry everything that is left from scratch (called when no progress for minutes). */
    void recover() {
        queue.clear();
        current = null;
        route = new ArrayList<>();
        scaffoldRetryAt.clear();
        attempts.clear();
        unpark();
        layer = 0;
        parkedPasses = 0;
        replan = false;
        enter(Phase.PLAN);
        lastProgressTick = ticks;       // give the fresh start its own time
    }

    /** Free air above the middle of this stage's work: somewhere the player can always be put. */
    BlockPos escapePoint() {
        return origin.offset(model.sizeX / 2, model.sizeY + 3, model.sizeZ / 2);
    }

    /** Stop for good; what is left is reported by the caller. */
    void giveUp() {
        finished = true;
        queue.clear();
        current = null;
    }

    /** Re-checks the whole build against the world and reopens anything that is not right. */
    int recheckAll(ClientLevel level) {
        int reopened = 0;
        for (int i = 0; i < status.length; i++) {
            // Done cells that are wrong now, and parked ones (e.g. a block broken to get out of an enclosed
            // space late in the run) get another go.
            if (status[i] == 3) {
                status[i] = 1;
                reopened++;
                continue;
            }
            if (status[i] != 2) continue;
            BlockPos p = worldOf(i);
            if (!matches(level.getBlockState(p), model.states[i])) {
                status[i] = 1;
                remaining++;
                reopened++;
            }
        }
        if (reopened > 0) {
            layer = 0;
            finished = false;
            parkedPasses = 0;
        }
        return reopened;
    }

    /** Counts cells that do not match the schematic right now. */
    int countWrong(ClientLevel level) {
        int wrong = 0;
        for (int i = 0; i < status.length; i++) {
            if (status[i] == 0) continue;
            if (!matches(level.getBlockState(worldOf(i)), model.states[i])) wrong++;
        }
        return wrong;
    }
}
