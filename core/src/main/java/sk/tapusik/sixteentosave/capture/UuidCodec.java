package sk.tapusik.sixteentosave.capture;

import java.util.UUID;

/** Converts between {@link UUID} and the four-int array form used in entity NBT ({@code "UUID"}). */
public final class UuidCodec {
	private UuidCodec() {
	}

	public static int[] toIntArray(UUID uuid) {
		long most = uuid.getMostSignificantBits();
		long least = uuid.getLeastSignificantBits();
		return new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
	}

	/** Returns the UUID as a string, or {@code ""} if the array is not exactly four ints. */
	public static String fromIntArray(int[] ints) {
		if (ints == null || ints.length != 4) {
			return "";
		}

		long most = ((long) ints[0] << 32) | (ints[1] & 0xFFFFFFFFL);
		long least = ((long) ints[2] << 32) | (ints[3] & 0xFFFFFFFFL);
		return new UUID(most, least).toString();
	}
}
