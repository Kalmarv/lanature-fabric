package xyz.ivyhq.lanature.converter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** PNG and TGA decoding, PNG encoding, and a pixel-identity hash. */
public final class Images {
	private Images() {
	}

	public static BufferedImage decode(String path, byte[] bytes) throws IOException {
		if (path.endsWith(".tga")) {
			return decodeTga(bytes);
		}
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
		if (img == null) {
			throw new IOException(path + ": not a readable image");
		}
		return img;
	}

	public static byte[] png(BufferedImage img) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	/** SHA-256 over width, height and straight ARGB pixels; equal for the same picture in any container. */
	public static String pixelHash(BufferedImage img) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			int w = img.getWidth();
			int h = img.getHeight();
			int[] row = new int[w];
			byte[] buf = new byte[w * 4];
			md.update(new byte[] {(byte) (w >> 8), (byte) w, (byte) (h >> 8), (byte) h});
			for (int y = 0; y < h; y++) {
				img.getRGB(0, y, w, 1, row, 0, w);
				for (int x = 0; x < w; x++) {
					int argb = row[x];
					if ((argb >>> 24) == 0) {
						argb = 0; // fully transparent pixels compare equal whatever colour they carry
					}
					buf[x * 4] = (byte) (argb >>> 24);
					buf[x * 4 + 1] = (byte) (argb >>> 16);
					buf[x * 4 + 2] = (byte) (argb >>> 8);
					buf[x * 4 + 3] = (byte) argb;
				}
				md.update(buf);
			}
			return HexFormat.of().formatHex(md.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	/** Truevision TGA: uncompressed or RLE, true-colour (24/32 bpp) or greyscale (8 bpp). */
	static BufferedImage decodeTga(byte[] b) throws IOException {
		if (b.length < 18) {
			throw new IOException("TGA too short");
		}
		int idLength = b[0] & 0xFF;
		int colorMapType = b[1] & 0xFF;
		int type = b[2] & 0xFF;
		int colorMapLength = u16(b, 5);
		int colorMapEntryBits = b[7] & 0xFF;
		int width = u16(b, 12);
		int height = u16(b, 14);
		int bpp = b[16] & 0xFF;
		int descriptor = b[17] & 0xFF;
		if (colorMapType != 0 || (type != 2 && type != 3 && type != 10 && type != 11)) {
			throw new IOException("unsupported TGA type " + type + " (colour map " + colorMapType + ")");
		}
		boolean grey = type == 3 || type == 11;
		boolean rle = type >= 9;
		int bytesPerPixel = bpp / 8;
		if (grey ? bpp != 8 : bpp != 24 && bpp != 32) {
			throw new IOException("unsupported TGA depth " + bpp);
		}
		int pos = 18 + idLength + colorMapLength * ((colorMapEntryBits + 7) / 8);
		int[] argb = new int[width * height];
		int count = width * height;
		int i = 0;
		while (i < count) {
			if (rle) {
				int header = b[pos++] & 0xFF;
				int run = (header & 0x7F) + 1;
				if ((header & 0x80) != 0) {
					int px = pixel(b, pos, bytesPerPixel, grey);
					pos += bytesPerPixel;
					for (int k = 0; k < run && i < count; k++) {
						argb[i++] = px;
					}
				} else {
					for (int k = 0; k < run && i < count; k++) {
						argb[i++] = pixel(b, pos, bytesPerPixel, grey);
						pos += bytesPerPixel;
					}
				}
			} else {
				argb[i++] = pixel(b, pos, bytesPerPixel, grey);
				pos += bytesPerPixel;
			}
		}
		boolean topOrigin = (descriptor & 0x20) != 0;
		boolean rightOrigin = (descriptor & 0x10) != 0;
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			int srcY = topOrigin ? y : height - 1 - y;
			for (int x = 0; x < width; x++) {
				int srcX = rightOrigin ? width - 1 - x : x;
				img.setRGB(x, y, argb[srcY * width + srcX]);
			}
		}
		return img;
	}

	private static int pixel(byte[] b, int pos, int bytesPerPixel, boolean grey) {
		if (grey) {
			int v = b[pos] & 0xFF;
			return 0xFF000000 | v << 16 | v << 8 | v;
		}
		int blue = b[pos] & 0xFF;
		int green = b[pos + 1] & 0xFF;
		int red = b[pos + 2] & 0xFF;
		int alpha = bytesPerPixel == 4 ? b[pos + 3] & 0xFF : 0xFF;
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	private static int u16(byte[] b, int off) {
		return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
	}
}
