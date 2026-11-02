package org.bitwisemadness.tensuraxaerointegration.tensura;

import io.github.manasmods.manascore.skill.api.ManasSkillInstance;
import io.github.manasmods.manascore.skill.api.SkillAPI;
import io.github.manasmods.manascore.skill.api.Skills;
import io.github.manasmods.tensura.config.AreaMagiculeConfig;
import io.github.manasmods.tensura.storage.TensuraStorages;
import io.github.manasmods.tensura.storage.chunk.ChunkStorage;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bitwisemadness.tensuraxaerointegration.skill.SkillGate;

/**
 * The only class in this mod that imports Tensura: Reincarnated types.
 *
 * <p>Never reference this class directly — always go through
 * {@link TensuraBridge}, which checks {@code ModList} first. Loading this class
 * without Tensura installed would throw {@link NoClassDefFoundError}.</p>
 *
 * <p>Chunk magicule is read server-side only: Tensura stores it in the server's
 * {@code LevelChunk} storage, and a client-side {@code ChunkStorage} reports the
 * base value and is never synchronised, so reading it there would silently invent
 * data. Player skills are different - they are synced to the owning client - so
 * {@link #senses} is meaningful on both sides.</p>
 */
final class TensuraAccess {

    private TensuraAccess() {
    }

    /**
     * Reads a loaded chunk's authoritative concentration.
     *
     * <p>The {@code hasChunk} guard is what guarantees no chunk is loaded or
     * generated to answer a map query; an unloaded chunk is reported as
     * unavailable rather than as Tensura's baseline fallback.</p>
     */
    static OptionalDouble read(ServerLevel level, int chunkX, int chunkZ) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return OptionalDouble.empty();
        }
        return read(level.getChunk(chunkX, chunkZ));
    }

    /** Reads a chunk the caller already holds (e.g. from a chunk-tick event). */
    static OptionalDouble read(LevelChunk chunk) {
        if (chunk == null || chunk.getLevel().isClientSide()) {
            return OptionalDouble.empty();
        }
        ChunkStorage storage = TensuraStorages.getChunkFrom(chunk);
        if (storage == null) {
            return OptionalDouble.empty();
        }
        double value = storage.getMagicule();
        if (!Double.isFinite(value) || value < 0.0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    /**
     * Tensura's configured normal baseline ({@code baseMagicule}), which the
     * classification multipliers are relative to.
     *
     * <p>Read from {@code ChunkStorage.CONFIG}, the same object Tensura's own
     * storage uses. Empty when the config has not been registered (very early
     * loading) or holds a non-positive value — this mod does not fabricate a
     * baseline it cannot verify.</p>
     */
    static OptionalDouble baseline() {
        AreaMagiculeConfig config = ChunkStorage.CONFIG;
        if (config == null) {
            return OptionalDouble.empty();
        }
        double base = config.baseMagicule;
        if (!Double.isFinite(base) || base <= 0.0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(base);
    }

    /**
     * Whether an entity's own skills satisfy the sensing gate.
     *
     * <p>Reads the entity's synced skill storage, so this is meaningful on both
     * sides: the server checks it before sending, the client checks its own player
     * before drawing. A skill id that is not a valid resource location is simply
     * not held, which is how a mistyped config id behaves: the gate stays closed
     * for that skill rather than crashing.</p>
     */
    static boolean senses(SkillGate gate, LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        Skills skills = SkillAPI.getSkillsFrom(entity);
        return gate.allows(new SkillGate.SkillQuery() {
            @Override
            public boolean has(String skillId) {
                return instance(skills, skillId) != null;
            }

            @Override
            public boolean mastered(String skillId) {
                ManasSkillInstance held = instance(skills, skillId);
                return held != null && held.isMastered(entity);
            }

            @Override
            public boolean toggled(String skillId) {
                ManasSkillInstance held = instance(skills, skillId);
                return held != null && held.isToggled();
            }
        });
    }

    private static ManasSkillInstance instance(Skills skills, String skillId) {
        ResourceLocation id = ResourceLocation.tryParse(skillId);
        if (id == null) {
            return null;
        }
        return skills.getSkill(id).orElse(null);
    }

    /** The per-chunk configured maximum, for diagnostics; never used for display. */
    static OptionalDouble maxOf(LevelChunk chunk) {
        if (chunk == null || chunk.getLevel().isClientSide()) {
            return OptionalDouble.empty();
        }
        ChunkStorage storage = TensuraStorages.getChunkFrom(chunk);
        if (storage == null) {
            return OptionalDouble.empty();
        }
        double value = storage.getMaxMagicule();
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }
}
