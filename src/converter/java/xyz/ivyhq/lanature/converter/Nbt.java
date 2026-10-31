package xyz.ivyhq.lanature.converter;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal reader for gzip-compressed, big-endian (Java edition) NBT. Compounds become ordered maps, lists become
 * lists, numbers become boxed Java numbers and arrays become primitive arrays.
 */
public final class Nbt {
	private Nbt() {
	}

	public static Map<String, Object> readCompressed(Path path) throws IOException {
		try (InputStream in = Files.newInputStream(path);
			 DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
			byte type = data.readByte();
			if (type != 10) {
				throw new IOException(path + ": root tag is type " + type + ", expected a compound");
			}
			data.readUTF();
			return readCompound(data);
		}
	}

	private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
		Map<String, Object> out = new LinkedHashMap<>();
		while (true) {
			byte type = in.readByte();
			if (type == 0) {
				return out;
			}
			String name = in.readUTF();
			out.put(name, readPayload(in, type));
		}
	}

	private static Object readPayload(DataInputStream in, byte type) throws IOException {
		return switch (type) {
			case 1 -> in.readByte();
			case 2 -> in.readShort();
			case 3 -> in.readInt();
			case 4 -> in.readLong();
			case 5 -> in.readFloat();
			case 6 -> in.readDouble();
			case 7 -> {
				byte[] bytes = new byte[in.readInt()];
				in.readFully(bytes);
				yield bytes;
			}
			case 8 -> in.readUTF();
			case 9 -> {
				byte elementType = in.readByte();
				int length = in.readInt();
				List<Object> list = new ArrayList<>(Math.max(length, 0));
				for (int i = 0; i < length; i++) {
					list.add(readPayload(in, elementType));
				}
				yield list;
			}
			case 10 -> readCompound(in);
			case 11 -> {
				int[] ints = new int[in.readInt()];
				for (int i = 0; i < ints.length; i++) {
					ints[i] = in.readInt();
				}
				yield ints;
			}
			case 12 -> {
				long[] longs = new long[in.readInt()];
				for (int i = 0; i < longs.length; i++) {
					longs[i] = in.readLong();
				}
				yield longs;
			}
			default -> throw new IOException("unknown NBT tag type " + type);
		};
	}
}
