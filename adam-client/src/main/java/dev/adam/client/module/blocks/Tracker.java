package dev.adam.client.module.blocks;

import dev.adam.client.config.AdamConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class Tracker {
    public static final class Cluster {
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public int total;
        public int color;
        public int fill;
        public Map<Block, Integer> counts = new LinkedHashMap<>();
    }

    private static final int MAX_HITS = 12000;
    public static volatile List<Cluster> clusters = List.of();
    public static volatile boolean dirty = true;
    private static int ticks = 0;

    public static Block blockOf(String id) {
        Identifier i = Identifier.tryParse(id);
        if (i == null || !BuiltInRegistries.BLOCK.containsKey(i)) return null;
        return BuiltInRegistries.BLOCK.getValue(i);
    }

    public static void clear() {
        clusters = List.of();
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            clusters = List.of();
            return;
        }
        ticks++;
        if (!dirty && ticks % 20 != 0) return;
        dirty = false;
        scan(level, player);
    }

    private static void scan(ClientLevel level, LocalPlayer player) {
        Map<Block, AdamConfig.BlockEntry> tracked = new HashMap<>();
        for (AdamConfig.BlockEntry e : AdamConfig.get().blocks) {
            if (!e.enabled) continue;
            Block b = blockOf(e.id);
            if (b != null) tracked.put(b, e);
        }
        if (tracked.isEmpty()) {
            clusters = List.of();
            return;
        }
        Predicate<BlockState> pred = s -> tracked.containsKey(s.getBlock());

        BlockPos pp = player.blockPosition();
        int r = BlocksModule.INSTANCE.range();
        int minCX = (pp.getX() - r) >> 4, maxCX = (pp.getX() + r) >> 4;
        int minCZ = (pp.getZ() - r) >> 4, maxCZ = (pp.getZ() + r) >> 4;

        List<int[]> hits = new ArrayList<>();
        List<Block> hitBlocks = new ArrayList<>();

        outer:
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || !section.maybeHas(pred)) continue;
                    int baseY = (chunk.getMinSectionY() + i) << 4;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                Block b = section.getBlockState(x, y, z).getBlock();
                                if (!tracked.containsKey(b)) continue;
                                int wx = (cx << 4) + x, wy = baseY + y, wz = (cz << 4) + z;
                                if (Math.abs(wx - pp.getX()) > r || Math.abs(wz - pp.getZ()) > r) continue;
                                hits.add(new int[]{wx, wy, wz});
                                hitBlocks.add(b);
                                if (hits.size() >= MAX_HITS) break outer;
                            }
                        }
                    }
                }
            }
        }

        int n = hits.size();
        int[] parent = new int[n];
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            int[] h = hits.get(i);
            index.put(BlockPos.asLong(h[0], h[1], h[2]), i);
        }
        int d = Math.max(0, Math.min(3, BlocksModule.INSTANCE.groupDistance()));
        for (int i = 0; i < n; i++) {
            int[] h = hits.get(i);
            for (int dx = -d; dx <= d; dx++) {
                for (int dy = -d; dy <= d; dy++) {
                    for (int dz = -d; dz <= d; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Integer j = index.get(BlockPos.asLong(h[0] + dx, h[1] + dy, h[2] + dz));
                        if (j != null) union(parent, i, j);
                    }
                }
            }
        }

        Map<Integer, Cluster> byRoot = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            int[] h = hits.get(i);
            Cluster c = byRoot.get(root);
            if (c == null) {
                c = new Cluster();
                c.minX = c.maxX = h[0];
                c.minY = c.maxY = h[1];
                c.minZ = c.maxZ = h[2];
                byRoot.put(root, c);
            } else {
                c.minX = Math.min(c.minX, h[0]);
                c.minY = Math.min(c.minY, h[1]);
                c.minZ = Math.min(c.minZ, h[2]);
                c.maxX = Math.max(c.maxX, h[0]);
                c.maxY = Math.max(c.maxY, h[1]);
                c.maxZ = Math.max(c.maxZ, h[2]);
            }
            c.total++;
            c.counts.merge(hitBlocks.get(i), 1, Integer::sum);
        }

        List<Cluster> result = new ArrayList<>(byRoot.values());
        for (Cluster c : result) {
            List<Map.Entry<Block, Integer>> sorted = new ArrayList<>(c.counts.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            Map<Block, Integer> ordered = new LinkedHashMap<>();
            for (Map.Entry<Block, Integer> e : sorted) ordered.put(e.getKey(), e.getValue());
            c.counts = ordered;
            AdamConfig.BlockEntry top = tracked.get(sorted.get(0).getKey());
            c.color = top.color;
            c.fill = top.fill;
        }
        clusters = result;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }
}
