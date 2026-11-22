package dev.timescute.mixin;

import dev.timescute.client.ActorManager;
import dev.timescute.core.Part;
import dev.timescute.core.PartLayer;
import dev.timescute.core.PartPose;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Aplica as animações (camadas por parte do corpo) ao modelo do ator, depois que o jogo montou a pose normal.
 * O modelo é compartilhado entre todos os jogadores, então a posição e a escala alteradas são desfeitas no início
 * da próxima pose, para não "vazar" para outras entidades.
 */
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart hat;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;

    private float[] timescute$saved;

    private ModelPart[] timescute$parts() { return new ModelPart[]{head, body, rightArm, leftArm, rightLeg, leftLeg}; }

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void timescute$restore(LivingEntity entity, float a, float b, float c, float d, float e, CallbackInfo ci) {
        if (timescute$saved == null) return;
        ModelPart[] ps = timescute$parts();
        for (int i = 0; i < ps.length; i++) {
            int k = i * 9;
            ps[i].pivotX = timescute$saved[k]; ps[i].pivotY = timescute$saved[k + 1]; ps[i].pivotZ = timescute$saved[k + 2];
            ps[i].xScale = timescute$saved[k + 3]; ps[i].yScale = timescute$saved[k + 4]; ps[i].zScale = timescute$saved[k + 5];
            // a rotação também: o jogo não zera o roll da cabeça/corpo a cada pose, então vazaria para outros jogadores
            ps[i].pitch = timescute$saved[k + 6]; ps[i].yaw = timescute$saved[k + 7]; ps[i].roll = timescute$saved[k + 8];
        }
        timescute$saved = null;
    }

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void timescute$layers(LivingEntity entity, float a, float b, float c, float d, float e, CallbackInfo ci) {
        Map<Part, List<PartLayer>> layers = ActorManager.get().layersFor(entity);
        if (layers == null) return;

        ModelPart[] ps = timescute$parts();
        float[] saved = new float[ps.length * 9];
        for (int i = 0; i < ps.length; i++) {
            int k = i * 9;
            saved[k] = ps[i].pivotX; saved[k + 1] = ps[i].pivotY; saved[k + 2] = ps[i].pivotZ;
            saved[k + 3] = ps[i].xScale; saved[k + 4] = ps[i].yScale; saved[k + 5] = ps[i].zScale;
            saved[k + 6] = ps[i].pitch; saved[k + 7] = ps[i].yaw; saved[k + 8] = ps[i].roll;
        }
        timescute$saved = saved;

        timescute$apply(head, layers.get(Part.HEAD));
        timescute$apply(body, layers.get(Part.BODY));
        timescute$apply(rightArm, layers.get(Part.RIGHT_ARM));
        timescute$apply(leftArm, layers.get(Part.LEFT_ARM));
        timescute$apply(rightLeg, layers.get(Part.RIGHT_LEG));
        timescute$apply(leftLeg, layers.get(Part.LEFT_LEG));
        hat.copyTransform(head); // o chapéu já tinha sido copiado antes das camadas
    }

    private static void timescute$apply(ModelPart m, List<PartLayer> layers) {
        if (layers == null || layers.isEmpty()) return;
        // a posição das animações é um DESLOCAMENTO do repouso: começa em 0 e soma ao pivô no final
        PartPose p = new PartPose(m.pitch, m.yaw, m.roll, 0, 0, 0, m.xScale, m.yScale, m.zScale);
        for (PartLayer l : layers) p = l.applyOver(p);
        m.pitch = (float) p.pitch();
        m.yaw = (float) p.yaw();
        m.roll = (float) p.roll();
        m.pivotX += (float) p.x();
        m.pivotY += (float) p.y();
        m.pivotZ += (float) p.z();
        m.xScale = (float) p.sx();
        m.yScale = (float) p.sy();
        m.zScale = (float) p.sz();
    }
}
