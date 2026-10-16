package dev.lume.gore;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Explicit face basis for wall stains.
 *
 * <p>The server encodes a droplet's travel direction on a face as
 * {@code rot = degrees(atan2(p·axis2, p·axis1))}. Rolling the quad about the
 * model Z axis after {@code Quaternionf.rotateTo(MODEL_FRONT, outward)} yields a
 * different in-plane basis per face, so the stain's "tail" points the wrong way
 * depending on the face. This class builds the orientation directly from the
 * face basis instead: the resulting rotation's columns map model X to
 * {@code tail × normal}, model Y to {@code −tail}, and model Z to
 * {@code −normal} (determinant +1).
 *
 * <p>Model −Y is the atlas "tail-up" direction and model −Z is the textured
 * front of the {@code ModelPart.Cube}, so mapping model −Y to the tail vector
 * and model −Z to the outward normal places the elongated end of the stain
 * along the travel direction.
 */
public final class DecalFrame {

	private DecalFrame() {
	}

	/**
	 * The travel direction projected onto {@code face}, as a unit vector in
	 * world space: {@code axis1·cos(rot) + axis2·sin(rot)}.
	 */
	public static Vec3 tail(Direction face, float rotDeg) {
		double rad = Math.toRadians(rotDeg);
		return Decal.axis1(face).scale(Math.cos(rad))
				.add(Decal.axis2(face).scale(Math.sin(rad)));
	}

	/**
	 * The rotation that places a stain quad on {@code face} with its tail
	 * pointing along {@link #tail(Direction, float)}: a proper rotation
	 * (determinant +1) whose columns map model X → {@code tail × normal},
	 * model Y → {@code −tail}, model Z → {@code −normal}, where
	 * {@code normal = axis1 × axis2} is the face's outward normal.
	 */
	public static Quaternionf orientation(Direction face, float rotDeg) {
		Vec3 t = tail(face, rotDeg);
		Vec3 n = Decal.axis1(face).cross(Decal.axis2(face));

		Vector3f xCol = new Vector3f(
				(float) (t.y * n.z - t.z * n.y),
				(float) (t.z * n.x - t.x * n.z),
				(float) (t.x * n.y - t.y * n.x));
		Vector3f yCol = new Vector3f((float) -t.x, (float) -t.y, (float) -t.z);
		Vector3f zCol = new Vector3f((float) -n.x, (float) -n.y, (float) -n.z);

		return new Quaternionf().setFromNormalized(new Matrix3f(xCol, yCol, zCol));
	}
}
