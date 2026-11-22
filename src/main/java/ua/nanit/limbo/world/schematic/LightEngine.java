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
 * BFS-based sky light and block light engine for a {@link SchematicWorld}.
 */
public final class LightEngine {

    private static final Map<String, Integer> BLOCK_LIGHT_LEVELS = new HashMap<>();
    private static final Set<String> TRANSPARENT_BLOCKS = new HashSet<>();

    static {
        // Light-emitting blocks (based on vanilla Minecraft & LOOHP Limbo)
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

    // Padded bounding box aligned to chunk boundaries
    private int originX;
    private int originY;
    private int originZ;
    private int sizeX;
    private int sizeY;
    private int sizeZ;

    private byte[] skyLight;
    private byte[] blockLight;

    public LightEngine(@NonNull SchematicWorld world, boolean hasSkyLight) {
        this.world = world;
        this.hasSkyLight = hasSkyLight;
    }

    public void compute() {
        if (world.isEmpty()) {
            return;
        }

        // Pad by 1 chunk (16 blocks) in all directions so light spreads smoothly at borders
        int minChunkX = (world.getMinX() >> 4) - 1;
        int maxChunkX = (world.getMaxX() >> 4) + 1;
        int minChunkZ = (world.getMinZ() >> 4) - 1;
        int maxChunkZ = (world.getMaxZ() >> 4) + 1;
        int minSectionY = (world.getMinY() >> 4) - 1;
        int maxSectionY = (world.getMaxY() >> 4) + 1;

        this.originX = minChunkX << 4;
        this.originY = minSectionY << 4;
        this.originZ = minChunkZ << 4;
        this.sizeX = (maxChunkX - minChunkX + 1) << 4;
        this.sizeY = (maxSectionY - minSectionY + 1) << 4;
        this.sizeZ = (maxChunkZ - minChunkZ + 1) << 4;

        int total = sizeX * sizeY * sizeZ;
        this.blockLight = new byte[total];
        if (hasSkyLight) {
            this.skyLight = new byte[total];
        }

        computeBlockLight();
        if (hasSkyLight) {
            computeSkyLight();
        }
    }

    private void computeBlockLight() {
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int x = world.getMinX(); x <= world.getMaxX(); x++) {
            for (int y = world.getMinY(); y <= world.getMaxY(); y++) {
                for (int z = world.getMinZ(); z <= world.getMaxZ(); z++) {
                    String block = world.getBlock(x, y, z);
                    int level = getBlockLightEmission(block);
                    if (level > 0) {
                        int idx = index(x - originX, y - originY, z - originZ);
                        if (level > blockLight[idx]) {
                            blockLight[idx] = (byte) level;
                            queue.add(idx);
                        }
                    }
                }
            }
        }

        propagateBfs(blockLight, queue);
    }

    private void computeSkyLight() {
        ArrayDeque<Integer> queue = new ArrayDeque<>();

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
                    int idx = index(lx, ly, lz);
                    skyLight[idx] = 15;
                    queue.add(idx);
                }
            }
        }

        propagateBfs(skyLight, queue);
    }

    private void propagateBfs(byte[] lightArray, ArrayDeque<Integer> queue) {
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
                int nIdx = index(nx, ny, nz);
                if ((lightArray[nIdx] & 0xFF) >= nextLevel) {
                    continue;
                }
                String neighbor = world.getBlock(originX + nx, originY + ny, originZ + nz);
                if (!isLightTransparent(neighbor)) {
                    continue;
                }
                lightArray[nIdx] = (byte) nextLevel;
                queue.add(nIdx);
            }
        }
    }

    private int index(int lx, int ly, int lz) {
        return (ly * sizeZ + lz) * sizeX + lx;
    }

    /**
     * Returns the 2048-byte packed nibble array for the given chunk section's sky light,
     * or {@code null} if all blocks in that section have light level 0.
     */
    public byte @Nullable [] getSkyLightSection(int chunkX, int chunkZ, int sectionY) {
        if (!hasSkyLight) {
            return null;
        }
        return extractSection(skyLight, chunkX, chunkZ, sectionY, (byte) 15);
    }

    /**
     * Returns the 2048-byte packed nibble array for the given chunk section's block light,
     * or {@code null} if all blocks in that section have light level 0.
     */
    public byte @Nullable [] getBlockLightSection(int chunkX, int chunkZ, int sectionY) {
        return extractSection(blockLight, chunkX, chunkZ, sectionY, (byte) 0);
    }

    private byte @Nullable [] extractSection(byte @Nullable [] lightArray,
                                             int chunkX,
                                             int chunkZ,
                                             int sectionY,
                                             byte outOfBoundsDefault) {
        int startX = chunkX << 4;
        int startY = sectionY << 4;
        int startZ = chunkZ << 4;

        boolean inside = lightArray != null
                && startX >= originX && startX + 16 <= originX + sizeX
                && startY >= originY && startY + 16 <= originY + sizeY
                && startZ >= originZ && startZ + 16 <= originZ + sizeZ;

        if (!inside) {
            return null;
        }

        byte[] data = new byte[2048];
        boolean nonZero = false;
        int baseX = startX - originX;
        int baseY = startY - originY;
        int baseZ = startZ - originZ;

        int outIdx = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                int rowBase = index(baseX, baseY + y, baseZ + z);
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
        return nonZero ? data : null;
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
}
