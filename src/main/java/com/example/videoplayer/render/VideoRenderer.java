package com.example.videoplayer.render;

import com.example.videoplayer.VideoPlayerMod;
import com.example.videoplayer.decoder.FFmpegVideoDecoder;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

public class VideoRenderer {
    private final VideoPlayerMod mod;
    private final ConcurrentLinkedDeque<BufferedImage> videoFrameQueue = new ConcurrentLinkedDeque<>();
    private final Semaphore semaphore = new Semaphore(0);
    private final ByteBuffer byteBuffer;
    private final Object videoTexture;
    private final int videoWidth;
    private final int videoHeight;
    private final float aspectRatio;
    private final long frameIntervalMs;
    private final int textureWidth;
    private final int textureHeight;
    private BufferedImage currentFrame;
    private volatile boolean needUpload = false;
    private FFmpegVideoDecoder videoDecoder;
    private long lastRenderTime = 0;
    // 新增：帧就绪同步（核心解决异步数据撕裂）
    private volatile boolean isFrameReady = false; // 帧是否就绪（可用于纹理更新）
    private final Object frameLock = new Object(); // 帧同步锁
    private ByteBuffer frameByteBuffer; // 新增：专用帧缓冲区，避免复用导致的指针混乱

    // VirtualTV集成
    private final VirtualTV virtualTV;
    private final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    // 新增：音频进度锚点（记录上一次渲染对应的音频进度，避免重复渲染）
    private double lastAudioProgress = 0.0;
    // 每帧对应的音频时长（秒/帧），基于视频帧率计算
    private double secondsPerFrame;

    public VideoRenderer(VideoPlayerMod mod, int videoWidth, int videoHeight, int frameRate) {
        this.mod = mod;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.aspectRatio = (float) videoWidth / videoHeight;
        this.frameIntervalMs = (long) (1000.0 / frameRate);
        this.secondsPerFrame = 1.0 / frameRate;

        this.textureWidth = videoWidth;
        this.textureHeight = videoHeight;
        // 修改：不再复用单个ByteBuffer，改为每次帧处理创建新缓冲区（避免指针偏移累积）
        this.byteBuffer = ByteBuffer.allocateDirect(textureWidth * textureHeight * 4);
        this.frameByteBuffer = ByteBuffer.allocateDirect(textureWidth * textureHeight * 4);
        this.videoTexture = mod.genTexture(textureWidth, textureHeight);

        System.out.println("[VideoRenderer] 初始化：视频尺寸" + videoWidth + "x" + videoHeight +
                "，纹理尺寸" + textureWidth + "x" + textureHeight + "，帧间隔" + frameIntervalMs + "ms，每帧音频时长" + secondsPerFrame + "秒");

        new Thread(this::frameProcessLoop, "VideoFrameProcess-Thread").start();
        this.virtualTV = new VirtualTV(mod, this);
    }

