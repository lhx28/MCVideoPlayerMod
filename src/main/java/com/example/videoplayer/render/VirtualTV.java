package com.example.videoplayer.render;

import com.example.videoplayer.VideoPlayerMod;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11; // 确保导入GL11

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 支持任意坐标范围 + 任意绕Y轴旋转角度（与MC视角一致）
 * 修复：1. 玩家穿越屏幕面复制问题 2. RenderSystem方法解析错误（适配MC 1.20+）
 * 旋转角度说明：0=正向，90=右转90度，180=反向，-90=左转90度
 */
public class VirtualTV {
    private final VideoPlayerMod mod;
    private final MinecraftClient mc;
    private final VideoRenderer videoRenderer;

    // 屏幕核心属性（支持任意坐标/角度）
    private BlockPos screenBasePos;       // 屏幕基准点（左下角）
    private float screenWidth = 2.0f;     // 屏幕宽度（X/Z轴有效差值）
    private float screenHeight = 1.5f;    // 屏幕高度（Y轴差值）
    private float screenOffsetZ = 0.0f;   // Z轴偏移
    private float rotationYaw;            // 绕Y轴旋转角度（与MC视角一致，单位：度）
    private Vec3d screenCenterPos;        // 屏幕几何中心（旋转中心）
    private Vec3d screenNormal;           // 屏幕正面法向量（用于可见性判断）


    // 构造方法：初始化默认值，不硬编码坐标
    public VirtualTV(VideoPlayerMod mod, VideoRenderer videoRenderer) {
        this.mod = mod;
        this.mc = MinecraftClient.getInstance();
        this.videoRenderer = videoRenderer;
        // 默认位置：玩家前方5格（保留原有逻辑，可通过setTvBounds覆盖）
        this.screenBasePos = mc.player != null ?
                mc.player.getBlockPos().offset(mc.player.getHorizontalFacing(), 5) : BlockPos.ORIGIN;
        // 默认旋转角度：0度（正向）
        this.rotationYaw = 0.0f;
        // 初始化中心坐标和法向量
        updateScreenCenter();
        updateScreenNormal();
    }


    // ========== 核心方法：设置任意坐标范围 ==========
    /**
     * 设置TV屏幕的坐标范围（支持任意min/max坐标）
     * @param minPos 屏幕最小坐标（左下角）
     * @param maxPos 屏幕最大坐标（右上角）
     */
    public void setTvBounds(Vec3d minPos, Vec3d maxPos) {
        if (minPos == null || maxPos == null) return;

        // 1. 更新基准位置（取minPos的整数坐标）
        this.screenBasePos = new BlockPos(
                Math.toIntExact(Math.round(minPos.x)),
                Math.toIntExact(Math.round(minPos.y)),
                Math.toIntExact(Math.round(minPos.z))
        );

        // 2. 智能计算宽高（避免X/Z差值为0导致宽度为0）
        float xDiff = (float) (maxPos.x - minPos.x);
        float zDiff = (float) (maxPos.z - minPos.z);
        // 优先用X轴差值作为宽度，若X轴无差值（如10→10），则用Z轴差值
        this.screenWidth = Math.abs(xDiff) > 0.01f ? Math.abs(xDiff) : Math.abs(zDiff);
        // 高度固定用Y轴差值
        this.screenHeight = (float) Math.abs(maxPos.y - minPos.y);
        // Z轴偏移取maxPos的Z值（保证显示层次）
        this.screenOffsetZ = (float) (maxPos.z - minPos.z);

        // 3. 更新屏幕中心坐标和法向量（旋转中心+可见性判断）
        updateScreenCenter();
        updateScreenNormal();
    }

    // ========== 新增：设置旋转角度（与MC视角一致） ==========
    /**
     * 设置绕Y轴的旋转角度（MC视角）
     * @param yaw 旋转角度（度）：90=右转，180=反向，-90=左转，-180=反向
     */
    public void setRotationYaw(float yaw) {
        this.rotationYaw = yaw;
        // 旋转角度变化时，更新屏幕法向量
        updateScreenNormal();
    }

