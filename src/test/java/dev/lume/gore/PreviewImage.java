package dev.lume.gore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * A small RGB canvas with PNG, animated PNG and contact-sheet output — the Java
 * twin of {@code tools/seq.py}, so the inspection tools need no image library.
 */
final class PreviewImage {
	final int w;
	final int h;
	final int[] rgb;

	PreviewImage(int w, int h, int fill) {
		this.w = w;
		this.h = h;
		this.rgb = new int[w * h];
		java.util.Arrays.fill(this.rgb, fill);
	}

	/** Alpha-blends {@code argb} over the pixel, ignoring pixels off the canvas. */
	void blend(int x, int y, int argb) {
		if (x < 0 || y < 0 || x >= w || y >= h) {
			return;
		}
		int a = argb >>> 24;
		int d = rgb[y * w + x];
		int r = mix((d >> 16) & 255, (argb >> 16) & 255, a);
		int g = mix((d >> 8) & 255, (argb >> 8) & 255, a);
		int b = mix(d & 255, argb & 255, a);
		rgb[y * w + x] = (r << 16) | (g << 8) | b;
	}

	private static int mix(int under, int over, int a) {
		return (under * (255 - a) + over * a + 127) / 255;
	}

	void fillRect(int x0, int y0, int x1, int y1, int argb) {
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				blend(x, y, argb);
			}
		}
	}

	void line(double x0, double y0, double x1, double y1, int argb) {
		int n = (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))) + 1;
		for (int i = 0; i <= n; i++) {
			double t = i / (double) n;
			blend((int) Math.round(x0 + (x1 - x0) * t), (int) Math.round(y0 + (y1 - y0) * t), argb);
		}
	}

	void circle(double cx, double cy, double r, int argb) {
		int n = Math.max(12, (int) (r * 6));
		for (int i = 0; i < n; i++) {
			double a0 = 2 * Math.PI * i / n;
			double a1 = 2 * Math.PI * (i + 1) / n;
			line(cx + r * Math.cos(a0), cy + r * Math.sin(a0), cx + r * Math.cos(a1), cy + r * Math.sin(a1), argb);
		}
	}

	void dot(double cx, double cy, int r, int argb) {
		fillRect((int) cx - r, (int) cy - r, (int) cx + r + 1, (int) cy + r + 1, argb);
	}

	/** Grid of downscaled frames, {@code cols} across. */
	static PreviewImage sheet(List<PreviewImage> frames, int cols, int k) {
		PreviewImage f0 = frames.get(0);
		int tw = f0.w / k;
		int th = f0.h / k;
		int rows = (frames.size() + cols - 1) / cols;
		PreviewImage out = new PreviewImage(tw * cols, th * rows, 0x202020);
		for (int n = 0; n < frames.size(); n++) {
			PreviewImage f = frames.get(n);
			int ox = (n % cols) * tw;
			int oy = (n / cols) * th;
			for (int y = 0; y < th; y++) {
				for (int x = 0; x < tw; x++) {
					out.rgb[(oy + y) * out.w + ox + x] = f.rgb[(y * k) * f.w + x * k];
				}
			}
		}
		return out;
	}

	void write(Path path) throws IOException {
		try (OutputStream out = Files.newOutputStream(path)) {
			header(out, w, h);
			chunk(out, "IDAT", deflate(this));
			chunk(out, "IEND", new byte[0]);
		}
	}

	static void writeAnimated(Path path, List<PreviewImage> frames, int delayMs) throws IOException {
		PreviewImage f0 = frames.get(0);
		try (OutputStream out = Files.newOutputStream(path)) {
			header(out, f0.w, f0.h);
			chunk(out, "acTL", ints(frames.size(), 0));
			int seq = 0;
			for (int n = 0; n < frames.size(); n++) {
				ByteArrayOutputStream fc = new ByteArrayOutputStream();
				DataOutputStream d = new DataOutputStream(fc);
				d.writeInt(seq++);
				d.writeInt(f0.w);
				d.writeInt(f0.h);
				d.writeInt(0);
				d.writeInt(0);
				d.writeShort(delayMs);
				d.writeShort(1000);
				d.writeByte(0);
				d.writeByte(0);
				chunk(out, "fcTL", fc.toByteArray());
				byte[] data = deflate(frames.get(n));
				if (n == 0) {
					chunk(out, "IDAT", data);
				} else {
					ByteArrayOutputStream fd = new ByteArrayOutputStream();
					new DataOutputStream(fd).writeInt(seq++);
					fd.write(data);
					chunk(out, "fdAT", fd.toByteArray());
				}
			}
			chunk(out, "IEND", new byte[0]);
		}
	}

	private static void header(OutputStream out, int w, int h) throws IOException {
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
		ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(ihdr);
		d.writeInt(w);
		d.writeInt(h);
		d.write(new byte[] {8, 2, 0, 0, 0});
		chunk(out, "IHDR", ihdr.toByteArray());
	}

	private static byte[] ints(int a, int b) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(bytes);
		d.writeInt(a);
		d.writeInt(b);
		return bytes.toByteArray();
	}

	private static byte[] deflate(PreviewImage img) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DeflaterOutputStream z = new DeflaterOutputStream(bytes, new Deflater(6))) {
			byte[] row = new byte[1 + img.w * 3];
			for (int y = 0; y < img.h; y++) {
				for (int x = 0; x < img.w; x++) {
					int p = img.rgb[y * img.w + x];
					row[1 + x * 3] = (byte) (p >> 16);
					row[2 + x * 3] = (byte) (p >> 8);
					row[3 + x * 3] = (byte) p;
				}
				z.write(row);
			}
		}
		return bytes.toByteArray();
	}

	private static void chunk(OutputStream out, String kind, byte[] body) throws IOException {
		DataOutputStream d = new DataOutputStream(out);
		byte[] k = kind.getBytes(StandardCharsets.US_ASCII);
		d.writeInt(body.length);
		d.write(k);
		d.write(body);
		CRC32 crc = new CRC32();
		crc.update(k);
		crc.update(body);
		d.writeInt((int) crc.getValue());
	}
}
