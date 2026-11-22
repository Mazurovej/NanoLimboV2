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
import ua.nanit.limbo.protocol.registry.Version;
import ua.nanit.limbo.server.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Multi-version global block state ID lookup table for Minecraft 1.18 through 26.3.
 * Loads compact gzipped binary block registries from classpath resources under {@code /blocks/}.
 */
public final class BlockMappings {

    private static final Map<String, VersionMappings> CACHE = new ConcurrentHashMap<>();

    private BlockMappings() {
    }

    @NonNull
    public static VersionMappings forVersion(@NonNull Version version) {
        String resourcePath = getResourcePath(version);
        return CACHE.computeIfAbsent(resourcePath, BlockMappings::loadResource);
    }

    public static int getGlobalStateId(@NonNull Version version, @NonNull String blockState) {
        return forVersion(version).getStateId(blockState);
    }

    public static int getDirectPaletteBits(@NonNull Version version) {
        int totalStates = forVersion(version).getTotalStates();
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, totalStates - 1)));
    }

    @NonNull
    private static String getResourcePath(@NonNull Version version) {
        if (version.moreOrEqual(Version.V26_3)) {
            return "/blocks/blocks_26_3.bin.gz";
        } else if (version.moreOrEqual(Version.V26_2)) {
            return "/blocks/blocks_26_2.bin.gz";
        } else if (version.moreOrEqual(Version.V26_1)) {
            return "/blocks/blocks_26_1.bin.gz";
        } else if (version.moreOrEqual(Version.V1_21_9)) {
            return "/blocks/blocks_1_21_9.bin.gz";
        } else if (version.moreOrEqual(Version.V1_21_6)) {
            return "/blocks/blocks_1_21_6.bin.gz";
        } else if (version.equals(Version.V1_21_5)) {
            return "/blocks/blocks_1_21_5.bin.gz";
        } else if (version.equals(Version.V1_21_4)) {
            return "/blocks/blocks_1_21_4.bin.gz";
        } else if (version.equals(Version.V1_21_2)) {
            return "/blocks/blocks_1_21_2.bin.gz";
        } else if (version.moreOrEqual(Version.V1_20_5)) {
            return "/blocks/blocks_1_20_5.bin.gz";
        } else if (version.equals(Version.V1_20_3)) {
            return "/blocks/blocks_1_20_3.bin.gz";
        } else if (version.equals(Version.V1_20_2)) {
            return "/blocks/blocks_1_20_2.bin.gz";
        } else if (version.equals(Version.V1_20)) {
            return "/blocks/blocks_1_20.bin.gz";
        } else if (version.equals(Version.V1_19_4)) {
            return "/blocks/blocks_1_19_4.bin.gz";
        } else if (version.equals(Version.V1_19_3)) {
            return "/blocks/blocks_1_19_3.bin.gz";
        } else if (version.moreOrEqual(Version.V1_19)) {
            return "/blocks/blocks_1_19.bin.gz";
        } else {
            return "/blocks/blocks_1_18.bin.gz";
        }
    }

    @NonNull
    private static VersionMappings loadResource(@NonNull String resourcePath) {
        try (InputStream raw = BlockMappings.class.getResourceAsStream(resourcePath)) {
            if (raw == null) {
                throw new IllegalStateException("Block mappings resource not found: " + resourcePath);
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
                int totalStates = in.readInt();
                int poolSize = in.readInt();
                String[] pool = new String[poolSize];
                for (int i = 0; i < poolSize; i++) {
                    pool[i] = in.readUTF();
                }

                int blockCount = in.readInt();
                Map<String, BlockEntry> blocks = new HashMap<>(blockCount * 4 / 3 + 1);

                for (int i = 0; i < blockCount; i++) {
                    String name = in.readUTF();
                    int minStateId = in.readInt();
                    int defaultOffset = in.readInt();
                    int propCount = in.readUnsignedByte();

                    PropertyDef[] props = propCount == 0 ? PropertyDef.EMPTY : new PropertyDef[propCount];
                    for (int p = 0; p < propCount; p++) {
                        String propName = pool[in.readUnsignedShort()];
                        int valCount = in.readUnsignedByte();
                        String[] values = new String[valCount];
                        for (int v = 0; v < valCount; v++) {
                            values[v] = pool[in.readUnsignedShort()];
                        }
                        props[p] = new PropertyDef(propName, values);
                    }

                    blocks.put(name, new BlockEntry(minStateId, defaultOffset, props));
                }

                return new VersionMappings(totalStates, blocks);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load block mappings from " + resourcePath, e);
        }
    }

    public static final class VersionMappings {

        private final int totalStates;
        private final Map<String, BlockEntry> blocks;
        private final Map<String, Integer> resolvedCache = new ConcurrentHashMap<>();

        VersionMappings(int totalStates, @NonNull Map<String, BlockEntry> blocks) {
            this.totalStates = totalStates;
            this.blocks = blocks;
        }

        public int getTotalStates() {
            return totalStates;
        }

        public int getStateId(@NonNull String blockState) {
            Integer cached = resolvedCache.get(blockState);
            if (cached != null) {
                return cached;
            }
            int computed = resolveStateId(blockState);
            resolvedCache.put(blockState, computed);
            return computed;
        }

        private int resolveStateId(@NonNull String blockState) {
            int bracketIdx = blockState.indexOf('[');
            String rawName = bracketIdx < 0 ? blockState : blockState.substring(0, bracketIdx);
            String name = rawName.startsWith("minecraft:") ? rawName.substring(10) : rawName;

            BlockEntry entry = blocks.get(name);
            if (entry == null) {
                Log.debug("Unknown block '%s' for current mapping version; falling back to air", blockState);
                return 0;
            }

            if (entry.properties.length == 0) {
                return entry.minStateId;
            }

            if (bracketIdx < 0) {
                return entry.minStateId + entry.defaultOffset;
            }

            int endBracket = blockState.lastIndexOf(']');
            if (endBracket <= bracketIdx + 1) {
                return entry.minStateId + entry.defaultOffset;
            }

            Map<String, String> providedProps = parseProperties(blockState, bracketIdx + 1, endBracket);
            return entry.resolve(providedProps);
        }

        private static Map<String, String> parseProperties(String s, int start, int end) {
            Map<String, String> map = new HashMap<>();
            int i = start;
            while (i < end) {
                int comma = s.indexOf(',', i);
                if (comma < 0 || comma > end) {
                    comma = end;
                }
                int eq = s.indexOf('=', i);
                if (eq > i && eq < comma) {
                    String k = s.substring(i, eq).trim();
                    String v = s.substring(eq + 1, comma).trim();
                    map.put(k, v);
                }
                i = comma + 1;
            }
            return map;
        }
    }

    private record PropertyDef(String name, String[] values) {
        static final PropertyDef[] EMPTY = new PropertyDef[0];

        int indexOf(String value) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static final class BlockEntry {
        final int minStateId;
        final int defaultOffset;
        final PropertyDef[] properties;
        final int[] strides;
        final int[] defaultIndices;

        BlockEntry(int minStateId, int defaultOffset, PropertyDef[] properties) {
            this.minStateId = minStateId;
            this.defaultOffset = defaultOffset;
            this.properties = properties;

            int n = properties.length;
            this.strides = new int[n];
            this.defaultIndices = new int[n];

            int stride = 1;
            for (int i = n - 1; i >= 0; i--) {
                this.strides[i] = stride;
                int len = properties[i].values.length;
                this.defaultIndices[i] = (defaultOffset / stride) % len;
                stride *= len;
            }
        }

        int resolve(Map<String, String> providedProps) {
            int offset = 0;
            for (int i = 0; i < properties.length; i++) {
                PropertyDef prop = properties[i];
                String val = providedProps.get(prop.name);
                int idx = val != null ? prop.indexOf(val) : -1;
                if (idx < 0) {
                    idx = defaultIndices[i];
                }
                offset += idx * strides[i];
            }
            return minStateId + offset;
        }
    }
}