    // ========== 辅助方法：更新屏幕中心坐标（旋转中心） ==========
    private void updateScreenCenter() {
        if (screenBasePos == null) return;
        // 计算屏幕几何中心 = 基准位置 + 宽/2（X/Z） + 高/2（Y）
        double centerX = screenBasePos.getX() + screenWidth / 2.0f;
        double centerY = screenBasePos.getY() + screenHeight / 2.0f;
        double centerZ = screenBasePos.getZ() + screenOffsetZ / 2.0f;
        this.screenCenterPos = new Vec3d(centerX, centerY, centerZ);
    }

    // ========== 辅助方法：更新屏幕正面法向量（用于可见性判断） ==========
    private void updateScreenNormal() {
        // 初始法向量（Z轴正方向）
        Vector3f normal = new Vector3f(0, 0, 1);
        // 根据旋转角度旋转法向量
        Quaternionf rotation = new Quaternionf().rotationY((float) Math.toRadians(rotationYaw));
        normal.rotate(rotation);
        // 转换为Vec3d保存
        this.screenNormal = new Vec3d(normal.x(), normal.y(), normal.z());
    }

    // ========== 辅助方法：判断相机是否在屏幕正面（核心：避免背面渲染） ==========
    private boolean isCameraInFront() {
        if (screenCenterPos == null || screenNormal == null || mc.gameRenderer.getCamera() == null) {
            return true; // 异常时默认渲染
        }

        // 1. 获取相机位置
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        // 2. 计算相机到屏幕中心的向量
        Vec3d cameraToScreen = screenCenterPos.subtract(cameraPos);
        // 3. 计算向量与屏幕法向量的点积：>0 → 相机在正面，<0 → 相机在背面
        double dotProduct = cameraToScreen.dotProduct(screenNormal);
        // 4. 只有点积>0时，才渲染（相机在正面）
        return dotProduct > 0.01;
    }

    // ========== 核心渲染逻辑（修复：GL状态直接调用GL11，适配MC 1.20+） ==========
    public void renderTvScreen(MatrixStack matrixStack) throws Exception {
        // 前置判断：1. 纹理无效 2. 相机在背面 → 不渲染
        int videoTextureId = mod.getVideoTextureGLId();
        if (videoTextureId == -1 || mc.player == null || mc.world == null || mc.gameRenderer.getCamera() == null) {
            return;
        }
        // 关键：相机在屏幕背面时，直接返回，不渲染
//        if (!isCameraInFront()) {
//            return;
//        }

        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        // 1. 计算屏幕位置（相对于相机）
        double x = screenBasePos.getX() - cameraPos.x;
        double y = screenBasePos.getY() - cameraPos.y;
        double z = screenBasePos.getZ() - cameraPos.z;

        matrixStack.push();
        // 2. 平移到屏幕基准位置
        matrixStack.translate(x, y, z);

        // 3. 绕屏幕中心旋转（核心修改：支持任意角度）
        if (screenCenterPos != null) {
            // 先平移到中心 → 旋转 → 平移回原位置（绕中心旋转）
            matrixStack.translate(screenWidth / 2.0f, screenHeight / 2.0f, screenOffsetZ / 2.0f);
            Quaternionf yRotation = new Quaternionf().rotationY((float) Math.toRadians(rotationYaw));
            matrixStack.multiply(yRotation);
            matrixStack.translate(-screenWidth / 2.0f, -screenHeight / 2.0f, -screenOffsetZ / 2.0f);
        }

        // ========== 核心修复：替换为GL11直接调用（适配MC 1.20+） ==========
        // 开启深度测试，避免层级穿透
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // 深度函数：只有深度值更小的像素才会绘制
        GL11.glDepthFunc(GL11.GL_LESS);
        // 开启背面剔除，只渲染正面
        GL11.glEnable(GL11.GL_CULL_FACE);
        // 剔除背面（只显示屏幕正面）
        GL11.glCullFace(GL11.GL_BACK);

        // 4. 渲染带纹理的四边形（保留原有渲染逻辑）
        renderTexturedQuad(
                matrixStack,
                0.0f, 0.0f, screenOffsetZ,
                screenWidth, screenHeight, 0.0f,
                0.0f, 0.0f, 1.0f, 1.0f,
                1.0f, 1.0f, 1.0f, 1.0f,
                videoTextureId
        );

        // ========== 恢复渲染状态（避免影响其他渲染） ==========
        // 关闭背面剔除（恢复MC默认状态）
        GL11.glDisable(GL11.GL_CULL_FACE);
        // 恢复默认深度函数
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        // 注意：不要关闭深度测试，MC其他渲染需要

        matrixStack.pop();
        RenderSystem.setShaderTexture(0, mod.getGpuTextureView());
    }

