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

import lombok.Getter;
import lombok.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.*;

/**
 * In-memory chunked representation of a loaded schematic world,
 * indexed by chunk coordinates and 16x16x16 section Y coordinates.
 */
@Getter
public final class SchematicWorld {

    public static final String AIR = "minecraft:air";

    private final Map<ChunkPos, ChunkData> chunks = new HashMap<>();

    private int minX = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxZ = Integer.MIN_VALUE;

    private LightEngine lightEngine;

    public void setBlock(int x, int y, int z, @NonNull String blockState) {
        if (isAir(blockState)) {
            return;
        }

        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
        if (z < minZ) minZ = z;
        if (z > maxZ) maxZ = z;

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        ChunkData chunk = chunks.computeIfAbsent(new ChunkPos(chunkX, chunkZ), k -> new ChunkData());
        chunk.setBlock(x & 15, y, z & 15, blockState);
    }

    @NonNull
    public String getBlock(int x, int y, int z) {
        ChunkData chunk = chunks.get(new ChunkPos(x >> 4, z >> 4));
        if (chunk == null) {
            return AIR;
        }
        return chunk.getBlock(x & 15, y, z & 15);
    }

    @Nullable
    public ChunkData getChunk(int chunkX, int chunkZ) {
        return chunks.get(new ChunkPos(chunkX, chunkZ));
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public void computeLighting(boolean hasSkyLight) {
        this.lightEngine = new LightEngine(this, hasSkyLight);
        this.lightEngine.compute();
    }

    public static boolean isAir(@NonNull String blockState) {
        return blockState.equals(AIR)
                || blockState.equals("air")
                || blockState.equals("minecraft:cave_air")
                || blockState.equals("minecraft:void_air");
    }

    public static boolean isFluid(@NonNull String blockState) {
        return blockState.startsWith("minecraft:water")
                || blockState.startsWith("minecraft:lava")
                || blockState.contains("waterlogged=true");
    }

    public record ChunkPos(int x, int z) {
    }

    public static final class ChunkData {

        private final Map<Integer, ChunkSection> sections = new HashMap<>();
        private final int[] highestBlockY = new int[256];

        public ChunkData() {
            Arrays.fill(highestBlockY, Integer.MIN_VALUE);
        }

        public void setBlock(int localX, int worldY, int localZ, @NonNull String blockState) {
            int sectionY = worldY >> 4;
            ChunkSection section = sections.computeIfAbsent(sectionY, k -> new ChunkSection());
            section.setBlock(localX, worldY & 15, localZ, blockState);

            int colIdx = (localZ << 4) | localX;
            if (!isAir(blockState) && worldY > highestBlockY[colIdx]) {
                highestBlockY[colIdx] = worldY;
            }
        }

        @NonNull
        public String getBlock(int localX, int worldY, int localZ) {
            ChunkSection section = sections.get(worldY >> 4);
            if (section == null) {
                return AIR;
            }
            return section.getBlock(localX, worldY & 15, localZ);
        }

        @Nullable
        public ChunkSection getSection(int sectionY) {
            return sections.get(sectionY);
        }

        public long[] computeMotionBlockingHeightmap(int minY, int height) {
            int bitsPerEntry = 32 - Integer.numberOfLeadingZeros(height);
            int entriesPerLong = 64 / bitsPerEntry;
            int longCount = (256 + entriesPerLong - 1) / entriesPerLong;
            long[] data = new long[longCount];
            long mask = (1L << bitsPerEntry) - 1L;

            for (int i = 0; i < 256; i++) {
                int y = highestBlockY[i];
                long value;
                if (y == Integer.MIN_VALUE || y < minY) {
                    value = 0L;
                } else {
                    value = Math.min(height, y - minY + 1);
                }
                int longIdx = i / entriesPerLong;
                int bitOffset = (i % entriesPerLong) * bitsPerEntry;
                data[longIdx] |= (value & mask) << bitOffset;
            }
            return data;
        }
    }

    @Getter
    public static final class ChunkSection {

        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> paletteIndex = new HashMap<>();
        private final short[] blocks = new short[4096];
        private short nonAirCount;
        private short fluidCount;

        public ChunkSection() {
            palette.add(AIR);
            paletteIndex.put(AIR, 0);
        }

        public void setBlock(int localX, int localY, int localZ, @NonNull String blockState) {
            int idx = (localY << 8) | (localZ << 4) | localX;
            int oldPaletteId = blocks[idx] & 0xFFFF;
            String oldState = palette.get(oldPaletteId);

            boolean wasAir = isAir(oldState);
            boolean nowAir = isAir(blockState);
            if (wasAir && !nowAir) {
                nonAirCount++;
            } else if (!wasAir && nowAir) {
                nonAirCount--;
            }

            boolean wasFluid = isFluid(oldState);
            boolean nowFluid = isFluid(blockState);
            if (!wasFluid && nowFluid) {
                fluidCount++;
            } else if (wasFluid && !nowFluid) {
                fluidCount--;
            }

            int newPaletteId = paletteIndex.computeIfAbsent(blockState, key -> {
                int id = palette.size();
                palette.add(key);
                return id;
            });
            blocks[idx] = (short) newPaletteId;
        }

        @NonNull
        public String getBlock(int localX, int localY, int localZ) {
            int idx = (localY << 8) | (localZ << 4) | localX;
            return palette.get(blocks[idx] & 0xFFFF);
        }
    }
}
