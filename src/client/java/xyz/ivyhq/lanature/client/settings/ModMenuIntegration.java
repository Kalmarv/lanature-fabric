package xyz.ivyhq.lanature.client.settings;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's config button opens the pack settings (Mod Menu is optional; this loads only when it is installed). */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return PackSettingsScreen::new;
	}
}
