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
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads Sponge Schematic (.schem) files (both v2 and v3) into a {@link SchematicWorld}.
 * Fully compatible with schematics used by LOOHP/Limbo, WorldEdit, and FAWE.
 */
public final class SchematicLoader {

    private SchematicLoader() {
    }

    @NonNull
    public static SchematicWorld load(@NonNull Path path,
                                      int offsetX,
                                      int offsetY,
                                      int offsetZ,
                                      boolean hasSkyLight) throws IOException {
        CompoundBinaryTag root = readNbt(path);
        return fromNbt(root, offsetX, offsetY, offsetZ, hasSkyLight);
    }

    @NonNull
    public static SchematicWorld fromNbt(@NonNull CompoundBinaryTag root,
                                         int offsetX,
                                         int offsetY,
                                         int offsetZ,
                                         boolean hasSkyLight) throws IOException {
        // Sponge Schematic v3 may wrap the payload under a "Schematic" compound tag
        CompoundBinaryTag schem = root.get("Schematic") instanceof CompoundBinaryTag nested ? nested : root;

        int width = schem.getShort("Width") & 0xFFFF;
        int height = schem.getShort("Height") & 0xFFFF;
        int length = schem.getShort("Length") & 0xFFFF;

        if (width == 0 || height == 0 || length == 0) {
            throw new IOException("Invalid schematic dimensions: " + width + "x" + height + "x" + length);
        }

        CompoundBinaryTag paletteTag;
        byte[] blockData;

        // Sponge Schematic v3 stores Palette and Data inside a "Blocks" compound
        if (schem.get("Blocks") instanceof CompoundBinaryTag blocksTag) {
            paletteTag = blocksTag.getCompound("Palette");
            blockData = blocksTag.getByteArray("Data");
        } else {
            // Sponge Schematic v2 (LOOHP/Limbo format)
            paletteTag = schem.getCompound("Palette");
            blockData = schem.getByteArray("BlockData");
        }

        if (paletteTag.size() == 0 || blockData.length == 0) {
            throw new IOException("Schematic is missing Palette or BlockData/Data tags");
        }

        Map<Integer, String> palette = new HashMap<>(paletteTag.size() * 4 / 3 + 1);
        for (Map.Entry<String, ? extends BinaryTag> entry : paletteTag) {
            if (entry.getValue() instanceof IntBinaryTag intTag) {
                palette.put(intTag.value(), entry.getKey());
            }
        }

        SchematicWorld world = new SchematicWorld();
        int widthTimesLength = width * length;

        int index = 0;
        int i = 0;
        while (i < blockData.length) {
            int value = 0;
            int varIntLen = 0;

            while (true) {
                if (i >= blockData.length) {
                    throw new IOException("Unexpected end of VarInt in schematic BlockData");
                }
                byte b = blockData[i++];
                value |= (b & 0x7F) << (varIntLen++ * 7);
                if (varIntLen > 5) {
                    throw new IOException("VarInt too big in schematic BlockData (corrupted data)");
                }
                if ((b & 0x80) == 0) {
                    break;
                }
            }

            // Sponge Schematic coordinate order: index = (y * length + z) * width + x
            int y = index / widthTimesLength;
            int rem = index % widthTimesLength;
            int z = rem / width;
            int x = rem % width;

            String blockState = palette.get(value);
            if (blockState != null && !SchematicWorld.isAir(blockState)) {
                world.setBlock(x + offsetX, y + offsetY, z + offsetZ, blockState);
            }

            index++;
        }

        world.computeLighting(hasSkyLight);
        return world;
    }

    @NonNull
    private static CompoundBinaryTag readNbt(@NonNull Path path) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            in.mark(2);
            int b1 = in.read();
            int b2 = in.read();
            in.reset();

            boolean isGzip = (b1 == 0x1F && b2 == 0x8B);
            BinaryTagIO.Compression compression = isGzip
                    ? BinaryTagIO.Compression.GZIP
                    : BinaryTagIO.Compression.NONE;

            return BinaryTagIO.unlimitedReader().read(in, compression);
        }
    }
}