    // ========== 保留原有方法（兼容+优化） ==========
    public void renderVirtualTV() throws Exception {
        if (mc.world == null || mc.player == null) return;
        MatrixStack matrixStack = new MatrixStack();
        renderTvScreen(matrixStack);
    }

    private void renderTexturedQuad(MatrixStack matrixStack,
                                    float x, float y, float z,
                                    float width, float height, float depth,
                                    float u1, float v1, float u2, float v2,
                                    float r, float g, float b, float a,
                                    int textureId) throws Exception {
        RenderSystem.setShaderTexture(0, mod.getGpuTextureView());

        RenderContext renderContext = new RenderContext(
                () -> "tv_screen_textured_quad",
                MaLiLibPipelines.POSITION_TEX_COLOR_MASA_LEQUAL_DEPTH
        );

        BufferBuilder buffer = renderContext.getBuilder();
        MatrixStack.Entry matrixEntry = matrixStack.peek();
        Matrix4f matrix = matrixEntry.getPositionMatrix();
        //uv高度因为实际解码的上下翻转所以这里反过来了
        // 顶点1：左下角（修改U坐标：u1 ↔ u2 交换）
        buffer.vertex(matrix, x, y, z)
                .color(r, g, b, a)
                .texture(u1, v1) // 原：u2 → 改为：u1
                .light(15728880)
                .normal(matrixEntry, 0.0f, 0.0f, 1.0f);

        // 顶点2：右下角（修改U坐标：u1 ↔ u2 交换）
        buffer.vertex(matrix, x + width, y, z)
                .color(r, g, b, a)
                .texture(u2, v1) // 原：u1 → 改为：u2
                .light(15728880)
                .normal(matrixEntry, 0.0f, 0.0f, 1.0f);

        // 顶点3：右上角（修改U坐标：u1 ↔ u2 交换）
        buffer.vertex(matrix, x + width, y + height, z)
                .color(r, g, b, a)
                .texture(u2, v2) // 原：u1 → 改为：u2
                .light(15728880)
                .normal(matrixEntry, 0.0f, 0.0f, 1.0f);

        // 顶点4：左上角（修改U坐标：u1 ↔ u2 交换）
        buffer.vertex(matrix, x, y + height, z)
                .color(r, g, b, a)
                .texture(u1, v2) // 原：u2 → 改为：u1
                .light(15728880)
                .normal(matrixEntry, 0.0f, 0.0f, 1.0f);
        try {
            renderContext.draw();
        } catch (Exception e) {
            System.out.println("Failed to render TV screen quad" + e);
        } finally {
            renderContext.close();
        }
    }

    // ========== 扩展方法：设置任意屏幕位置+尺寸（兼容原有逻辑） ==========
    public void setTvScreen(BlockPos basePos, float width, float height) {
        this.screenBasePos = basePos;
        this.screenWidth = width;
        this.screenHeight = height;
        updateScreenCenter(); // 更新中心坐标
        updateScreenNormal(); // 更新法向量
    }

    // ========== Getter方法（方便外部获取/调试） ==========
    public Vec3d getScreenCenterPos() {
        return screenCenterPos;
    }

    public float getRotationYaw() {
        return rotationYaw;
    }

    public float getScreenWidth() {
        return screenWidth;
    }

    public float getScreenHeight() {
        return screenHeight;
    }
}
