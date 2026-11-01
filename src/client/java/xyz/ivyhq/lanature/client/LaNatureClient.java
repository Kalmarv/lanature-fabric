package xyz.ivyhq.lanature.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import xyz.ivyhq.lanature.client.bedrock.BedrockResources;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.environment.Colormaps;
import xyz.ivyhq.lanature.client.environment.WaterOpacity;
import xyz.ivyhq.lanature.client.molang.ClientQueries;
import xyz.ivyhq.lanature.client.settings.PackSettingsScreen;

/**
 * Client entry point: registers the Bedrock data loader, the Molang queries, the colour-mapped block tints and the
 * settings key (unbound by default).
 */
public final class LaNatureClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientQueries.register();
		EntityScripts.registerQueries();
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> Colormaps.registerBlockTints(mc.getBlockColors()));
		WaterOpacity.register();
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(BedrockResources.ID, new BedrockResources());
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lanature", "lanature"));
		KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.lanature.settings", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (settingsKey.consumeClick()) {
				mc.gui.setScreen(new PackSettingsScreen(mc.gui.screen()));
			}
		});
	}
}
