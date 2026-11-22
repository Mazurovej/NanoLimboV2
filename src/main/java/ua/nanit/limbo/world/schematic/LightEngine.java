/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo.world.schematic;

import lombok.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.*;

/**
 * Memory-efficient BFS sky light and block light engine for a {@link SchematicWorld}.
 * Uses a primitive int ring buffer (zero boxing) and releases temporary 3D buffers immediately
 * after extracting non-empty 2048-byte section nibble arrays.
 */
public final class LightEngine {

    private static final int MAX_BFS_VOLUME = 8_000_000;

    private static final Map<String, Integer> BLOCK_LIGHT_LEVELS = new HashMap<>();
    private static final Set<String> TRANSPARENT_BLOCKS = new HashSet<>();

    static {
        BLOCK_LIGHT_LEVELS.put("minecraft:beacon", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:torch", 14);
        BLOCK_LIGHT_LEVELS.put("minecraft:wall_torch", 14);
        BLOCK_LIGHT_LEVELS.put("minecraft:soul_torch", 10);
        BLOCK_LIGHT_LEVELS.put("minecraft:soul_wall_torch", 10);
        BLOCK_LIGHT_LEVELS.put("minecraft:redstone_torch", 7);
        BLOCK_LIGHT_LEVELS.put("minecraft:redstone_wall_torch", 7);
        BLOCK_LIGHT_LEVELS.put("minecraft:sea_lantern", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:end_rod", 14);
        BLOCK_LIGHT_LEVELS.put("minecraft:fire", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:soul_fire", 10);
        BLOCK_LIGHT_LEVELS.put("minecraft:lava", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:lantern", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:soul_lantern", 10);
        BLOCK_LIGHT_LEVELS.put("minecraft:glowstone", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:shroomlight", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:jack_o_lantern", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:campfire", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:soul_campfire", 10);
        BLOCK_LIGHT_LEVELS.put("minecraft:ochre_froglight", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:pearlescent_froglight", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:verdant_froglight", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:conduit", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:end_portal", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:end_gateway", 15);
        BLOCK_LIGHT_LEVELS.put("minecraft:nether_portal", 11);
        BLOCK_LIGHT_LEVELS.put("minecraft:crying_obsidian", 10);
        BLOCK_LIGHT_LEVELS.put("minecraft:ender_chest", 7);
        BLOCK_LIGHT_LEVELS.put("minecraft:enchanting_table", 7);
        BLOCK_LIGHT_LEVELS.put("minecraft:amethyst_cluster", 5);
        BLOCK_LIGHT_LEVELS.put("minecraft:magma_block", 3);
        BLOCK_LIGHT_LEVELS.put("minecraft:light", 15);

        TRANSPARENT_BLOCKS.add("minecraft:air");
        TRANSPARENT_BLOCKS.add("minecraft:cave_air");
        TRANSPARENT_BLOCKS.add("minecraft:void_air");
        TRANSPARENT_BLOCKS.add("minecraft:glass");
        TRANSPARENT_BLOCKS.add("minecraft:glass_pane");
        TRANSPARENT_BLOCKS.add("minecraft:barrier");
        TRANSPARENT_BLOCKS.add("minecraft:torch");
        TRANSPARENT_BLOCKS.add("minecraft:wall_torch");
        TRANSPARENT_BLOCKS.add("minecraft:soul_torch");
        TRANSPARENT_BLOCKS.add("minecraft:soul_wall_torch");
        TRANSPARENT_BLOCKS.add("minecraft:lantern");
        TRANSPARENT_BLOCKS.add("minecraft:soul_lantern");
        TRANSPARENT_BLOCKS.add("minecraft:end_rod");
        TRANSPARENT_BLOCKS.add("minecraft:iron_bars");
        TRANSPARENT_BLOCKS.add("minecraft:chain");
    }

    private final SchematicWorld world;
    private final boolean hasSkyLight;

    // Compact sparse storage of pre-packed 2048-byte light sections
    private final Map<Long, byte[]> skyLightSections = new HashMap<>();
    private final Map<Long, byte[]> blockLightSections = new HashMap<>();

    public LightEngine(@NonNull SchematicWorld world, boolean hasSkyLight) {
        this.world = world;
        this.hasSkyLight = hasSkyLight;
    }

    public void compute() {
        if (world.isEmpty()) {
            return;
        }

        int minChunkX = world.getMinX() >> 4;
        int maxChunkX = world.getMaxX() >> 4;
        int minChunkZ = world.getMinZ() >> 4;
        int maxChunkZ = world.getMaxZ() >> 4;
        int minSectionY = world.getMinY() >> 4;
        int maxSectionY = world.getMaxY() >> 4;

        int originX = minChunkX << 4;
        int originY = minSectionY << 4;
        int originZ = minChunkZ << 4;
        int sizeX = (maxChunkX - minChunkX + 1) << 4;
        int sizeY = (maxSectionY - minSectionY + 1) << 4;
        int sizeZ = (maxChunkZ - minChunkZ + 1) << 4;

        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume <= 0 || volume > MAX_BFS_VOLUME) {
            // Very large schematic: avoid giant 3D arrays and fill populated chunks with full skylight
            if (hasSkyLight) {
                byte[] fullLight = new byte[2048];
                Arrays.fill(fullLight, (byte) 0xFF);
                for (SchematicWorld.ChunkPos pos : world.getChunks().keySet()) {
                    for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                        skyLightSections.put(sectionKey(pos.x(), pos.z(), sy), fullLight);
                    }
                }
            }
            return;
        }

        int total = (int) volume;

        // Compute block light, extract sections, then immediately free the 3D array
        byte[] blockLight = new byte[total];
        computeBlockLight(blockLight, originX, originY, originZ, sizeX, sizeY, sizeZ);
        extractAllSections(blockLight, blockLightSections, minChunkX, maxChunkX, minChunkZ, maxChunkZ, minSectionY, maxSectionY, sizeX, sizeZ);
        blockLight = null;

        // Compute sky light, extract sections, then immediately free the 3D array
        if (hasSkyLight) {
            byte[] skyLight = new byte[total];
            computeSkyLight(skyLight, originX, originY, originZ, sizeX, sizeY, sizeZ);
            extractAllSections(skyLight, skyLightSections, minChunkX, maxChunkX, minChunkZ, maxChunkZ, minSectionY, maxSectionY, sizeX, sizeZ);
        }
    }

    private void computeBlockLight(byte[] blockLight,
                                   int originX, int originY, int originZ,
                                   int sizeX, int sizeY, int sizeZ) {
        IntRingQueue queue = new IntRingQueue(4096);

        for (int x = world.getMinX(); x <= world.getMaxX(); x++) {
            for (int y = world.getMinY(); y <= world.getMaxY(); y++) {
                for (int z = world.getMinZ(); z <= world.getMaxZ(); z++) {
                    String block = world.getBlock(x, y, z);
                    int level = getBlockLightEmission(block);
                    if (level > 0) {
                        int idx = index(x - originX, y - originY, z - originZ, sizeX, sizeZ);
                        if (level > (blockLight[idx] & 0xFF)) {
                            blockLight[idx] = (byte) level;
                            queue.push(idx);
                        }
                    }
                }
            }
        }

        propagateBfs(blockLight, queue, originX, originY, originZ, sizeX, sizeY, sizeZ);
    }

    private void computeSkyLight(byte[] skyLight,
                                 int originX, int originY, int originZ,
                                 int sizeX, int sizeY, int sizeZ) {
        IntRingQueue queue = new IntRingQueue(4096);

        for (int lx = 0; lx < sizeX; lx++) {
            int wx = originX + lx;
            for (int lz = 0; lz < sizeZ; lz++) {
                int wz = originZ + lz;
                for (int ly = sizeY - 1; ly >= 0; ly--) {
                    int wy = originY + ly;
                    String block = world.getBlock(wx, wy, wz);
                    if (!isLightTransparent(block)) {
                        break;
                    }
                    int idx = index(lx, ly, lz, sizeX, sizeZ);
                    skyLight[idx] = 15;
                    queue.push(idx);
                }
            }
        }

        propagateBfs(skyLight, queue, originX, originY, originZ, sizeX, sizeY, sizeZ);
    }

    private void propagateBfs(byte[] lightArray,
                              IntRingQueue queue,
                              int originX, int originY, int originZ,
                              int sizeX, int sizeY, int sizeZ) {
        final int[] dx = {1, -1, 0, 0, 0, 0};
        final int[] dy = {0, 0, 1, -1, 0, 0};
        final int[] dz = {0, 0, 0, 0, 1, -1};

        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int level = lightArray[idx] & 0xFF;
            if (level <= 1) {
                continue;
            }
            int nextLevel = level - 1;

            int lx = idx % sizeX;
            int rem = idx / sizeX;
            int lz = rem % sizeZ;
            int ly = rem / sizeZ;

            for (int d = 0; d < 6; d++) {
                int nx = lx + dx[d];
                int ny = ly + dy[d];
                int nz = lz + dz[d];
                if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) {
                    continue;
                }
                int nIdx = index(nx, ny, nz, sizeX, sizeZ);
                if ((lightArray[nIdx] & 0xFF) >= nextLevel) {
                    continue;
                }
                String neighbor = world.getBlock(originX + nx, originY + ny, originZ + nz);
                if (!isLightTransparent(neighbor)) {
                    continue;
                }
                lightArray[nIdx] = (byte) nextLevel;
                queue.push(nIdx);
            }
        }
    }

