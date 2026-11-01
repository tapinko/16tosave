package sk.tapusik.sixteentosave.export;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A small NBT writer/reader for the world files the exporter creates itself ({@code level.dat},
 * saved data), so their structure is plain Java and unit-testable. Values: {@link Byte},
 * {@link Short}, {@link Integer}, {@link Long}, {@link Float}, {@link Double}, {@link String},
 * {@code byte[]}, {@code int[]}, {@code long[]}, {@link List} (all elements of one type) and
 * {@link Map} with {@link String} keys (a compound; use a {@link LinkedHashMap} for a stable order).
 */
public final class Nbt {
	static final int END = 0;
	static final int BYTE = 1;
	static final int SHORT = 2;
	static final int INT = 3;
	static final int LONG = 4;
	static final int FLOAT = 5;
	static final int DOUBLE = 6;
	static final int BYTE_ARRAY = 7;
	static final int STRING = 8;
	static final int LIST = 9;
	static final int COMPOUND = 10;
	static final int INT_ARRAY = 11;
	static final int LONG_ARRAY = 12;

	private static final int MAX_DEPTH = 512;

	private Nbt() {
	}

	/** A compound builder that keeps insertion order. */
	public static Map<String, Object> compound() {
		return new LinkedHashMap<>();
	}

	public static byte bool(boolean value) {
		return value ? (byte) 1 : (byte) 0;
	}

	// ---- writing --------------------------------------------------------------------------

	/** Gzip-compressed, unnamed root compound: the format of {@code level.dat} and saved data. */
	public static void writeCompressed(Path file, Map<String, Object> root) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
			write(out, root);
		}
	}

	public static void write(DataOutput out, Map<String, Object> root) throws IOException {
		out.writeByte(COMPOUND);
		out.writeUTF("");
		writePayload(out, root, 0);
	}

	static int typeOf(Object value) {
		return switch (value) {
			case Byte _ -> BYTE;
			case Short _ -> SHORT;
			case Integer _ -> INT;
			case Long _ -> LONG;
			case Float _ -> FLOAT;
			case Double _ -> DOUBLE;
			case byte[] _ -> BYTE_ARRAY;
			case String _ -> STRING;
			case List<?> _ -> LIST;
			case Map<?, ?> _ -> COMPOUND;
			case int[] _ -> INT_ARRAY;
			case long[] _ -> LONG_ARRAY;
			default -> throw new IllegalArgumentException("not an NBT value: " + value.getClass().getName());
		};
	}

	private static void writePayload(DataOutput out, Object value, int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("NBT nested too deeply");
		}

		switch (value) {
			case Byte b -> out.writeByte(b);
			case Short s -> out.writeShort(s);
			case Integer i -> out.writeInt(i);
			case Long l -> out.writeLong(l);
			case Float f -> out.writeFloat(f);
			case Double d -> out.writeDouble(d);
			case byte[] a -> {
				out.writeInt(a.length);
				out.write(a);
			}
			case String s -> out.writeUTF(s);
			case List<?> list -> {
				int type = list.isEmpty() ? END : typeOf(list.get(0));
				out.writeByte(type);
				out.writeInt(list.size());

				for (Object element : list) {
					if (typeOf(element) != type) {
						throw new IllegalArgumentException("mixed list element types");
					}

					writePayload(out, element, depth + 1);
				}
			}
			case Map<?, ?> map -> {
				for (Map.Entry<?, ?> e : map.entrySet()) {
					out.writeByte(typeOf(e.getValue()));
					out.writeUTF((String) e.getKey());
					writePayload(out, e.getValue(), depth + 1);
				}

				out.writeByte(END);
			}
			case int[] a -> {
				out.writeInt(a.length);

				for (int v : a) {
					out.writeInt(v);
				}
			}
			case long[] a -> {
				out.writeInt(a.length);

				for (long v : a) {
					out.writeLong(v);
				}
			}
			default -> throw new IllegalArgumentException("not an NBT value: " + value.getClass().getName());
		}
	}

	// ---- reading (tests and checks) -------------------------------------------------------

	public static Map<String, Object> readCompressed(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			return read(in);
		}
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> read(DataInput in) throws IOException {
		int type = in.readUnsignedByte();

		if (type != COMPOUND) {
			throw new IOException("root is not a compound");
		}

		in.readUTF();
		return (Map<String, Object>) readPayload(in, COMPOUND, 0);
	}

	private static Object readPayload(DataInput in, int type, int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("NBT nested too deeply");
		}

		switch (type) {
			case BYTE:
				return in.readByte();
			case SHORT:
				return in.readShort();
			case INT:
				return in.readInt();
			case LONG:
				return in.readLong();
			case FLOAT:
				return in.readFloat();
			case DOUBLE:
				return in.readDouble();
			case BYTE_ARRAY: {
				byte[] a = new byte[length(in)];
				in.readFully(a);
				return a;
			}
			case STRING:
				return in.readUTF();
			case LIST: {
				int elementType = in.readUnsignedByte();
				int n = length(in);
				List<Object> list = new ArrayList<>(Math.min(n, 1024));

				for (int i = 0; i < n; i++) {
					list.add(readPayload(in, elementType, depth + 1));
				}

				return list;
			}
			case COMPOUND: {
				Map<String, Object> map = new LinkedHashMap<>();

				for (int t = in.readUnsignedByte(); t != END; t = in.readUnsignedByte()) {
					String key = in.readUTF();
					map.put(key, readPayload(in, t, depth + 1));
				}

				return map;
			}
			case INT_ARRAY: {
				int[] a = new int[length(in)];

				for (int i = 0; i < a.length; i++) {
					a[i] = in.readInt();
				}

				return a;
			}
			case LONG_ARRAY: {
				long[] a = new long[length(in)];

				for (int i = 0; i < a.length; i++) {
					a[i] = in.readLong();
				}

				return a;
			}
			default:
				throw new IOException("unknown NBT type " + type);
		}
	}

	private static int length(DataInput in) throws IOException {
		int n = in.readInt();

		if (n < 0 || n > 64 * 1024 * 1024) {
			throw new IOException("bad NBT length " + n);
		}

		return n;
	}
}
