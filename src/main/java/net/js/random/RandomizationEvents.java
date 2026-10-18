package net.js.random;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import net.neoforged.neoforge.event.LootTableLoadEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RandomizationEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(RandomizationEvents.class);
    private static final TagKey<Item> RANDOM_ITEMS = TagKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath(RandomnessMod.MOD_ID, "item")
    );
    private static final TagKey<Block> RANDOM_BLOCKS = TagKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(RandomnessMod.MOD_ID, "block")
    );
    private static volatile List<BlockState> WORLDGEN_CANDIDATES;

    private RandomizationEvents() {}

    static void onBlockDrops(BlockDropsEvent event) {
        if (!(event.getBreaker() instanceof Player)
                || (event.getBlockEntity() != null && !(event.getBlockEntity() instanceof SignBlockEntity))) {
            return;
        }

        HolderSet.Named<Item> itemTag = event.getLevel().registryAccess()
                .lookupOrThrow(Registries.ITEM)
                .get(RANDOM_ITEMS)
                .orElse(null);
        if (itemTag == null) {
            LOGGER.error("Cannot randomize block drops: missing item tag {}", RANDOM_ITEMS.location());
            return;
        }

        List<Holder<Item>> candidates = itemTag.stream()
                .filter(item -> item.value() != Items.AIR)
                .toList();
        if (candidates.isEmpty()) {
            LOGGER.error("Cannot randomize block drops: item tag {} has no non-air items", RANDOM_ITEMS.location());
            return;
        }

        Holder<Item> selected = candidates.get(event.getLevel().getRandom().nextInt(candidates.size()));
        event.getDrops().clear();
        event.getDrops().add(new ItemEntity(
                event.getLevel(),
                event.getPos().getX() + 0.5,
                event.getPos().getY() + 0.5,
                event.getPos().getZ() + 0.5,
                new ItemStack(selected.value())
        ));
    }

    static void onLootTableLoad(LootTableLoadEvent event) {
        if (!event.getName().getPath().startsWith("chests/")) {
            return;
        }

        HolderSet.Named<Item> itemTag = event.getRegistries()
                .lookupOrThrow(Registries.ITEM)
                .get(RANDOM_ITEMS)
                .orElse(null);
        if (itemTag == null) {
            LOGGER.error("Cannot randomize chest loot: missing item tag {}", RANDOM_ITEMS.location());
            return;
        }

        LootTable randomizedChestLoot = LootTable.lootTable()
                .setParamSet(LootContextParamSets.CHEST)
                .withPool(LootPool.lootPool()
                        .setRolls(ContextIntProviders.exactly(3))
                        .add(TagEntry.expandTag(itemTag)))
                .build();
        event.setTable(randomizedChestLoot);
    }

    public static void randomizeGeneratedChunk(ChunkAccess chunk) {
        List<BlockState> candidates = worldgenCandidates();
        if (candidates.isEmpty()) {
            LOGGER.error("Cannot randomize generated chunk: block tag {} is empty", RANDOM_BLOCKS.location());
            return;
        }

        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        RandomSource random = RandomSource.create(chunkPos.pack());
        LevelChunkSection[] sections = chunk.getSections();
        boolean changed = false;
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) {
                continue;
            }

            int sectionY = chunk.getMinSectionY() + sectionIndex;
            int baseY = sectionY << 4;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState original = section.getBlockState(x, y, z);
                        if (original.isAir() || preserveGeneratedBlock(original)) {
                            continue;
                        }

                        if (original.hasBlockEntity()) {
                            pos.set(chunkPos.getMinBlockX() + x, baseY + y, chunkPos.getMinBlockZ() + z);
                            chunk.removeBlockEntity(pos);
                        }
                        section.setBlockState(x, y, z, candidates.get(random.nextInt(candidates.size())), false);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            Heightmap.primeHeightmaps(chunk, chunk.getPersistedStatus().heightmapsAfter());
        }
    }

    private static boolean preserveGeneratedBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof AbstractChestBlock<?>
                || block == Blocks.SPAWNER
                || block == Blocks.TRIAL_SPAWNER
                || block == Blocks.END_PORTAL
                || block == Blocks.END_PORTAL_FRAME;
    }

    private static List<BlockState> worldgenCandidates() {
        List<BlockState> candidates = WORLDGEN_CANDIDATES;
        if (candidates != null) {
            return candidates;
        }

        synchronized (RandomizationEvents.class) {
            candidates = WORLDGEN_CANDIDATES;
            if (candidates == null) {
                List<BlockState> discovered = new ArrayList<>();
                BuiltInRegistries.BLOCK.getTagOrEmpty(RANDOM_BLOCKS).forEach(holder -> {
                    discovered.add(holder.value().defaultBlockState());
                });
                candidates = List.copyOf(discovered);
                WORLDGEN_CANDIDATES = candidates;
            }
        }
        return candidates;
    }
}
