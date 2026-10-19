package dev.lume.gore.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tick particle trace for debugging, off unless {@code -Dgore.trace=<file>}
 * is set. Each blood particle appends one JSON line per tick with its state as
 * the tick begins (so {@code xo} should equal the previous line's {@code x}).
 * {@code tools/trace.py} plots the lines and flags anomalies.
 */
public final class ParticleTrace {
	private static final BufferedWriter OUT = open(System.getProperty("gore.trace"));
	private static final AtomicInteger IDS = new AtomicInteger();

	private ParticleTrace() {
	}

	public static boolean enabled() {
		return OUT != null;
	}

	/** A fresh particle id, or 0 when tracing is off. */
	public static int nextId() {
		return OUT == null ? 0 : IDS.incrementAndGet();
	}

	public static void record(int id, String type, long gameTime, int age, double x, double y, double z,
			double xo, double yo, double zo, double vx, double vy, double vz, float alpha, float size) {
		if (OUT == null) {
			return;
		}

		String line = String.format(Locale.ROOT,
				"{\"id\":%d,\"type\":\"%s\",\"t\":%d,\"age\":%d,\"x\":%.5f,\"y\":%.5f,\"z\":%.5f,"
						+ "\"xo\":%.5f,\"yo\":%.5f,\"zo\":%.5f,\"vx\":%.5f,\"vy\":%.5f,\"vz\":%.5f,\"alpha\":%.4f,\"size\":%.4f}",
				id, type, gameTime, age, x, y, z, xo, yo, zo, vx, vy, vz, alpha, size);

		synchronized (OUT) {
			try {
				OUT.write(line);
				OUT.newLine();
			} catch (IOException e) {
				// A trace is best-effort; never take the game down over it.
			}
		}
	}

	private static BufferedWriter open(String file) {
		if (file == null || file.isBlank()) {
			return null;
		}

		try {
			Path path = Path.of(file);
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				synchronized (out) {
					try {
						out.close();
					} catch (IOException ignored) {
					}
				}
			}, "gore-trace-close"));
			return out;
		} catch (IOException e) {
			return null;
		}
	}
}
