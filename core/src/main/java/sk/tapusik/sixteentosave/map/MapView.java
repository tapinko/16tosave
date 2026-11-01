package sk.tapusik.sixteentosave.map;

/**
 * Where the map looks: centre in block coordinates and zoom in GUI pixels per block, inside a
 * viewport rectangle on the screen. All conversions between screen and world go through here.
 */
public final class MapView {
	public static final double MIN_SCALE = 1.0 / 128;
	public static final double MAX_SCALE = 16;
	public static final int REGION_BLOCKS = 512;

	private double centerX;
	private double centerZ;
	private double scale = 1;

	private double viewX;
	private double viewY;
	private double viewWidth = 1;
	private double viewHeight = 1;

	public void setViewport(double x, double y, double width, double height) {
		this.viewX = x;
		this.viewY = y;
		this.viewWidth = Math.max(1, width);
		this.viewHeight = Math.max(1, height);
	}

	public void setCenter(double blockX, double blockZ) {
		this.centerX = blockX;
		this.centerZ = blockZ;
	}

	public void setScale(double pixelsPerBlock) {
		this.scale = clampScale(pixelsPerBlock);
	}

	public double centerX() {
		return centerX;
	}

	public double centerZ() {
		return centerZ;
	}

	public double scale() {
		return scale;
	}

	/** Moves the map with the mouse: dragging right shows what is to the west. */
	public void pan(double dxPixels, double dyPixels) {
		centerX -= dxPixels / scale;
		centerZ -= dyPixels / scale;
	}

	/** Zooms by {@code factor}, keeping the block under the cursor where it is. */
	public void zoomAt(double factor, double screenX, double screenY) {
		double bx = blockX(screenX);
		double bz = blockZ(screenY);
		scale = clampScale(scale * factor);
		centerX = bx - (screenX - viewCenterX()) / scale;
		centerZ = bz - (screenY - viewCenterY()) / scale;
	}

	public double blockX(double screenX) {
		return centerX + (screenX - viewCenterX()) / scale;
	}

	public double blockZ(double screenY) {
		return centerZ + (screenY - viewCenterY()) / scale;
	}

	public double screenX(double blockX) {
		return viewCenterX() + (blockX - centerX) * scale;
	}

	public double screenY(double blockZ) {
		return viewCenterY() + (blockZ - centerZ) * scale;
	}

	public boolean contains(double screenX, double screenY) {
		return screenX >= viewX && screenX < viewX + viewWidth && screenY >= viewY && screenY < viewY + viewHeight;
	}

	/** Regions at least partly visible: {@code {minX, minZ, maxX, maxZ}}, inclusive. */
	public int[] visibleRegions() {
		return new int[] {
				regionOf(blockX(viewX)),
				regionOf(blockZ(viewY)),
				regionOf(blockX(viewX + viewWidth)),
				regionOf(blockZ(viewY + viewHeight))};
	}

	static int regionOf(double block) {
		return (int) Math.floorDiv((long) Math.floor(block), REGION_BLOCKS);
	}

	/** Squared distance in blocks from the view centre to a region's centre, for tile priority. */
	public double regionDistanceSq(int regionX, int regionZ) {
		double dx = (regionX + 0.5) * REGION_BLOCKS - centerX;
		double dz = (regionZ + 0.5) * REGION_BLOCKS - centerZ;
		return dx * dx + dz * dz;
	}

	private double viewCenterX() {
		return viewX + viewWidth / 2;
	}

	private double viewCenterY() {
		return viewY + viewHeight / 2;
	}

	private static double clampScale(double s) {
		if (Double.isNaN(s)) {
			return 1;
		}

		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
	}
}
