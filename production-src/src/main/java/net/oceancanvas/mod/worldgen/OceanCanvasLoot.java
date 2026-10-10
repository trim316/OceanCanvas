package net.oceancanvas.mod.worldgen;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/** Adds rare renewable tree starts to vanilla shipwreck supply chests. */
public final class OceanCanvasLoot {

	// Independent chance per tree type, deliberately in the requested 5-10% range.
	private static final float SAPLING_CHANCE = 0.07F;

	private OceanCanvasLoot() {
	}

	public static void register() {
		LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
			if (!BuiltInLootTables.SHIPWRECK_SUPPLY.equals(key) || !source.isBuiltin()) {
				return;
			}

			addRareSapling(tableBuilder, Items.OAK_SAPLING, 1);
			addRareSapling(tableBuilder, Items.SPRUCE_SAPLING, 1);
			addRareSapling(tableBuilder, Items.BIRCH_SAPLING, 1);
			addRareSapling(tableBuilder, Items.JUNGLE_SAPLING, 1);
			addRareSapling(tableBuilder, Items.ACACIA_SAPLING, 1);
			// Dark oak cannot grow from a single sapling, so a successful rare
			// roll gives the minimum useful set rather than a dead-end reward.
			addRareSapling(tableBuilder, Items.DARK_OAK_SAPLING, 4);
			addRareSapling(tableBuilder, Items.CHERRY_SAPLING, 1);
			addRareSapling(tableBuilder, Items.MANGROVE_PROPAGULE, 1);
			addRareSapling(tableBuilder, Items.PALE_OAK_SAPLING, 1);
		});
	}

	private static void addRareSapling(net.minecraft.world.level.storage.loot.LootTable.Builder tableBuilder,
			ItemLike item, int count) {
		LootPool.Builder pool = LootPool.lootPool()
				.setRolls(ConstantValue.exactly(1.0F))
				.add(LootItem.lootTableItem(item)
						.apply(SetItemCountFunction.setCount(ConstantValue.exactly((float) count))))
				.when(LootItemRandomChanceCondition.randomChance(SAPLING_CHANCE));
		tableBuilder.withPool(pool);
	}
}
