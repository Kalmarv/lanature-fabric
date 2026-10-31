package xyz.ivyhq.lanature.client.molang;

import net.minecraft.world.entity.Entity;

/** A query host standing for an entity, such as a client entity's running scripts; entity queries act on {@link #entity}. */
public interface EntityHost {
	Entity entity();
}
