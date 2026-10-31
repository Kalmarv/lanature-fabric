package xyz.ivyhq.lanature.client.settings;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.client.settings.PackSettings.Option;
import xyz.ivyhq.lanature.client.settings.PackSettings.Setting;

/**
 * The pack's settings, as Bedrock lists them under the pack in its global resources screen: each setting's text from
 * the pack's language files, then its control. Changes apply at once (the pack's scripts read them every frame) and
 * are saved when the screen closes.
 */
public final class PackSettingsScreen extends Screen {
	private static final int WIDTH = 310;
	private final @Nullable Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private @Nullable ScrollableLayout body;

	public PackSettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("lanature.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		LinearLayout list = LinearLayout.vertical().spacing(4);
		list.defaultCellSetting().alignHorizontallyLeft();
		List<Setting> settings = PackSettings.get().all();
		if (settings.isEmpty()) {
			list.addChild(new StringWidget(Component.translatable("lanature.settings.none"), font));
		}
		for (Setting s : settings) {
			if (!s.text().isEmpty()) {
				list.addChild(new MultiLineTextWidget(BedrockText.translate(s.text()), font).setMaxWidth(WIDTH));
			}
			switch (s.type()) {
				case SLIDER -> list.addChild(new Slider(s));
				case TOGGLE -> list.addChild(CycleButton.onOffBuilder(PackSettings.get().enabled(s.name()))
					.create(0, 0, WIDTH, 20, Component.empty(), (b, v) -> PackSettings.get().set(s.name(), v)));
				case DROPDOWN -> list.addChild(dropdown(s));
				case MULTISELECT -> multiselect(s, list);
				case LABEL -> {
				}
			}
			list.addChild(new SpacerElement(WIDTH, 6));
		}
		body = new ScrollableLayout(minecraft, list, layout.getContentHeight());
		layout.addToContents(body);
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("lanature.settings.reset"), b -> {
			PackSettings.get().reset();
			rebuildWidgets();
		}).width(150).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(150).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private CycleButton<Option> dropdown(Setting s) {
		Object value = PackSettings.get().value(s.name());
		Option current = s.options().stream().filter(o -> o.name().equals(value)).findFirst().orElse(s.options().getFirst());
		return CycleButton.<Option>builder(o -> BedrockText.translate(o.text()), current)
			.withValues(s.options())
			.displayOnlyValue()
			.create(0, 0, WIDTH, 20, Component.empty(), (b, o) -> PackSettings.get().set(s.name(), o.name()));
	}

	private void multiselect(Setting s, LinearLayout list) {
		for (Option o : s.options()) {
			list.addChild(Checkbox.builder(BedrockText.translate(o.text()), font)
				.maxWidth(WIDTH)
				.selected(PackSettings.get().selected(s.name(), o.name()))
				.onValueChange((box, on) -> {
					Set<String> chosen = new LinkedHashSet<>();
					if (PackSettings.get().value(s.name()) instanceof Set<?> current) {
						current.forEach(x -> chosen.add(x.toString()));
					}
					if (on) {
						chosen.add(o.name());
					} else {
						chosen.remove(o.name());
					}
					PackSettings.get().set(s.name(), chosen);
				})
				.build());
		}
	}

	@Override
	protected void repositionElements() {
		if (body != null) {
			body.arrangeElements();
			body.setMaxHeight(layout.getContentHeight());
		}
		layout.arrangeElements();
	}

	@Override
	public void removed() {
		PackSettings.get().save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** A slider over the setting's range, moving in its steps, showing its value. */
	private static final class Slider extends AbstractSliderButton {
		private final Setting setting;

		Slider(Setting setting) {
			super(0, 0, WIDTH, 20, Component.empty(), fraction(setting, PackSettings.get().number(setting.name())));
			this.setting = setting;
			updateMessage();
		}

		private static double fraction(Setting s, double v) {
			return s.max() == s.min() ? 0 : Math.clamp((v - s.min()) / (s.max() - s.min()), 0, 1);
		}

		private double settingValue() {
			double v = setting.min() + value * (setting.max() - setting.min());
			if (setting.step() > 0) {
				v = setting.min() + Math.round((v - setting.min()) / setting.step()) * setting.step();
			}
			return Math.clamp(v, setting.min(), setting.max());
		}

		@Override
		protected void updateMessage() {
			double v = settingValue();
			String text = v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "");
			setMessage(Component.literal(text));
		}

		@Override
		protected void applyValue() {
			PackSettings.get().set(setting.name(), settingValue());
		}
	}
}
