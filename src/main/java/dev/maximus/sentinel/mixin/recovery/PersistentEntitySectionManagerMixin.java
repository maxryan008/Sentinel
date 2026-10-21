package dev.maximus.sentinel.mixin.recovery;

import dev.maximus.sentinel.Sentinel;

import dev.maximus.sentinel.recovery.entity.SentinelEntityGeneration;
import dev.maximus.sentinel.recovery.entity.SentinelEntityGenerationLedger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import org.spongepowered.asm.mixin.Mixin;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {

    /**
     * Intercepts entity admission into Minecraft's
     * persistent entity manager.
     *
     * This catches entities arriving from previously
     * unloaded chunks, including entity-region records
     * that were written before Sentinel performed
     * the rollback.
     *
     * Entities with UUIDs not tracked by Sentinel
     * are admitted normally.
     */
    @Inject(
            method = "addEntity",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sentinel$rejectStaleEntity(
            EntityAccess access,
            boolean fromDisk,
            CallbackInfoReturnable<Boolean> cir
    ) {

        if (!(access instanceof Entity entity)) {
            return;
        }

        /*
         * Player storage is a separate system.
         * This feature must never reject players.
         */
        if (entity instanceof Player) {
            return;
        }

        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        MinecraftServer server =
                level.getServer();

        if (server == null) {
            return;
        }

        UUID entityUuid =
                entity.getUUID();

        UUID generation =
                ((SentinelEntityGeneration) entity)
                        .sentinel$getRestorationGeneration();

        SentinelEntityGenerationLedger ledger =
                SentinelEntityGenerationLedger.get(server);

        if (ledger.accepts(
                entityUuid,
                generation
        )) {

            return;
        }

        /*
         * The entity has a UUID whose authoritative
         * generation is different from its own.
         *
         * Do not allow this old copy into the entity
         * manager.
         */
        Sentinel.LOGGER.info(
                "Rejected stale entity copy: "
                        + "uuid={}, type={}, "
                        + "dimension={}, chunk={}, "
                        + "savedGeneration={}, expectedGeneration={}",
                entityUuid,
                entity.getType(),
                level.dimension().location(),
                entity.chunkPosition(),
                generation,
                ledger.generationFor(entityUuid)
        );

        cir.setReturnValue(false);
    }
}