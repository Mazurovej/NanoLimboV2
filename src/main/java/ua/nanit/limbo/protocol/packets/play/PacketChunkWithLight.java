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

package ua.nanit.limbo.protocol.packets.play;

import io.netty.buffer.ByteBufAllocator;
import lombok.*;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import org.checkerframework.checker.nullness.qual.Nullable;
import ua.nanit.limbo.protocol.ByteMessage;
import ua.nanit.limbo.protocol.MetadataWriter;
import ua.nanit.limbo.protocol.PacketOut;
import ua.nanit.limbo.protocol.registry.Version;
import ua.nanit.limbo.world.VersionedDimension;
import ua.nanit.limbo.world.schematic.BlockMappings;
import ua.nanit.limbo.world.schematic.LightEngine;
import ua.nanit.limbo.world.schematic.SchematicWorld;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PacketChunkWithLight implements PacketOut {

    private int x;
    private int z;
    private VersionedDimension dimension;
    @Nullable
    private SchematicWorld world;

    public PacketChunkWithLight(int x, int z, VersionedDimension dimension) {
        this(x, z, dimension, null);
    }

    @Override
    public void encode(@NonNull ByteMessage buf, @NonNull Version version) {
        buf.writeInt(this.x);
        buf.writeInt(this.z);

        SchematicWorld.ChunkData chunkData = this.world != null ? this.world.getChunk(this.x, this.z) : null;
        int minY = this.dimension.getMinY(version);
        int height = this.dimension.getHeight(version);

        long[] motionBlocking = chunkData != null
                ? chunkData.computeMotionBlockingHeightmap(minY, height)
                : new long[37];

        writeHeightmaps(buf, version, Map.of(HeightMapType.MOTION_BLOCKING, motionBlocking));
        writeBlocksData(buf, version, chunkData);

        // Skip block entities
        buf.writeVarInt(0);

        writeLightData(buf, version);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    private void writeBlocksData(@NonNull ByteMessage buf,
                                 @NonNull Version version,
                                 SchematicWorld.@Nullable ChunkData chunkData) {
        int sections = this.dimension.getChunkSections(version);
        int minSectionY = this.dimension.getMinSectionY(version);
        SinglePaletteFactory emptyPalette = new SinglePaletteFactory(0);

        ByteMessage sectionsBuf = new ByteMessage(ByteBufAllocator.DEFAULT.heapBuffer());
        try {
            for (int i = 0; i < sections; i++) {
                int sectionY = minSectionY + i;
                SchematicWorld.ChunkSection section = chunkData != null ? chunkData.getSection(sectionY) : null;

                if (section == null || section.getNonAirCount() == 0) {
                    writeEmptySection(sectionsBuf, version, emptyPalette, emptyPalette);
                } else {
                    writePopulatedSection(sectionsBuf, version, section, emptyPalette);
                }
            }

            buf.writeVarInt(sectionsBuf.readableBytes());
            buf.writeBytes(sectionsBuf);
        } finally {
            sectionsBuf.release();
        }
    }

    private static void writeEmptySection(@NonNull ByteMessage buf,
                                          @NonNull Version version,
                                          @NonNull PaletteFactory blocksPalette,
                                          @NonNull PaletteFactory biomesPalette) {
        buf.writeShort(0); // non-empty block count
        if (version.moreOrEqual(Version.V26_1)) {
            buf.writeShort(0); // fluid count
        }
        long[] storage = new long[0];
        writePalette(buf, version, blocksPalette, storage);
        writePalette(buf, version, biomesPalette, storage);
    }

    private static void writePopulatedSection(@NonNull ByteMessage buf,
                                              @NonNull Version version,
                                              @NonNull SchematicWorld.ChunkSection section,
                                              @NonNull PaletteFactory biomesPalette) {
        buf.writeShort(section.getNonAirCount());
        if (version.moreOrEqual(Version.V26_1)) {
            buf.writeShort(section.getFluidCount());
        }

        List<String> palette = section.getPalette();
        int paletteSize = palette.size();
        int[] globalIds = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            globalIds[i] = BlockMappings.getGlobalStateId(version, palette.get(i));
        }

        short[] blocks = section.getBlocks();

        if (paletteSize == 1) {
            writePalette(buf, version, new SinglePaletteFactory(globalIds[0]), new long[0]);
        } else if (paletteSize <= 256) {
            int bitsPerEntry = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            long[] packed = packEntries(blocks, null, bitsPerEntry);
            writePalette(buf, version, new IndirectPaletteFactory(bitsPerEntry, globalIds), packed);
        } else {
            int bitsPerEntry = BlockMappings.getDirectPaletteBits(version);
            long[] packed = packEntries(blocks, globalIds, bitsPerEntry);
            writePalette(buf, version, new DirectPaletteFactory(bitsPerEntry), packed);
        }

        writePalette(buf, version, biomesPalette, new long[0]);
    }

    private static long[] packEntries(short[] blocks, int @Nullable [] globalIds, int bitsPerEntry) {
        int entriesPerLong = 64 / bitsPerEntry;
        int longCount = (4096 + entriesPerLong - 1) / entriesPerLong;
        long[] data = new long[longCount];
        long mask = (1L << bitsPerEntry) - 1L;

        for (int i = 0; i < 4096; i++) {
            int paletteIdx = blocks[i] & 0xFFFF;
            long value = globalIds != null ? globalIds[paletteIdx] : paletteIdx;
            int longIdx = i / entriesPerLong;
            int bitOffset = (i % entriesPerLong) * bitsPerEntry;
            data[longIdx] |= (value & mask) << bitOffset;
        }
        return data;
    }

    private static void writePalette(@NonNull ByteMessage buf,
                                     @NonNull Version version,
                                     @NonNull PaletteFactory paletteFactory,
                                     long[] storage) {
        buf.writeByte(paletteFactory.getId());
        paletteFactory.writeData(buf, version);
        if (version.moreOrEqual(Version.V1_21_5)) {
            for (long data : storage) {
                buf.writeLong(data);
            }
            return;
        }
        buf.writeVarInt(storage.length);
        for (long data : storage) {
            buf.writeLong(data);
        }
    }

    private void writeLightData(@NonNull ByteMessage buf, @NonNull Version version) {
        // 1.18 - 1.19.4 include a trustEdges boolean before the light masks
        if (version.less(Version.V1_20)) {
            buf.writeBoolean(true);
        }

        int sections = this.dimension.getChunkSections(version);
        int lightSections = sections + 2;

        LightEngine lightEngine = this.world != null ? this.world.getLightEngine() : null;
        if (lightEngine == null) {
            BitSet emptyBlocksYMask = new BitSet(lightSections);
            emptyBlocksYMask.set(0, lightSections);

            buf.writeBitSet(null, version); // Sky y mask
            buf.writeBitSet(null, version); // Block y mask
            buf.writeBitSet(null, version); // Empty sky y mask
            buf.writeBitSet(emptyBlocksYMask, version); // Empty block y mask

            buf.writeVarInt(0); // Sky updates
            buf.writeVarInt(0); // Block updates
            return;
        }

        int minSectionY = this.dimension.getMinSectionY(version);
        BitSet skyYMask = new BitSet(lightSections);
        BitSet blockYMask = new BitSet(lightSections);
        BitSet emptySkyYMask = new BitSet(lightSections);
        BitSet emptyBlockYMask = new BitSet(lightSections);

        List<byte[]> skyArrays = new ArrayList<>();
        List<byte[]> blockArrays = new ArrayList<>();

        for (int i = 0; i < lightSections; i++) {
            int sectionY = minSectionY - 1 + i;

            byte[] sky = lightEngine.getSkyLightSection(this.x, this.z, sectionY);
            if (sky != null) {
                skyYMask.set(i);
                skyArrays.add(sky);
            } else {
                emptySkyYMask.set(i);
            }

            byte[] block = lightEngine.getBlockLightSection(this.x, this.z, sectionY);
            if (block != null) {
                blockYMask.set(i);
                blockArrays.add(block);
            } else {
                emptyBlockYMask.set(i);
            }
        }

        buf.writeBitSet(skyYMask, version);
        buf.writeBitSet(blockYMask, version);
        buf.writeBitSet(emptySkyYMask, version);
        buf.writeBitSet(emptyBlockYMask, version);

        buf.writeVarInt(skyArrays.size());
        for (byte[] array : skyArrays) {
            buf.writeBytesArray(array);
        }

        buf.writeVarInt(blockArrays.size());
        for (byte[] array : blockArrays) {
            buf.writeBytesArray(array);
        }
    }

    private static void writeHeightmaps(@NonNull ByteMessage buf,
                                        @NonNull Version version,
                                        @NonNull Map<HeightMapType, long[]> heightMaps) {
        if (version.moreOrEqual(Version.V1_21_5)) {
            buf.writeVarInt(heightMaps.size());
            for (Map.Entry<HeightMapType, long[]> entry : heightMaps.entrySet()) {
                buf.writeVarInt(entry.getKey().ordinal());
                long[] bitSet = entry.getValue();
                buf.writeVarInt(bitSet.length);
                for (long l : bitSet) {
                    buf.writeLong(l);
                }
            }
            return;
        }
        CompoundBinaryTag.Builder tagBuilder = CompoundBinaryTag.builder();
        for (Map.Entry<HeightMapType, long[]> entry : heightMaps.entrySet()) {
            tagBuilder.put(entry.getKey().name(), LongArrayBinaryTag.longArrayBinaryTag(entry.getValue()));
        }
        buf.writeCompoundTag(tagBuilder.build(), version);
    }

    public enum HeightMapType {
        WORLD_SURFACE_WG,
        WORLD_SURFACE,
        OCEAN_FLOOR_WG,
        OCEAN_FLOOR,
        MOTION_BLOCKING,
        MOTION_BLOCKING_NO_LEAVES
    }

    public interface PaletteFactory extends MetadataWriter {

        int getId();

    }

    @RequiredArgsConstructor
    public static class SinglePaletteFactory implements PaletteFactory {

        private final int data;

        @Override
        public int getId() {
            return 0;
        }

        @Override
        public void writeData(@NonNull ByteMessage message, @NonNull Version version) {
            message.writeVarInt(this.data);
        }
    }

    @RequiredArgsConstructor
    public static class IndirectPaletteFactory implements PaletteFactory {

        private final int bitsPerEntry;
        private final int[] paletteIds;

        @Override
        public int getId() {
            return this.bitsPerEntry;
        }

        @Override
        public void writeData(@NonNull ByteMessage message, @NonNull Version version) {
            message.writeVarInt(this.paletteIds.length);
            for (int id : this.paletteIds) {
                message.writeVarInt(id);
            }
        }
    }

    @RequiredArgsConstructor
    public static class DirectPaletteFactory implements PaletteFactory {

        private final int bitsPerEntry;

        @Override
        public int getId() {
            return this.bitsPerEntry;
        }

        @Override
        public void writeData(@NonNull ByteMessage message, @NonNull Version version) {
            // Direct palette has no palette array
        }
    }

}
