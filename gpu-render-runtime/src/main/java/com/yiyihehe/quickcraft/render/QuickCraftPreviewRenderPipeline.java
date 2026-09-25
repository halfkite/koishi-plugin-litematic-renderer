package com.yiyihehe.quickcraft.render;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entity previews use the vanilla entity shader, but without its directional
 * vertex-color multiplier. The lightmap remains enabled, so texture alpha,
 * transparency, overlays, and entity-specific render layers keep their normal
 * behavior while the virtual void cannot make them uniformly dark.
 */
public final class QuickCraftPreviewRenderPipeline {
    private static final Map<RenderPipeline, RenderPipeline> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private QuickCraftPreviewRenderPipeline() {
    }

    public static RenderPipeline forPreview(RenderPipeline original) {
        String vertexShader = original.getShaders().get(ShaderType.VERTEX).toString();
        if (!vertexShader.endsWith(":core/entity") && !vertexShader.equals("core/entity")) {
            return original;
        }
        return CACHE.computeIfAbsent(original, QuickCraftPreviewRenderPipeline::create);
    }

    private static RenderPipeline create(RenderPipeline original) {
        ShaderDefines originalDefines = original.getShaderDefines();
        Map<String, String> values = new HashMap<>(originalDefines.values());
        Set<String> flags = new HashSet<>(originalDefines.flags());

        // PER_FACE_LIGHTING takes precedence over NO_CARDINAL_LIGHTING in
        // entity.vsh, so remove it before enabling the no-directional-light path.
        values.remove("PER_FACE_LIGHTING");
        flags.remove("PER_FACE_LIGHTING");
        flags.add("NO_CARDINAL_LIGHTING");

        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(
                        "quickcraft",
                        "preview/entity-" + NEXT_ID.incrementAndGet()
                ))
                .withVertexShader(original.getShaders().get(ShaderType.VERTEX))
                .withFragmentShader(original.getShaders().get(ShaderType.FRAGMENT))
                .withCull(original.isCull())
                .withPolygonMode(original.getPolygonMode())
                .withDepthStencilState(original.getDepthStencilState())
                .withPrimitiveTopology(original.getPrimitiveTopology());

        for (Map.Entry<String, String> entry : values.entrySet()) {
            builder.withShaderDefine(entry.getKey(), Float.parseFloat(entry.getValue()));
        }
        for (String flag : flags) {
            builder.withShaderDefine(flag);
        }
        for (var layout : original.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }

        java.util.List<ColorTargetState> colorTargets = original.getColorTargetStates();
        for (int i = 0; i < colorTargets.size(); i++) {
            ColorTargetState colorTarget = colorTargets.get(i);
            if (colorTarget == null) {
                builder.withUnusedColorTargetState(i);
            } else {
                builder.withColorTargetState(i, colorTarget);
            }
        }

        java.util.List<VertexFormat> vertexFormats = original.getVertexFormatBindings();
        for (int i = 0; i < vertexFormats.size(); i++) {
            if (vertexFormats.get(i) != null) {
                builder.withVertexBinding(i, vertexFormats.get(i));
            }
        }

        return builder.build();
    }
}
