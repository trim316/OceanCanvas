package net.oceancanvas.mod.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Keeps hostile mobs out of regions whose rule says so - the third kind
 * of rule a region can carry, alongside structures and biomes.
 *
 * <p><b>Why this belongs in a mod that refuses to place blocks.</b> The
 * point of an ocean canvas is building on it by hand, for a long time, in
 * one place. Doing that while creepers arrive is a genuinely bad
 * experience, and the usual answers are both bad: peaceful difficulty
 * turns the whole world off, and walling and lighting a site is real work
 * that has to be undone afterwards. Marking the site as "no hostile mobs"
 * is a rule about an area - the same shape as every other rule here - and
 * it changes nothing about the terrain.</p>
 *
 * <h2>What it does, precisely</h2>
 * <p>When an entity is added to a level, if it is hostile and its position
 * falls inside an ENABLED region whose {@code suppressHostileMobs} rule is
 * set, it is discarded on the next tick. That is the entire mechanism.</p>
 *
 * <p><b>Hostility is tested against {@link Enemy}, not {@code Monster}.</b>
 * That distinction is not pedantry: under official mappings {@code Monster}
 * is an abstract CLASS (a walking hostile mob), while {@code Enemy} is the
 * marker INTERFACE vanilla actually uses to mean "this is hostile".
 * Testing the class would silently miss slimes, magma cubes, ghasts,
 * phantoms, shulkers, hoglins and zoglins - so a build site advertised as
 * kept clear would still have phantoms overhead and slimes underfoot,
 * which is a worse failure than the feature not existing. Using the
 * interface also means a modded hostile mob that declares itself hostile
 * is covered, while a modded passive one is not.</p>
 *
 * <p><b>Remove-on-load rather than prevent-spawn, deliberately.</b> There
 * is no Fabric API event for "should this mob be allowed to spawn" - that
 * would need a mixin into vanilla's spawn placement, which is a much
 * larger and more fragile commitment than this feature is worth, and one
 * that fights with every other mod that touches spawning.
 * {@link ServerEntityEvents#ENTITY_LOAD} is a plain, long-stable Fabric
 * event, and discarding an entity the same tick it appears is
 * indistinguishable in play from it never having spawned. The cost is
 * that vanilla does the spawn work first and this throws it away - real,
 * but small, and only inside regions someone explicitly asked to keep
 * clear.</p>
 *
 * <h2>What it deliberately does NOT do</h2>
 * <ul>
 *   <li><b>Only hostile mobs.</b> Not villagers, animals, item frames,
 *       armour stands, boats, minecarts, dropped items, arrows or
 *       players.</li>
 *   <li><b>Never a mob someone deliberately placed.</b> A custom-named
 *       mob, or one flagged persistent (a spawn egg placement, a
 *       {@code /summon} with persistence, a mob carefully led somewhere),
 *       is left alone. {@code discard()} is unrecoverable - there is no
 *       drop and no undo - so quietly deleting a named zombie a player
 *       had a reason to keep would be the kind of loss this project's
 *       standing rule about eliminating real risk exists to prevent.</li>
 *   <li><b>Never affects blocks, spawners, or spawn rates elsewhere.</b>
 *       A region's rule is local to that region.</li>
 *   <li><b>Not a difficulty change.</b> Mobs already inside a region when
 *       the rule is switched on are removed as they load, which is the
 *       intended reading of "keep this area clear" - but nothing about
 *       the world's difficulty, gamerules, or spawning anywhere else is
 *       touched.</li>
 * </ul>
 *
 * <p><b>One case deliberately not special-cased:</b> a boss summoned
 * INSIDE a suppressed region (a wither) would be removed like any other
 * hostile mob, since it is neither named nor persistent at the moment it
 * appears. Recognising bosses would mean adding two more unconfirmed
 * vanilla class references for a situation that requires deliberately
 * summoning a boss inside an area explicitly marked "no hostile mobs" -
 * so the rule is left doing exactly what it says, and this paragraph
 * exists so that is a documented consequence rather than a surprise.</p>
 *
 * <p><b>Honest consequence worth knowing before enabling it:</b> because
 * this removes monsters as they load, a mob farm or a spawner inside a
 * region with this rule will stop producing. That is the rule working as
 * asked rather than a bug, but it is the one surprising interaction, so
 * the command and the map screen both say so.</p>
 *
 * <p><b>Cheap when unused.</b> Every callback starts with one boolean -
 * "does any region suppress mobs at all" - which is false for
 * essentially every world that has not asked for this, so the common case
 * costs a field read per entity load and nothing else.</p>
 *
 * <p><b>Risk profile:</b> {@code ServerEntityEvents.ENTITY_LOAD},
 * {@code Entity#discard()}, {@code Entity#hasCustomName()} and
 * {@code Mob#isPersistenceRequired()} are all long-standing and widely
 * used, and {@link Enemy} has been vanilla's hostile marker interface for
 * many versions. This is the lower-risk kind of unconfirmed - mature APIs
 * not yet checked against this exact build - rather than a guess at a
 * recent internal rename.</p>
 *
 * <p><b>v110:</b> dimension changes are covered using Fabric 26.2's verified
 * {@code ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL} callback.
 * The destination entity is passed through the exact same suppression rule
 * as an ordinary entity load. Same-dimension teleports remain the one narrow
 * documented gap because there is no equivalent low-cost Fabric callback and
 * Ocean Canvas will not add a continuous loaded-entity sweep for it.</p>
 */
public final class OceanCanvasMobSuppressor {

	private OceanCanvasMobSuppressor() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register(OceanCanvasMobSuppressor::removeIfSuppressed);
		// Fabric 26.2 renamed the old world-change hook to level-change.
		// Run the same local rule immediately after a non-player entity arrives
		// in its destination level; no global sweep and no terrain mutation.
		ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register((original, newEntity, origin, destination) ->
				removeIfSuppressed(newEntity, destination));
		// Same-dimension teleports still do not have a dedicated low-cost Fabric
		// callback. ENTITY_LOAD/level-change cover natural spawning, chunk load,
		// and portal/dimension travel without adding a recurring entity scan.
	}

	private static void removeIfSuppressed(Entity entity, ServerLevel world) {
		if (!(entity instanceof Enemy)) {
			return;
		}
		// Never take a mob someone deliberately placed or named - see the
		// class doc. Checked before the region lookup because it is the
		// cheaper test and the one that must never be skipped.
		if (entity.hasCustomName()) {
			return;
		}
		if (entity instanceof Mob mob && mob.isPersistenceRequired()) {
			return;
		}

		OceanCanvasPlayerZones zones = OceanCanvasPlayerZones.get(world);
		if (!zones.hasAnyMobSuppression()) {
			return;
		}
		BlockPos pos = entity.blockPosition();
		if (!zones.suppressesHostileMobsAt(pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}

		// Deferred by one tick rather than discarded inline. ENTITY_LOAD
		// fires while the level is still in the middle of adding this
		// entity to its own tracking structures, and discard() immediately
		// drives the removal path back through those same structures -
		// re-entrancy that is most likely to bite during a bulk chunk
		// entity load, which is exactly the hardest case to reproduce.
		// One tick later the add has completed and the removal is ordinary.
		// Imperceptible in play: the mob is gone before it can act.
		//
		// discard(), not kill(): this mob should behave as though it was
		// never here. Killing it would drop loot and XP into a region
		// someone is trying to keep quiet, and would turn a suppressed
		// region into a farm rather than leaving it alone.
		MinecraftServer server = world.getServer();
		if (server == null) {
			entity.discard();
			return;
		}
		server.execute(entity::discard);
	}
}
