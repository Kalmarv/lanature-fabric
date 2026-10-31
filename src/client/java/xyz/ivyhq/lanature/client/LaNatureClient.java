package xyz.ivyhq.lanature.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import xyz.ivyhq.lanature.client.bedrock.BedrockResources;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.molang.ClientQueries;

/** Client entry point: registers the Bedrock data loader and the Molang queries. */
public final class LaNatureClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientQueries.register();
		EntityScripts.registerQueries();
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(BedrockResources.ID, new BedrockResources());
	}
}