    private static void extractAllSections(byte[] lightArray,
                                           Map<Long, byte[]> target,
                                           int minChunkX, int maxChunkX,
                                           int minChunkZ, int maxChunkZ,
                                           int minSectionY, int maxSectionY,
                                           int sizeX, int sizeZ) {
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            int baseX = (cx - minChunkX) << 4;
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                int baseZ = (cz - minChunkZ) << 4;
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    int baseY = (sy - minSectionY) << 4;
                    byte[] data = new byte[2048];
                    boolean nonZero = false;
                    int outIdx = 0;

                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            int rowBase = index(baseX, baseY + y, baseZ + z, sizeX, sizeZ);
                            for (int x = 0; x < 16; x += 2) {
                                int even = lightArray[rowBase + x] & 0x0F;
                                int odd = lightArray[rowBase + x + 1] & 0x0F;
                                byte packed = (byte) ((odd << 4) | even);
                                data[outIdx++] = packed;
                                if (packed != 0) {
                                    nonZero = true;
                                }
                            }
                        }
                    }

                    if (nonZero) {
                        target.put(sectionKey(cx, cz, sy), data);
                    }
                }
            }
        }
    }

    private static int index(int lx, int ly, int lz, int sizeX, int sizeZ) {
        return (ly * sizeZ + lz) * sizeX + lx;
    }

    private static long sectionKey(int chunkX, int chunkZ, int sectionY) {
        return (((long) chunkX & 0x3FFFFFL) << 42)
                | (((long) chunkZ & 0x3FFFFFL) << 20)
                | ((long) sectionY & 0xFFFFFL);
    }

    public byte @Nullable [] getSkyLightSection(int chunkX, int chunkZ, int sectionY) {
        if (!hasSkyLight) {
            return null;
        }
        return skyLightSections.get(sectionKey(chunkX, chunkZ, sectionY));
    }

    public byte @Nullable [] getBlockLightSection(int chunkX, int chunkZ, int sectionY) {
        return blockLightSections.get(sectionKey(chunkX, chunkZ, sectionY));
    }

    public boolean hasLightForChunk(int chunkX, int chunkZ, int minSectionY, int lightSections) {
        for (int i = 0; i < lightSections; i++) {
            long key = sectionKey(chunkX, chunkZ, minSectionY - 1 + i);
            if (skyLightSections.containsKey(key) || blockLightSections.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static int getBlockLightEmission(@NonNull String blockState) {
        int bracket = blockState.indexOf('[');
        String name = bracket < 0 ? blockState : blockState.substring(0, bracket);
        if (!name.startsWith("minecraft:")) {
            name = "minecraft:" + name;
        }
        if (name.equals("minecraft:redstone_lamp") || name.endsWith("_copper_bulb")) {
            return blockState.contains("lit=true") ? 15 : 0;
        }
        if (name.equals("minecraft:campfire") || name.equals("minecraft:soul_campfire")) {
            if (blockState.contains("lit=false")) {
                return 0;
            }
        }
        return BLOCK_LIGHT_LEVELS.getOrDefault(name, 0);
    }

    private static boolean isLightTransparent(@NonNull String blockState) {
        if (SchematicWorld.isAir(blockState)) {
            return true;
        }
        int bracket = blockState.indexOf('[');
        String name = bracket < 0 ? blockState : blockState.substring(0, bracket);
        if (!name.startsWith("minecraft:")) {
            name = "minecraft:" + name;
        }
        return TRANSPARENT_BLOCKS.contains(name)
                || name.endsWith("_glass")
                || name.endsWith("_glass_pane")
                || name.endsWith("_leaves")
                || name.endsWith("_fence")
                || name.endsWith("_fence_gate")
                || name.endsWith("_slab")
                || name.endsWith("_stairs")
                || name.endsWith("_carpet")
                || name.endsWith("_sign")
                || name.endsWith("_wall_sign")
                || name.endsWith("_banner")
                || name.endsWith("_trapdoor")
                || name.endsWith("_door");
    }

    private static final class IntRingQueue {
        private int[] data;
        private int head;
        private int tail;
        private int size;

        IntRingQueue(int initialCapacity) {
            this.data = new int[Integer.highestOneBit(Math.max(16, initialCapacity - 1)) << 1];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(int value) {
            if (size == data.length) {
                grow();
            }
            data[tail] = value;
            tail = (tail + 1) & (data.length - 1);
            size++;
        }

        int poll() {
            int value = data[head];
            head = (head + 1) & (data.length - 1);
            size--;
            return value;
        }

        private void grow() {
            int[] next = new int[data.length << 1];
            int firstPart = data.length - head;
            System.arraycopy(data, head, next, 0, firstPart);
            if (head > 0) {
                System.arraycopy(data, 0, next, firstPart, head);
            }
            data = next;
            head = 0;
            tail = size;
        }
    }
}
