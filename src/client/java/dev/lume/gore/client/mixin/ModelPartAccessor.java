package dev.lume.gore.client.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to a part's geometry, to find limbs by name and copy a severed one. */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes")
	List<ModelPart.Cube> gore$cubes();

	@Accessor("children")
	Map<String, ModelPart> gore$children();
}
