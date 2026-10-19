package dev.lume.gore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline wall previewer: bakes every block face of a {@link PreviewScenes} scene
 * with {@link BloodField} over a range of ticks — exactly what {@code DecalRenderer}
 * uploads, minus the GPU — and composes the unfolded room (wall above, floor
 * below) on stone grey with the block grid.
 *
 * <pre>./gradlew previewBlood -Pscene=spray -Pto=120 -Pstep=2 -Poverlay</pre>
 *
 * Writes {@code build/inspect/<scene>/}: {@code frames/t0000.png} per tick,
 * {@code <scene>.apng} and {@code <scene>-sheet.png}, and prints a per-tick table.
 * {@code -Poverlay} marks each kernel's centre (yellow) and support (cyan).
 */
public final class BloodPreview {
	private static final int STONE = 0x7d7d7d;
	private static final int GRID = 0x40000000;

	private BloodPreview() {
	}

	public static void main(String[] args) throws IOException {
		String scene = System.getProperty("scene", "spray");
		long from = Long.getLong("from", 0L);
		long to = Long.getLong("to", 120L);
		long step = Math.max(1, Long.getLong("step", 2L));
		int px = Integer.getInteger("scale", 3);
		long seed = Long.getLong("seed", 20261016L);
		boolean overlay = Boolean.getBoolean("overlay");
		Path out = Path.of(System.getProperty("out", "build/inspect")).resolve(scene);
		List<String> scenes = scene.equals("all") ? PreviewScenes.NAMES : List.of(scene);

		for (String name : scenes) {
			render(name, PreviewScenes.scene(name, seed), from, to, step, px, overlay,
					scenes.size() > 1 ? out.getParent().resolve(name) : out);
		}
	}

	static void render(String name, List<PreviewScenes.Placement> scene, long from, long to, long step, int px,
			boolean overlay, Path dir) throws IOException {
		long started = System.nanoTime();
		Files.createDirectories(dir.resolve("frames"));
		List<PreviewImage> frames = new ArrayList<>();
		System.out.printf(Locale.ROOT, "%s: %d placements, ticks %d..%d step %d%n", name, scene.size(), from, to, step);
		System.out.println("  tick decals kernels wallTexels floorTexels meanAlpha");

		for (long t = from; t <= to; t += step) {
			List<Decal> decals = PreviewScenes.stored(scene, t);
			PreviewImage img = frame(decals, t, px, overlay);
			img.write(dir.resolve("frames").resolve(String.format(Locale.ROOT, "t%04d.png", t)));
			frames.add(img);
		}

		PreviewImage.writeAnimated(dir.resolve(name + ".apng"), frames, (int) Math.min(1000, 50 * step));
		PreviewImage.sheet(frames, 8, 2).write(dir.resolve(name + "-sheet.png"));
		System.out.printf(Locale.ROOT, "%s: %d frames in %.2fs -> %s%n", name, frames.size(),
				(System.nanoTime() - started) / 1e9, dir.toAbsolutePath());
	}

	/** One frame: the wall's faces over the floor's, {@code px} pixels per texel. */
	static PreviewImage frame(List<Decal> decals, long now, int px, boolean overlay) {
		int res = BloodField.RES;
		int block = res * px;
		int wallH = PreviewScenes.WALL_H;
		PreviewImage img = new PreviewImage(PreviewScenes.WALL_W * block, (wallH + PreviewScenes.FLOOR_D) * block, STONE);
		stone(img, px);

		List<BloodField.Kernel> wall = new ArrayList<>();
		List<BloodField.Kernel> floor = new ArrayList<>();
		for (Decal d : decals) {
			(d.face() == PreviewScenes.SOUTH ? wall : floor).addAll(BloodField.kernels(d, now));
		}

		int[] texels = new int[res * res];
		long[] lit = new long[2];
		double alphaSum = 0;
		for (int plane = 0; plane < 2; plane++) {
			List<BloodField.Kernel> kernels = plane == 0 ? wall : floor;
			int rows = plane == 0 ? wallH : PreviewScenes.FLOOR_D;
			for (int bx = 0; bx < PreviewScenes.WALL_W; bx++) {
				for (int by = 0; by < rows; by++) {
					// Wall: pv = y, top row is the highest block. Floor: pv = -z,
					// top row is z = 0 against the wall, so the room unfolds.
					int pu = bx;
					int pv = plane == 0 ? wallH - 1 - by : -by;
					int oy = (plane == 0 ? by : wallH + by) * block;
					if (!BloodField.bake(kernels, pu, pv, texels)) {
						continue;
					}
					for (int j = 0; j < res; j++) {
						for (int i = 0; i < res; i++) {
							int c = texels[j * res + i];
							if (c >>> 24 == 0) {
								continue;
							}
							lit[plane]++;
							alphaSum += (c >>> 24) / 255.0;
							img.fillRect(bx * block + i * px, oy + j * px, bx * block + (i + 1) * px, oy + (j + 1) * px, c);
						}
					}
				}
			}
		}

		grid(img, block);

		if (overlay) {
			for (int plane = 0; plane < 2; plane++) {
				for (BloodField.Kernel k : plane == 0 ? wall : floor) {
					// Plane u is world x − ½ on both planes; plane v is world y − ½ on
					// the wall and ½ − z on the floor, which sits below the wall's foot.
					double vy = plane == 0 ? wallH - 0.5 : wallH + 0.5;
					double ax = (k.au() + 0.5) * block;
					double bx = (k.bu() + 0.5) * block;
					double ay = (vy - k.av()) * block;
					double by = (vy - k.bv()) * block;
					img.circle(ax, ay, k.support() * block, 0x5000e0ff);
					if (ax != bx || ay != by) {
						img.circle(bx, by, k.support() * block, 0x5000e0ff);
						img.line(ax, ay, bx, by, 0xc0ffe000);
					}
					img.dot(ax, ay, 1, 0xffffe000);
				}
			}
		}

		long total = lit[0] + lit[1];
		System.out.printf(Locale.ROOT, "  %4d %6d %7d %10d %11d %9.3f%n", now, decals.size(), wall.size() + floor.size(),
				lit[0], lit[1], total == 0 ? 0.0 : alphaSum / total);
		return img;
	}

	/** A faint per-texel mottle so translucent blood reads against something. */
	private static void stone(PreviewImage img, int px) {
		for (int y = 0; y < img.h; y += px) {
			for (int x = 0; x < img.w; x += px) {
				int h = (x / px) * 374761393 + (y / px) * 668265263;
				h = (h ^ (h >>> 13)) * 1274126177;
				int n = ((h >>> 24) & 15) - 8;
				int g = 0x7d + n;
				img.fillRect(x, y, x + px, y + px, 0xff000000 | (g << 16) | (g << 8) | g);
			}
		}
	}

	private static void grid(PreviewImage img, int block) {
		for (int x = 0; x <= img.w; x += block) {
			img.fillRect(x - 1, 0, x + 1, img.h, GRID);
		}
		for (int y = 0; y <= img.h; y += block) {
			img.fillRect(0, y - 1, img.w, y + 1, y == PreviewScenes.WALL_H * block ? 0xa0000000 : GRID);
		}
	}
}
