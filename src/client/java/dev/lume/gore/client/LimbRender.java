package dev.lume.gore.client;

import dev.lume.gore.Limb;
import dev.lume.gore.client.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.ARGB;

/**
 * Severed limbs and per-limb passes, applied to a model at the moment it is
 * drawn (see {@code ModelFeatureRendererMixin}).
 *
 * <ul>
 * <li>A severed arm or leg is drawn as a stub: scaled to {@link #STUB} of its
 * length from the joint, which is where every limb's pivot sits. A severed
 * head is hidden.</li>
 * <li>A <em>masked pass</em> draws one limb only. The mod submits the model
 * again with a tint whose colour names the pass ({@link #coatTint},
 * {@link #stumpTint}); every other part is set to {@code skipDraw}, which
 * keeps its children's transforms but draws none of its own cubes. That is
 * how the blood coat lands on the arm that was hit, not the whole body, and
 * how a stump gets a raw red end.</li>
 * </ul>
 *
 * Every change is undone in {@link #after}: models are shared by every
 * creature of a type, and the next one must be drawn whole.
 */
public final class LimbRender {
	/** Severed limbs, as a {@link dev.lume.gore.LimbState#severed()} bit mask. */
	public static final RenderStateDataKey<Integer> SEVERED = RenderStateDataKey.create(() -> "gore:severed");
	/** Per-limb blood coat, 0..1, indexed by {@link Limb#ordinal()}. */
	public static final RenderStateDataKey<float[]> COAT = RenderStateDataKey.create(() -> "gore:limb_coat");

	/** Fraction of a severed limb's length left on the body. */
	static final float STUB = 0.28f;

	/** Model part names that make up each limb, across vanilla's humanoid, quadruped and bird models. */
	private static final Map<Limb, List<String>> NAMES = new EnumMap<>(Map.of(
			Limb.HEAD, List.of("head"),
			Limb.LEFT_ARM, List.of("left_arm", "left_front_leg", "left_wing"),
			Limb.RIGHT_ARM, List.of("right_arm", "right_front_leg", "right_wing"),
			Limb.LEFT_LEG, List.of("left_leg", "left_hind_leg"),
			Limb.RIGHT_LEG, List.of("right_leg", "right_hind_leg")));

	private static final int COAT_R = 116;
	private static final int COAT_G = 24;
	private static final int COAT_B = 40;
	private static final int STUMP_R = 92;
	private static final int STUMP_G = 6;
	private static final int STUMP_B = 60;

	private static final Map<Model<?>, Map<Limb, ModelPart>> PARTS = new WeakHashMap<>();
	private static final List<Saved> SAVED = new ArrayList<>();

	private record Saved(ModelPart part, boolean visible, boolean skipDraw, float xScale, float yScale, float zScale) {
	}

	private LimbRender() {
	}

	/** The tint for a blood-coat pass over {@code limb} at {@code alpha}. */
	public static int coatTint(Limb limb, int alpha) {
		return ARGB.color(alpha, COAT_R, COAT_G, COAT_B + limb.ordinal());
	}

	/** The tint for the raw end of a severed {@code limb}. */
	public static int stumpTint(Limb limb) {
		return ARGB.color(240, STUMP_R, STUMP_G, STUMP_B + limb.ordinal());
	}

	/** The limb a masked pass draws, from its tint, or null for an ordinary draw. */
	static Limb maskOf(int tint) {
		int r = ARGB.red(tint);
		int g = ARGB.green(tint);
		int b = ARGB.blue(tint);
		Limb[] all = Limb.values();
		if (r == COAT_R && g == COAT_G && b >= COAT_B && b < COAT_B + all.length) {
			return all[b - COAT_B];
		}
		if (r == STUMP_R && g == STUMP_G && b >= STUMP_B && b < STUMP_B + all.length) {
			return all[b - STUMP_B];
		}
		return null;
	}

	static boolean isStumpPass(int tint) {
		return ARGB.red(tint) == STUMP_R && ARGB.green(tint) == STUMP_G;
	}

	/** The model's part for {@code limb}, or null if it has none by any known name. */
	public static ModelPart part(Model<?> model, Limb limb) {
		return PARTS.computeIfAbsent(model, LimbRender::resolve).get(limb);
	}

	private static Map<Limb, ModelPart> resolve(Model<?> model) {
		Map<Limb, ModelPart> out = new EnumMap<>(Limb.class);
		for (Map.Entry<Limb, List<String>> e : NAMES.entrySet()) {
			for (String name : e.getValue()) {
				ModelPart found = find(model.root(), name);
				if (found != null) {
					out.put(e.getKey(), found);
					break;
				}
			}
		}
		return out;
	}

	private static ModelPart find(ModelPart part, String name) {
		Map<String, ModelPart> children = ((ModelPartAccessor) (Object) part).gore$children();
		ModelPart direct = children.get(name);
		if (direct != null) {
			return direct;
		}
		for (ModelPart child : children.values()) {
			ModelPart found = find(child, name);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/** Drops the part cache; models are rebuilt on resource reload. */
	public static void invalidate() {
		PARTS.clear();
	}

	public static void before(Model<?> model, Object state, int tint) {
		if (!(state instanceof FabricRenderState frs)) {
			return;
		}

		Integer severed = frs.getData(SEVERED);
		Limb mask = maskOf(tint);

		if ((severed == null || severed == 0) && mask == null) {
			return;
		}

		if (mask != null) {
			Set<ModelPart> draw = Collections.newSetFromMap(new IdentityHashMap<>());
			if (mask == Limb.TORSO) {
				draw.addAll(model.allParts());
				for (Limb limb : NAMES.keySet()) {
					ModelPart p = part(model, limb);
					if (p != null) {
						draw.removeAll(p.getAllParts());
					}
				}
			} else {
				ModelPart p = part(model, mask);
				if (p != null) {
					draw.addAll(p.getAllParts());
				}
			}
			for (ModelPart p : model.allParts()) {
				save(p);
				p.skipDraw = p.skipDraw || !draw.contains(p);
			}
		}

		if (severed != null && severed != 0) {
			boolean stump = isStumpPass(tint);
			for (Limb limb : NAMES.keySet()) {
				if ((severed & limb.bit()) == 0) {
					continue;
				}
				ModelPart p = part(model, limb);
				if (p == null) {
					continue;
				}
				save(p);
				if (limb == Limb.HEAD) {
					p.visible = false;
				} else {
					p.yScale *= STUB;
					if (stump && mask == limb) {
						// The raw end sits just proud of the stub's skin.
						p.xScale *= 1.06f;
						p.zScale *= 1.06f;
						p.yScale *= 1.04f;
					}
				}
			}
		}
	}

	private static void save(ModelPart p) {
		SAVED.add(new Saved(p, p.visible, p.skipDraw, p.xScale, p.yScale, p.zScale));
	}

	public static void after() {
		// Restore in reverse, so a part saved twice ends on its original values.
		for (int i = SAVED.size() - 1; i >= 0; i--) {
			Saved s = SAVED.get(i);
			s.part.visible = s.visible;
			s.part.skipDraw = s.skipDraw;
			s.part.xScale = s.xScale;
			s.part.yScale = s.yScale;
			s.part.zScale = s.zScale;
		}
		SAVED.clear();
	}
}
