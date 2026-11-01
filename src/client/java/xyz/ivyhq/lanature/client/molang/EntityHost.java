package xyz.ivyhq.lanature.client.molang;

import net.minecraft.world.entity.Entity;

/**
 * A query host standing for an entity, such as a client entity's running scripts; entity queries act on {@link #entity},
 * read between its last two ticks at {@link #partialTick} where they change smoothly (rotations, walk, age).
 */
public interface EntityHost {
	Entity entity();

	/** Whether the host is an attachable, a held item's model, whose {@link #entity} is its holder. */
	default boolean attached() {
		return false;
	}

	default float partialTick() {
		return 1;
	}
}
