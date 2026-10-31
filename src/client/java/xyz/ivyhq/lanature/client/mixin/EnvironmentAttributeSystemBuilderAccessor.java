package xyz.ivyhq.lanature.client.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The layers a builder holds, so the client level's weather fog colour layer can be wrapped. */
@Mixin(EnvironmentAttributeSystem.Builder.class)
public interface EnvironmentAttributeSystemBuilderAccessor {
	@Accessor("layersByAttribute")
	Map<EnvironmentAttribute<?>, List<EnvironmentAttributeLayer<?>>> lanature$layers();
}
