package xyz.ivyhq.lanature.client.settings;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Bedrock text with {@code §} formatting as a Java component. Bedrock shares Java's sixteen colours and {@code §k},
 * {@code §l}, {@code §o}, {@code §r}, but has no strikethrough or underline: its {@code §g} to {@code §v} are the
 * "material" colours, {@code §m} redstone and {@code §n} copper among them. Java's font would read those two as
 * strikethrough and underline, so the codes become styles here and never reach it.
 */
public final class BedrockText {
	private BedrockText() {
	}

	/** A language key's text in the current language, converted; the key itself when it has none. */
	public static Component translate(String key) {
		return parse(Language.getInstance().getOrDefault(key, key));
	}

	public static Component parse(String text) {
		MutableComponent out = Component.empty();
		Style style = Style.EMPTY;
		StringBuilder run = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§' && i + 1 < text.length()) {
				if (!run.isEmpty()) {
					out.append(Component.literal(run.toString()).withStyle(style));
					run.setLength(0);
				}
				style = apply(style, Character.toLowerCase(text.charAt(++i)));
			} else {
				run.append(c);
			}
		}
		if (!run.isEmpty()) {
			out.append(Component.literal(run.toString()).withStyle(style));
		}
		return out;
	}

	private static Style apply(Style style, char code) {
		Integer material = material(code);
		if (material != null) {
			// a colour code also clears bold and italic, as in Java
			return Style.EMPTY.withColor(TextColor.fromRgb(material));
		}
		return switch (code) {
			case 'k' -> style.withObfuscated(true);
			case 'l' -> style.withBold(true);
			case 'o' -> style.withItalic(true);
			case 'r' -> Style.EMPTY;
			default -> (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') ? Style.EMPTY.withColor(ChatFormatting.getByCode(code)) : style;
		};
	}

	/** Bedrock's material colours. */
	private static Integer material(char code) {
		return switch (code) {
			case 'g' -> 0xDDD605; // minecoin gold
			case 'h' -> 0xE3D4D1; // quartz
			case 'i' -> 0xCECACA; // iron
			case 'j' -> 0x443A3B; // netherite
			case 'm' -> 0x971607; // redstone
			case 'n' -> 0xB4684D; // copper
			case 'p' -> 0xDEB12D; // gold
			case 'q' -> 0x47A036; // emerald
			case 's' -> 0x2CBAA8; // diamond
			case 't' -> 0x21497B; // lapis
			case 'u' -> 0x9A5CC6; // amethyst
			case 'v' -> 0xEB7114; // resin
			default -> null;
		};
	}
}
