package net.oceancanvas.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/** Adds OceanCanvas sapling access to vanilla shipwreck supply chests without replacing vanilla loot. */
public final class ShipwreckSaplingLootBootstrap implements ModInitializer {
    private static final float CHANCE_PER_SAPLING = 0.05F;
    private static final String SHIPWRECK_SUPPLY = "minecraft:chests/shipwreck_supply";

    private static final Item[] SAPLINGS = {
            Items.OAK_SAPLING,
            Items.SPRUCE_SAPLING,
            Items.BIRCH_SAPLING,
            Items.JUNGLE_SAPLING,
            Items.ACACIA_SAPLING,
            Items.DARK_OAK_SAPLING,
            Items.CHERRY_SAPLING,
            Items.PALE_OAK_SAPLING,
            Items.MANGROVE_PROPAGULE
    };

    @Override
    public void onInitialize() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, holder) -> {
            if (!SHIPWRECK_SUPPLY.equals(key.identifier().toString())) return;

            // Add one independent pool per tree-start item. Each pool rolls once
            // and succeeds with 5% probability, so every sapling has its own 5%
            // chance and multiple different saplings can very rarely appear in
            // the same chest. Existing vanilla loot pools remain untouched.
            for (Item sapling : SAPLINGS) {
                LootPool.Builder pool = LootPool.lootPool()
                        .setRolls(ConstantValue.exactly(1.0F))
                        .when(LootItemRandomChanceCondition.randomChance(CHANCE_PER_SAPLING))
                        .add(LootItem.lootTableItem(sapling));
                tableBuilder.withPool(pool);
            }
        });

        OceanCanvas.LOGGER.info("(Ocean Canvas Core) SHIPWRECK-SAPLING-LOOT-REGISTERED chancePerSapling={} saplingKinds={}",
                CHANCE_PER_SAPLING, SAPLINGS.length);
    }
}
