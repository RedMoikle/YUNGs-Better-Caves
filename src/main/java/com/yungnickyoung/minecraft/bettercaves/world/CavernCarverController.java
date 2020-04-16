package com.yungnickyoung.minecraft.bettercaves.world;

import com.yungnickyoung.minecraft.bettercaves.config.ConfigHolder;
import com.yungnickyoung.minecraft.bettercaves.config.Settings;
import com.yungnickyoung.minecraft.bettercaves.enums.CavernType;
import com.yungnickyoung.minecraft.bettercaves.enums.RegionSize;
import com.yungnickyoung.minecraft.bettercaves.noise.FastNoise;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseColumn;
import com.yungnickyoung.minecraft.bettercaves.noise.NoiseUtils;
import com.yungnickyoung.minecraft.bettercaves.world.carver.CarverNoiseRange;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cavern.CavernCarver;
import com.yungnickyoung.minecraft.bettercaves.world.carver.cavern.CavernCarverBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class CavernCarverController {
    private World world;
    private FastNoise cavernRegionController;
    private List<CarverNoiseRange> noiseRanges = new ArrayList<>();

    // Vars from config
    private boolean isDebugViewEnabled;
    private boolean isOverrideSurfaceDetectionEnabled;
    private boolean isFloodedUndergroundEnabled;

    public CavernCarverController(World worldIn, ConfigHolder config) {
        this.world = worldIn;
        this.isDebugViewEnabled = config.debugVisualizer.get();
        this.isOverrideSurfaceDetectionEnabled = config.overrideSurfaceDetection.get();
        this.isFloodedUndergroundEnabled = config.enableFloodedUnderground.get();

        // Configure cavern region controller, which determines what type of cavern should be carved in any given region
        float cavernRegionSize = calcCavernRegionSize(config.cavernRegionSize.get(), config.cavernRegionCustomSize.get());
        this.cavernRegionController = new FastNoise();
        this.cavernRegionController.SetSeed((int)worldIn.getSeed() + 333);
        this.cavernRegionController.SetFrequency(cavernRegionSize);

        // Initialize all carvers using config options
        List<CavernCarver> carvers = new ArrayList<>();
        carvers.add(new CavernCarverBuilder(worldIn)
            .ofTypeFromConfig(CavernType.LIQUID, config)
            .debugVisualizerBlock(Blocks.REDSTONE_BLOCK.getDefaultState())
            .build()
        );
        carvers.add(new CavernCarverBuilder(worldIn)
            .ofTypeFromConfig(CavernType.FLOORED, config)
            .debugVisualizerBlock(Blocks.GOLD_BLOCK.getDefaultState())
            .build()
        );

        float spawnChance = config.cavernSpawnChance.get() / 100f;
        int totalPriority = carvers.stream().map(CavernCarver::getPriority).reduce(0, Integer::sum);

        Settings.LOGGER.debug("CAVERN INFORMATION");
        Settings.LOGGER.debug("--> SPAWN CHANCE SET TO: " + spawnChance);
        Settings.LOGGER.debug("--> TOTAL PRIORITY: " + totalPriority);

        carvers.removeIf(carver -> carver.getPriority() == 0);
        float totalDeadzonePercent = 1 - spawnChance;
        float deadzonePercent = carvers.size() > 1
                ? totalDeadzonePercent / (carvers.size() - 1)
                : totalDeadzonePercent;

        Settings.LOGGER.debug("--> DEADZONE PERCENT: " + deadzonePercent + "(" + totalDeadzonePercent + " TOTAL)");

        float currNoise = -1f;

        for (CavernCarver carver : carvers) {
            Settings.LOGGER.debug("--> CARVER");
            float rangeCDFPercent = (float)carver.getPriority() / totalPriority * spawnChance;
            float topNoise = NoiseUtils.simplexNoiseOffsetByPercent(currNoise, rangeCDFPercent);
            CarverNoiseRange range = new CarverNoiseRange(currNoise, topNoise, carver);
            noiseRanges.add(range);

            // Offset currNoise for deadzone region
            currNoise = NoiseUtils.simplexNoiseOffsetByPercent(topNoise, deadzonePercent);

            Settings.LOGGER.debug("    --> RANGE PERCENT LENGTH WANTED: " + rangeCDFPercent);
            Settings.LOGGER.debug("    --> RANGE FOUND: " + range);
        }
    }

    public void carveChunk(ChunkPrimer primer, int chunkX, int chunkZ, int[][] surfaceAltitudes, IBlockState[][] liquidBlocks) {
        // Prevent unnecessary computation if caverns are disabled
        if (noiseRanges.size() == 0) {
            return;
        }

        boolean flooded = false;
        float smoothAmpFactor = 1;

        for (int subX = 0; subX < 16 / Settings.SUB_CHUNK_SIZE; subX++) {
            for (int subZ = 0; subZ < 16 / Settings.SUB_CHUNK_SIZE; subZ++) {
                int startX = subX * Settings.SUB_CHUNK_SIZE;
                int startZ = subZ * Settings.SUB_CHUNK_SIZE;
                int endX = startX + Settings.SUB_CHUNK_SIZE - 1;
                int endZ = startZ + Settings.SUB_CHUNK_SIZE - 1;
                BlockPos startPos = new BlockPos(chunkX * 16 + startX, 1, chunkZ * 16 + startZ);
                BlockPos endPos = new BlockPos(chunkX * 16 + endX, 1, chunkZ * 16 + endZ);

                noiseRanges.forEach(range -> range.setNoiseCube(null));

                // Get max height in subchunk. This is needed for calculating the noise cube
                int maxHeight = 0;
                if (!isOverrideSurfaceDetectionEnabled) { // Only necessary if we aren't overriding surface detection
                    for (int x = startX; x < endX; x++) {
                        for (int z = startZ; z < endZ; z++) {
                            maxHeight = Math.max(maxHeight, surfaceAltitudes[x][z]);
                        }
                    }
                    for (CarverNoiseRange range : noiseRanges) {
                        CavernCarver carver = (CavernCarver) range.getCarver();
                        maxHeight = Math.max(maxHeight, carver.getTopY());
                    }
                }

                for (int offsetX = 0; offsetX < Settings.SUB_CHUNK_SIZE; offsetX++) {
                    for (int offsetZ = 0; offsetZ < Settings.SUB_CHUNK_SIZE; offsetZ++) {
                        int localX = startX + offsetX;
                        int localZ = startZ + offsetZ;
                        BlockPos colPos = new BlockPos(chunkX * 16 + localX, 1, chunkZ * 16 + localZ);

                        if (isFloodedUndergroundEnabled && !isDebugViewEnabled) {
                            flooded = world.getBiome(colPos).getTempCategory() == Biome.TempCategory.OCEAN;
                            Function<Biome.TempCategory, Boolean> compareFunc = flooded ? (b -> b != Biome.TempCategory.OCEAN) : (b -> b == Biome.TempCategory.OCEAN);
                            smoothAmpFactor = getDistFactor(colPos, 2, compareFunc);
//                            smoothAmpFactor = calcFloodedSmoothAmp(flooded, colPos);
                            if (smoothAmpFactor <= 0) continue; // Wall between flooded and normal caves
                        }

                        int surfaceAltitude = surfaceAltitudes[localX][localZ];
                        IBlockState liquidBlock = liquidBlocks[localX][localZ];

                        // Get noise values used to determine cavern region
                        float cavernRegionNoise = cavernRegionController.GetNoise(colPos.getX(), colPos.getZ());

                        // Carve cavern using matching carver
                        for (CarverNoiseRange range : noiseRanges) {
                            if (!range.contains(cavernRegionNoise)) {
                                continue;
                            }
                            CavernCarver carver = (CavernCarver)range.getCarver();
                            int bottomY = carver.getBottomY();
                            int topY = isDebugViewEnabled ? carver.getTopY() : Math.min(surfaceAltitude, carver.getTopY());
                            if (isOverrideSurfaceDetectionEnabled) {
                                topY = carver.getTopY();
                                maxHeight = carver.getTopY();
                            }
                            float smoothAmp = range.getSmoothAmp(cavernRegionNoise) * smoothAmpFactor;
                            if (range.getNoiseCube() == null) {
                                range.setNoiseCube(carver.getNoiseGen().interpolateNoiseCube(startPos, endPos, bottomY, maxHeight));
                            }
                            NoiseColumn noiseColumn = range.getNoiseCube().get(offsetX).get(offsetZ);
                            carver.carveColumn(primer, colPos, topY, smoothAmp, noiseColumn, liquidBlock, flooded);
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * @return frequency value for cavern region controller
     */
    private float calcCavernRegionSize(RegionSize cavernRegionSize, float cavernRegionCustomSize) {
        switch (cavernRegionSize) {
            case Small:
                return .01f;
            case Large:
                return .005f;
            case ExtraLarge:
                return .001f;
            case Custom:
                return cavernRegionCustomSize;
            default: // Medium
                return .007f;
        }
    }

//    private float calcFloodedSmoothAmp(boolean flooded, BlockPos colPos) {
//        float dist1away, dist2away;
//        if (flooded)
//        {
//            dist2away = isNonOceanAtDistanceFromPos(colPos, 2);
//            if (dist2away != 0)
//            {
//                dist1away = isNonOceanAtDistanceFromPos(colPos, 1);
//
//                if (dist1away == 1)
//                    return -1;
//                else if (dist1away > 0)
//                    return .5f * dist1away;
//
//                return .7f;
//            }
//        }
//        else {
//            if (isOceanAtDistanceFromPos(colPos, 2)) {
//                if (isOceanAtDistanceFromPos(colPos, 1)) {
//                    return .2f;
//                }
//                return .4f;
//            }
//        }
//        return 1;
//    }

    private float getDistFactor(BlockPos pos, int distance, Function<Biome.TempCategory, Boolean> isTargetBiome) {
        // First check unit circle
        // Cardinal directions
        if (
            isTargetBiome.apply(world.getBiome(pos.north()).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.east()).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.south()).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.west()).getTempCategory())
        ) {
//            return 1f / (distance * 2);
            return 0;
        }
        // Corners
        if (
            isTargetBiome.apply(world.getBiome(pos.north().east()).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.east().south()).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.south().west()).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.west().north()).getTempCategory())
        ) {
            return 2f / (distance * 2);
        }

        // 2-circle
        // Cardinal directions
        if (
            isTargetBiome.apply(world.getBiome(pos.north(2)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.east(2)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.south(2)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.west(2)).getTempCategory())
        ) {
            return 2f / (distance * 2);
        }
        // 3 away
        if (
            isTargetBiome.apply(world.getBiome(pos.north(2).east(1)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.north(2).west(1)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.east(2).north(1)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.east(2).south(1)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.south(2).east(1)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.south(2).west()).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.west(2).south(1)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.west(2).north(1)).getTempCategory())
        ) {
            return 3f / (distance * 2);
        }
        // Corners
        if (
            isTargetBiome.apply(world.getBiome(pos.north(2).east(2)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.east(2).south(2)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.south(2).west(2)).getTempCategory()) ||
            isTargetBiome.apply(world.getBiome(pos.west(2).north(2)).getTempCategory())
        ) {
            return 1;
        }
        return 1;
    }

    private float isOceanAtDistanceFromPos(BlockPos pos, int distance) {
        for (int z = 0; z <= distance; z++) {
            for (int x = 0; x <= distance; x++) {
                if (x == distance || z == distance) {
                    if (
                        world.getBiome(pos.north(z).west(x)).getTempCategory() == Biome.TempCategory.OCEAN ||
                            world.getBiome(pos.north(z).east(x)).getTempCategory() == Biome.TempCategory.OCEAN ||
                            world.getBiome(pos.south(z).west(x)).getTempCategory() == Biome.TempCategory.OCEAN ||
                            world.getBiome(pos.south(z).east(x)).getTempCategory() == Biome.TempCategory.OCEAN
                    ) {
                        return (float)(x + z) / distance;
                    }
                }
            }
        }
        return 0;
    }

    private float isNonOceanAtDistanceFromPos(BlockPos pos, int distance) {
        float min = 99;
        for (int z = 0; z <= distance; z++) {
            for (int x = 0; x <= distance; x++) {
                if (x == distance || z == distance) {
                    if (
                        world.getBiome(pos.north(z).west(x)).getTempCategory() != Biome.TempCategory.OCEAN ||
                            world.getBiome(pos.north(z).east(x)).getTempCategory() != Biome.TempCategory.OCEAN ||
                            world.getBiome(pos.south(z).west(x)).getTempCategory() != Biome.TempCategory.OCEAN ||
                            world.getBiome(pos.south(z).east(x)).getTempCategory() != Biome.TempCategory.OCEAN
                    ) {
                        min = Math.min((float)(x + z) / distance, min);
                        if (x + z == distance) return min; // lowest it could possibly be
                    }
                }
            }
        }
        return min == 99 ? -1 : min;
    }
}
