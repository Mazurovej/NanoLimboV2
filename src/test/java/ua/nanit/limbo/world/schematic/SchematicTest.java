package ua.nanit.limbo.world.schematic;

import io.netty.buffer.ByteBufAllocator;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import ua.nanit.limbo.protocol.ByteMessage;
import ua.nanit.limbo.protocol.packets.play.PacketChunkWithLight;
import ua.nanit.limbo.protocol.packets.play.PacketSetChunkCacheCenter;
import ua.nanit.limbo.protocol.registry.State;
import ua.nanit.limbo.protocol.registry.Version;
import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.world.DimensionRegistry;
import ua.nanit.limbo.world.DimensionType;
import ua.nanit.limbo.world.VersionedDimension;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchematicTest {

    @Test
    void loadsDefaultSpawnSchemAndComputesLighting() throws Exception {
        SchematicWorld world = loadBundledSpawnSchem(true);

        assertFalse(world.isEmpty(), "spawn.schem should contain blocks");
        assertFalse(world.getChunks().isEmpty(), "spawn.schem should populate chunks");
        assertNotNull(world.getLightEngine(), "light engine should be initialized");

        // Verify at least one chunk has non-zero skylight and heightmap data
        SchematicWorld.ChunkPos firstPos = world.getChunks().keySet().iterator().next();
        SchematicWorld.ChunkData chunk = world.getChunk(firstPos.x(), firstPos.z());
        assertNotNull(chunk);

        long[] heightmap = chunk.computeMotionBlockingHeightmap(-64, 384);
        assertEquals(37, heightmap.length);
        boolean anyHeight = false;
        for (long l : heightmap) {
            if (l != 0L) {
                anyHeight = true;
                break;
            }
        }
        assertTrue(anyHeight, "Heightmap should have non-zero entries for populated chunk");

        byte[] skyLight = world.getLightEngine().getSkyLightSection(firstPos.x(), firstPos.z(), 1);
        assertNotNull(skyLight, "Sky light section should be computed for populated chunk");
        assertEquals(2048, skyLight.length);
    }

    @Test
    void loadsSpongeSchematicV3Format() throws Exception {
        CompoundBinaryTag palette = CompoundBinaryTag.builder()
                .putInt("minecraft:air", 0)
                .putInt("minecraft:stone", 1)
                .putInt("minecraft:glowstone", 2)
                .build();

        // 2x2x2 schematic: index = (y * length + z) * width + x
        byte[] data = new byte[]{1, 1, 1, 1, 0, 2, 0, 0};

        CompoundBinaryTag blocks = CompoundBinaryTag.builder()
                .put("Palette", palette)
                .putByteArray("Data", data)
                .build();

        CompoundBinaryTag inner = CompoundBinaryTag.builder()
                .putInt("Version", 3)
                .putShort("Width", (short) 2)
                .putShort("Height", (short) 2)
                .putShort("Length", (short) 2)
                .put("Blocks", blocks)
                .build();

        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Schematic", inner)
                .build();

        SchematicWorld world = SchematicLoader.fromNbt(root, 10, 64, 20, true);
        assertEquals("minecraft:stone", world.getBlock(10, 64, 20));
        assertEquals("minecraft:glowstone", world.getBlock(11, 65, 20));
        assertEquals("minecraft:air", world.getBlock(10, 65, 20));
    }

    @Test
    void resolvesBlockStatesAcrossAllSupportedVersions() throws Exception {
        SchematicWorld world = loadBundledSpawnSchem(true);

        // Collect all unique block states used in spawn.schem
        Set<String> allStates = new HashSet<>();
        for (SchematicWorld.ChunkData chunk : world.getChunks().values()) {
            for (int sy = -4; sy < 20; sy++) {
                SchematicWorld.ChunkSection section = chunk.getSection(sy);
                if (section != null) {
                    allStates.addAll(section.getPalette());
                }
            }
        }
        assertTrue(allStates.size() > 5, "spawn.schem should contain multiple block states");

        for (Version version : Version.values()) {
            if (version.less(Version.V1_18)) {
                continue;
            }

            assertEquals(0, BlockMappings.getGlobalStateId(version, "minecraft:air"),
                    "Air must be 0 in " + version);
            assertEquals(1, BlockMappings.getGlobalStateId(version, "minecraft:stone"),
                    "Stone must be 1 in " + version);
            assertTrue(BlockMappings.getGlobalStateId(version, "minecraft:purpur_slab[type=bottom,waterlogged=false]") > 0,
                    "Purpur slab must resolve in " + version);
            assertEquals(0, BlockMappings.getGlobalStateId(version, "minecraft:nonexistent_block_xyz"),
                    "Unknown block must fall back to air in " + version);

            for (String state : allStates) {
                if (!SchematicWorld.isAir(state)) {
                    int id = BlockMappings.getGlobalStateId(version, state);
                    assertTrue(id > 0, "State " + state + " failed to resolve in " + version);
                }
            }
        }
    }

    @Test
    void encodesChunkPacketForAllVersions1_18ToMax() throws Exception {
        LimboServer server = new LimboServer();
        DimensionRegistry registry = new DimensionRegistry(server);
        registry.load();

        SchematicWorld world = loadBundledSpawnSchem(true);
        SchematicWorld.ChunkPos pos = world.getChunks().keySet().iterator().next();

        for (DimensionType dimType : DimensionType.values()) {
            VersionedDimension dimension = dimType.createVersionedDimension(registry);
            PacketChunkWithLight packet = new PacketChunkWithLight(pos.x(), pos.z(), dimension, world);

            for (Version version : Version.values()) {
                if (version.less(Version.V1_18)) {
                    continue;
                }

                // Verify packet IDs are registered
                State.PacketRegistry playRegistry = State.PLAY.clientBound.getRegistry(version);
                assertTrue(playRegistry.getPacketId(PacketChunkWithLight.class) >= 0,
                        "PacketChunkWithLight must be registered for " + version);
                assertTrue(playRegistry.getPacketId(PacketSetChunkCacheCenter.class) >= 0,
                        "PacketSetChunkCacheCenter must be registered for " + version);

                ByteMessage buf = new ByteMessage(ByteBufAllocator.DEFAULT.heapBuffer());
                try {
                    packet.encode(buf, version);
                    assertTrue(buf.readableBytes() > 50,
                            "Encoded chunk packet should be non-trivial for " + version + " / " + dimType);
                    assertEquals(pos.x(), buf.readInt());
                    assertEquals(pos.z(), buf.readInt());
                } finally {
                    buf.release();
                }
            }
        }
    }

    private static SchematicWorld loadBundledSpawnSchem(boolean hasSkyLight) throws Exception {
        try (InputStream in = SchematicTest.class.getResourceAsStream("/spawn.schem")) {
            assertNotNull(in, "Bundled /spawn.schem resource must exist");
            CompoundBinaryTag tag = BinaryTagIO.unlimitedReader().read(in, BinaryTagIO.Compression.GZIP);
            return SchematicLoader.fromNbt(tag, 0, 0, 0, hasSkyLight);
        }
    }
}