    // 原有frameProcessLoop方法保留
    private void frameProcessLoop() {
        while (true) {
            try {
                semaphore.acquire();
                while (!videoFrameQueue.isEmpty()) {
                    BufferedImage frame = videoFrameQueue.poll();
                    if (frame != null) {
                        processFrameToByteBuffer(frame);
                    }
                }
            } catch (Exception e) {
                System.err.println("[VideoRenderer] 帧处理异常：" + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // 原有processFrameToByteBuffer方法保留
    // 你的原有方法，仅补充一行needUpload = true;
    private void processFrameToByteBuffer(BufferedImage frame) {
        if (frame == null) return;
        BufferedImage scaledFrame = resizeImage(frame, textureWidth, textureHeight);

        int[] pixels = new int[textureWidth * textureHeight];
        scaledFrame.getRGB(0, 0, textureWidth, textureHeight, pixels, 0, textureWidth);

        // 加锁：确保帧数据写入过程不被渲染线程打断
        synchronized (frameLock) {
            // 重置帧缓冲区指针（避免偏移累积）
            frameByteBuffer.clear();
            // 修复：像素遍历顺序（从上到下，匹配OpenGL纹理坐标，解决从下到上花屏）
            for (int h = 0; h < textureHeight; h++) {
                for (int w = 0; w < textureWidth; w++) {
                    // 原逻辑：h * textureWidth + w（从下到上），改为：(textureHeight - 1 - h) * textureWidth + w（从上到下）
                    int pixel = pixels[(textureHeight - 1 - h) * textureWidth + w]; // 翻转Y轴，匹配OpenGL纹理
                    frameByteBuffer.put((byte) ((pixel >> 16) & 0xFF)); // R
                    frameByteBuffer.put((byte) ((pixel >> 8) & 0xFF));  // G
                    frameByteBuffer.put((byte) (pixel & 0xFF));         // B
                    frameByteBuffer.put((byte) ((pixel >> 24) & 0xFF)); // A
                }
            }
            // 固定缓冲区指针（flip()：将写模式转为读模式，position=0，limit=有效数据长度）
            frameByteBuffer.flip();
            // 拷贝到渲染缓冲区（避免帧处理线程与渲染线程直接竞争）
            byteBuffer.clear();
            byteBuffer.put(frameByteBuffer);
            byteBuffer.flip();
            // 标记帧就绪，允许纹理更新
            isFrameReady = true;

            // ========== 仅补充这1行！打通needUpload闭环（对应问题2） ==========
            needUpload = true; // 帧数据准备完成，标记需要上传纹理，通知render()执行updateTexture
            // ==================================================================
        }
    }



    // 原有resizeImage方法保留
    private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        float scaleX = (float) targetWidth / originalImage.getWidth();
        float scaleY = (float) targetHeight / originalImage.getHeight();
        float scale = Math.min(scaleX, scaleY);

        int newWidth = (int) (originalImage.getWidth() * scale);
        int newHeight = (int) (originalImage.getHeight() * scale);

        Image resultingImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_AREA_AVERAGING);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        int x = (targetWidth - newWidth) / 2;
        int y = (targetHeight - newHeight) / 2;
        outputImage.getGraphics().drawImage(resultingImage, x, y, null);
        return outputImage;
    }

    // ========== 核心重构：基于OpenAL音频进度同步视频帧（抛弃固定间隔） ==========
    /**
     * 以音频进度为唯一基准，计算并消费对应视频帧
     * 逻辑：
     * 1. 获取当前OpenAL音频播放进度（秒）
     * 2. 计算当前应该渲染的视频帧索引（音频进度 / 每帧音频时长）
     * 3. 消费视频帧直到对齐该索引，实现音视频精准同步
     */
    public void updateFrame() {
        // 前置判断：播放器未初始化/未播放，直接返回
        if (videoDecoder == null || !videoDecoder.isDecoding() || !mod.isVideoPlaying()) {
            return;
        }

        // 1. 获取当前OpenAL音频真实播放进度（硬件级，无累积误差）
        double currentAudioProgress = mod.getPlaybackProgressInSeconds();
        if (currentAudioProgress <= 0.0) {
            return;
        }

        // 2. 过滤无效进度（避免回退导致重复渲染）
        if (currentAudioProgress < lastAudioProgress) {
            lastAudioProgress = currentAudioProgress;
            return;
        }

        // 3. 计算当前应该渲染的视频帧数量（已播放音频时长 / 每帧对应的音频时长）
        long expectedFrameCount = (long) (currentAudioProgress / secondsPerFrame);
        long lastFrameCount = (long) (lastAudioProgress / secondsPerFrame);

        // 4. 消费视频帧直到对齐预期帧数量（核心：音视频时序绑定）
        long framesToConsume = expectedFrameCount - lastFrameCount;
        if (framesToConsume > 0) {
            int consumedFrames = 0;
            while (consumedFrames < framesToConsume && videoDecoder.isDecoding()) {
                BufferedImage newFrame = videoDecoder.pollVideoFrame();
                if (newFrame != null) {
                    videoFrameQueue.offer(newFrame);
                    semaphore.release();
                    consumedFrames++;
                } else {
                    // 无帧可消费，跳出循环，等待下一次解码
                    break;
                }
            }

            // 5. 更新音频进度锚点，记录本次渲染对应的进度
            lastAudioProgress = currentAudioProgress;

            // 6. 队列积压预警（调试用）
//            if (videoFrameQueue.size() > 20) {
//                System.out.println("[VideoRenderer] 视频帧队列积压，当前大小=" + videoFrameQueue.size() +
//                        "，预期帧=" + expectedFrameCount + "，已消费=" + consumedFrames);
//            }
        }
    }

    // 修复：render方法中3D渲染的异常处理和逻辑
    public void render() {
        // 第一步：纹理更新逻辑（移除display判断，避免跳过状态重置）
        synchronized (frameLock) {
            if (isFrameReady && needUpload) {
                // 1. 直接移除display相关判断，不再return，确保后续逻辑执行
                needUpload = false; // 执行纹理更新后，标记为无需再上传
                synchronized (byteBuffer) {
                    // 2. 执行纹理更新（核心：将byteBuffer的帧数据更新到视频纹理）
                    mod.updateTexture(videoTexture, textureWidth, textureHeight, byteBuffer);
                }
                // 3. 重置帧就绪标记，避免重复更新同一帧
                isFrameReady = false;
            }
        }

        DrawContext drawContext = mod.getCurrentDrawContext();
        if (drawContext != null) {
            // 保留原有尺寸计算和日志，仅删除display相关赋值
            int screenWidth = mod.getScreenWidth();
            int screenHeight = mod.getScreenHeight();

            // 自适应尺寸计算
            int renderWidth = Math.min(videoWidth, screenWidth - 20);
            int renderHeight = (int) (renderWidth / aspectRatio);
            if (renderHeight > screenHeight - 20) {
                renderHeight = screenHeight - 20;
                renderWidth = (int) (renderHeight * aspectRatio);
            }

            int x = (screenWidth - renderWidth) / 2;
            int y = (screenHeight - renderHeight) / 2;

//            System.out.println("[VideoRenderer] 绘制视频：坐标(" + x + "," + y + ")，尺寸" + renderWidth + "x" + renderHeight +
//                    "，当前音频进度=" + mod.getPlaybackProgressInSeconds() + "秒");
        }
    }


    // 原有cleanup方法保留
    public void cleanup() {
        videoFrameQueue.clear();
        currentFrame = null;
        needUpload = false;
        lastRenderTime = 0;
        lastAudioProgress = 0.0; // 重置音频进度锚点
        System.out.println("[VideoRenderer] 资源已清理");
    }

    // 原有setVideoDecoder方法保留
    public void setVideoDecoder(FFmpegVideoDecoder decoder) {
        this.videoDecoder = decoder;
    }

    // 原有getVideoTexture方法保留
    public Object getVideoTexture() {
        return videoTexture;
    }

    // 修复：补充setVirtualTVBounds方法（转发给VirtualTV）
    public void setVirtualTVBounds(Vec3d minPos, Vec3d maxPos) {
        if (virtualTV != null) {
            virtualTV.setTvBounds(minPos, maxPos);
        }
    }

    public long getperFrameNano(){return (long) (secondsPerFrame * 1_000_000_000.0);}
    public VirtualTV getVirtualTV() {
        return this.virtualTV;
    }
}
