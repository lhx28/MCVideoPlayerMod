package com.example.videoplayer;

import com.example.videoplayer.audio.OpenALAudioPlayer;
import com.example.videoplayer.decoder.FFmpegVideoDecoder;
import com.example.videoplayer.render.VideoRenderer;
import com.example.videoplayer.util.VideoInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.util.GlException;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

/**
 * 仅使用glTexImage2D的视频播放器Mod核心类（服务端触发版）
 * 核心修改：对齐B站音乐插件，使用MC内置编解码方法，解决解码异常
 */
public class VideoPlayerMod implements ClientModInitializer {
    // 与服务端保持一致的插件消息通道ID
    public static final Identifier CHANNEL_ID = Identifier.of("videoplayer", "main");
    public static final Identifier VIDEO_TEXTURE_ID = Identifier.of("videoplayer", "video_texture");

    // 支持的视频格式（同步服务端）
    public static final List<String> SUPPORTED_VIDEO_FORMATS = Arrays.asList(
            "m3u8", "flv", "default"
    );

    private static DrawContext currentDrawContext;
    private FFmpegVideoDecoder videoDecoder;
    private OpenALAudioPlayer audioPlayer;
    private VideoRenderer videoRenderer;
    private final MinecraftClient mc = MinecraftClient.getInstance();
    public static VideoPlayerMod INSTANCE;
    private boolean isVideoPlaying = false;
    private final Lock cleanupLock = new ReentrantLock();
    private volatile boolean isCleaning = false;
    private Object videoTexture;
    private GpuTextureView gpuTextureView;

    // 核心修复：强化纹理状态管理
    private final StampedLock textureUpdateLock = new StampedLock();
    private volatile boolean isTextureValid = false;
    private volatile int cachedTexWidth = 0;
    private volatile int cachedTexHeight = 0;
    private volatile int maxTextureSize = 0;

    private volatile boolean isTextureInited = false;

    // 自定义纹理类（不变）
    public static class Tex extends AbstractTexture {
        public Tex(GpuTexture tex, GpuTextureView view) {
            this.glTexture = tex;
            this.glTextureView = view;
        }
    }

    // 定义VideoPayload，匹配服务端数据包格式（使用MC内置编解码方法）
    public record VideoPayload(
            byte commandType,
            String videoUrl,
            String videoFormat,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            double yaw
    ) implements CustomPayload {
        public static final Id<VideoPayload> ID = new Id<>(CHANNEL_ID);

        // 核心修改：使用MC内置readString()/writeString()，放弃手动VarInt
        public static final PacketCodec<PacketByteBuf, VideoPayload> CODEC = PacketCodec.of(
                (value, buf) -> {
                    // 写入逻辑：对齐服务端，使用内置方法
                    buf.writeByte(value.commandType);
                    buf.writeString(value.videoUrl); // 内置UTF-8 + VarInt，无需手动实现
                    buf.writeString(value.videoFormat);
                    buf.writeDouble(value.startX);
                    buf.writeDouble(value.startY);
                    buf.writeDouble(value.startZ);
                    buf.writeDouble(value.endX);
                    buf.writeDouble(value.endY);
                    buf.writeDouble(value.endZ);
                    buf.writeDouble(value.yaw);
                },
                buf -> {
                    // 参考B站音乐插件，添加详细调试日志
                    int initialReadableBytes = buf.readableBytes();
                    System.out.println("[VideoPlayerMod] 开始解码Payload，可读字节数: " + initialReadableBytes);

                    try {
                        // 读取命令类型
                        byte commandType = buf.readByte();
                        System.out.println("[VideoPlayerMod] 读取命令类型: " + (commandType == 0 ? "播放" : "停止"));

                        // 读取字符串：使用内置方法，避免手动解码偏差
                        String videoUrl = buf.readString();
                        String videoFormat = buf.readString();
                        //System.out.println("[VideoPlayerMod] 读取视频链接: " + videoUrl);
                        //System.out.println("[VideoPlayerMod] 读取视频格式: " + videoFormat);

                        // 读取坐标和旋转角
                        double startX = buf.readDouble();
                        double startY = buf.readDouble();
                        double startZ = buf.readDouble();
                        double endX = buf.readDouble();
                        double endY = buf.readDouble();
                        double endZ = buf.readDouble();
                        double yaw = buf.readDouble();
                        //System.out.println("[VideoPlayerMod] 读取坐标完成，旋转角: " + yaw);

                        // 检查剩余字节
                        int remainingBytes = buf.readableBytes();
                        if (remainingBytes > 0) {
                            System.out.println("[VideoPlayerMod] WARNING: 还有 " + remainingBytes + " 字节未读取!");
                        } else {
                            System.out.println("[VideoPlayerMod] 所有字节已正确读取");
                        }

                        return new VideoPayload(
                                commandType, videoUrl, videoFormat,
                                startX, startY, startZ, endX, endY, endZ, yaw
                        );
                    } catch (Exception e) {
                        System.err.println("[VideoPlayerMod] Payload解码失败: " + e.getMessage());
                        e.printStackTrace();
                        // 返回默认值，避免客户端崩溃
                        return new VideoPayload(
                                (byte)0, "", "",
                                0, 0, 0, 0, 0, 0, 0
                        );
                    }
                }
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 生成非正方形视频纹理（修正maxTextureSize获取方式）
     */
    public Object genTexture(int width, int height) {
        if (isTextureValid) {
            releaseTexture();
        }

        if (maxTextureSize == 0) {
            try {
                maxTextureSize = RenderSystem.getDevice().getMaxTextureSize();
            } catch (Exception e) {
                System.out.println("[VideoPlayerMod] 无法获取GPU最大纹理尺寸，系统可能不稳定");
            }
            System.out.println("[VideoPlayerMod] GPU最大纹理尺寸：" + maxTextureSize);
        }
        int safeWidth = Math.min(width, maxTextureSize);
        int safeHeight = Math.min(height, maxTextureSize);
        if (safeWidth != width || safeHeight != height) {
            System.err.println("[VideoPlayerMod] 纹理尺寸超限，自动调整：" + width + "x" + height + " → " + safeWidth + "x" + safeHeight);
        }

        var device = RenderSystem.getDevice();
        var tex = device.createTexture("videoplayer:video_textured", 5, TextureFormat.RGBA8, safeWidth, safeHeight, 1, 1);
        tex.setTextureFilter(FilterMode.NEAREST, false);

        var view = device.createTextureView(tex);
        Tex videoTex = new Tex(tex, view);
        this.gpuTextureView = view;
        MinecraftClient.getInstance().getTextureManager().registerTexture(VIDEO_TEXTURE_ID, videoTex);

        this.videoTexture = tex;
        this.cachedTexWidth = safeWidth;
        this.cachedTexHeight = safeHeight;
        this.isTextureValid = true;

        int glId = ((GlTexture) tex).getGlId();
        System.out.println("[VideoPlayerMod] 生成纹理：" + safeWidth + "x" + safeHeight + "，GL ID：" + glId);
        return tex;
    }

    // 强化纹理释放的线程同步（修复双重释放）
    private void releaseTexture() {
        long stamp = textureUpdateLock.writeLock();
        try {
            if (!isTextureValid) return;

            final boolean[] released = {false};
            mc.execute(() -> {
                try {
                    if (gpuTextureView != null) {
                        gpuTextureView.close();
                        gpuTextureView = null;
                    }
                    if (videoTexture instanceof GpuTexture gpuTex) {
                        gpuTex.close();
                    }
                } catch (Exception e) {
                    System.err.println("[VideoPlayerMod] 释放纹理失败：" + e.getMessage());
                } finally {
                    videoTexture = null;
                    cachedTexWidth = 0;
                    cachedTexHeight = 0;
                    isTextureValid = false;
                    isTextureInited = false; // 新增：重置纹理初始化标记
                    released[0] = true;
                }
            });
            long waitStart = System.currentTimeMillis();
            while (!released[0] && System.currentTimeMillis() - waitStart < 1000) {
                Thread.sleep(10);
            }
            System.out.println("[VideoPlayerMod] 纹理资源已安全释放");
        } catch (Exception e) {
            System.err.println("[VideoPlayerMod] 释放纹理异常：" + e.getMessage());
        } finally {
            textureUpdateLock.unlockWrite(stamp);
        }
    }


    // 获取GL ID（不变）
    public int getVideoTextureGLId() {
        long stamp = textureUpdateLock.readLock();
        try {
            if (!isTextureValid || videoTexture == null) {
                return -1;
            }
            if (this.videoTexture instanceof GlTexture glTexture) {
                int glId = glTexture.getGlId();
                return glId > 0 ? glId : -1;
            }
            return -1;
        } finally {
            textureUpdateLock.unlockRead(stamp);
        }
    }

    /**
     * 修复：补全非渲染线程更新逻辑 + 严格参数校验
     */
    public void updateTexture(Object tex, int width, int height, ByteBuffer byteBuffer) {
        if (!isTextureValid || tex == null || byteBuffer == null || !byteBuffer.hasRemaining()
                || width <= 0 || height <= 0) {
            System.err.println("[VideoPlayerMod] 纹理更新前置校验失败");
            return;
        }

        int expectedSize = width * height * 4;
        if (byteBuffer.remaining() != expectedSize) {
            System.err.println("[VideoPlayerMod] 缓冲区尺寸不匹配：期望" + expectedSize + "，实际" + byteBuffer.remaining());
            return;
        }

        if (tex instanceof GlTexture tex1) {
            int glId = tex1.getGlId();
            if (glId <= 0) {
                isTextureValid = false;
                return;
            }

            ByteBuffer safeBuffer = copyByteBuffer(byteBuffer);
            if (safeBuffer == null) return;

            if (RenderSystem.isOnRenderThread()) {
                updateGLTextureWithOpenGL(glId, width, height, safeBuffer);
            } else {
                mc.execute(() -> {
                    if (isTextureValid && glId > 0) {
                        updateGLTextureWithOpenGL(glId, width, height, safeBuffer);
                    }
                });
            }
        }
    }

    // 缓冲区拷贝（不变）
    private ByteBuffer copyByteBuffer(ByteBuffer original) {
        try {
            original.mark();
            ByteBuffer copy = MemoryUtil.memAlloc(original.remaining());
            copy.order(original.order());
            copy.put(original);
            copy.flip();
            original.reset();
            return copy;
        } catch (Exception e) {
            System.err.println("[VideoPlayerMod] 拷贝缓冲区失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 核心修改：移除glTexSubImage2D，仅使用glTexImage2D更新纹理
     */
    private void updateGLTextureWithOpenGL(int glId, int width, int height, ByteBuffer byteBuffer) {
        long stamp = textureUpdateLock.writeLock();
        boolean bufferFreed = false;
        try {
            if (!isTextureValid || glId <= 0 || byteBuffer == null || !byteBuffer.hasRemaining()) {
                if (byteBuffer != null) {
                    try {
                        MemoryUtil.memFree(byteBuffer);
                        bufferFreed = true;
                    } catch (Exception e) {}
                }
                return;
            }
            if (!RenderSystem.isOnRenderThread()) {
                System.err.println("[VideoPlayerMod] 非渲染线程跳过GL操作");
                MemoryUtil.memFree(byteBuffer);
                bufferFreed = true;
                return;
            }

            while (GL11.glGetError() != GL11.GL_NO_ERROR);


            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            // ========== 核心修复：OpenGL 纹理行步长和对齐参数（解决放大后花屏） ==========
            // 1. 设置像素解包对齐（1字节对齐，适配所有视频宽度，避免行偏移）
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            // 2. 设置行步长（匹配视频帧实际宽度，而非纹理宽度，解决放大后行偏移累积）
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width); // 行步长=实际帧宽度
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, width);
            // 3. 关闭行步长偏移（避免额外的像素偏移）
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);

            try {
                // 分支逻辑：对齐示例，首次创建 vs 后续更新
                if (!isTextureInited) {
                    // 首次创建纹理：使用 glTexImage2D
                    GL11.glTexImage2D(
                            GL11.GL_TEXTURE_2D,
                            0,
                            GL11.GL_RGBA8,
                            width, height,
                            0,
                            GL11.GL_RGBA,
                            GL11.GL_UNSIGNED_BYTE,
                            byteBuffer
                    );
                    isTextureInited = true; // 标记为已初始化，后续走更新逻辑
                    cachedTexWidth = width; // 更新缓存纹理尺寸
                    cachedTexHeight = height;
                    //System.out.println("[VideoPlayerMod] 首次创建纹理：" + width + "x" + height);
                } else {
                    // 后续更新纹理：复用纹理对象，使用 glTexSubImage2D 降低开销
                    // 额外校验：纹理尺寸未变化才执行更新（glTexSubImage2D 无法修改纹理尺寸）
                    if (width == cachedTexWidth && height == cachedTexHeight) {
                        GL11.glTexSubImage2D(
                                GL11.GL_TEXTURE_2D,
                                0,
                                0, 0, // 偏移量：从纹理左上角开始更新
                                width, height,
                                GL11.GL_RGBA,
                                GL11.GL_UNSIGNED_BYTE,
                                byteBuffer
                        );
                        //System.out.println("[VideoPlayerMod] 更新纹理数据：" + width + "x" + height);
                    } else {
                        // 纹理尺寸变化，回退到 glTexImage2D 重新创建
                        GL11.glTexImage2D(
                                GL11.GL_TEXTURE_2D,
                                0,
                                GL11.GL_RGBA8,
                                width, height,
                                0,
                                GL11.GL_RGBA,
                                GL11.GL_UNSIGNED_BYTE,
                                byteBuffer
                        );
                        cachedTexWidth = width; // 更新缓存纹理尺寸
                        cachedTexHeight = height;
                        //System.out.println("[VideoPlayerMod] 纹理尺寸变化，重新创建纹理：" + width + "x" + height);
                    }
                }

                // 释放缓冲区
                MemoryUtil.memFree(byteBuffer);
                bufferFreed = true;

                // 检测GL错误
                int glError = GL11.glGetError();
                if (glError != GL11.GL_NO_ERROR) {
                    System.err.println("[VideoPlayerMod] OpenGL纹理操作错误：" + glError + "（对应GL ID：" + glId + "）");
                    isTextureValid = false;
                    isTextureInited = false; // 错误时重置初始化标记
                }
            } catch (GlException e) {
                System.err.println("[VideoPlayerMod] OpenGL原生错误（避免崩溃）：" + e.getMessage());
                isTextureValid = false;
                isTextureInited = false; // 错误时重置初始化标记
            } catch (Exception e) {
                System.err.println("[VideoPlayerMod] 更新纹理数据异常：" + e.getMessage());
                isTextureValid = false;
                isTextureInited = false; // 错误时重置初始化标记
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                if (!bufferFreed) {
                    try {
                        MemoryUtil.memFree(byteBuffer);
                    } catch (Exception e) {}
                }
            }
        } catch (Exception e) {
            System.err.println("[VideoPlayerMod] GL纹理更新外层异常：" + e.getMessage());
            isTextureValid = false;
            isTextureInited = false;
        } finally {
            textureUpdateLock.unlockWrite(stamp);
        }
    }

    /**
     * 关键修复：修正drawPic的矩阵操作和drawTexture参数（解决画面渲染异常）
     */
    public void drawPic(Object texture, int width, int height, int x, int y, int ang) {
        if (currentDrawContext == null || !isTextureValid) return;

        Matrix3x2fStack stack = currentDrawContext.getMatrices();
        stack.pushMatrix();

        int centerX = width / 2;
        int centerY = height / 2;

        stack.translate(x + centerX, y + centerY);
        if (ang > 0) {
            stack.rotate((float) Math.toRadians(ang));
        }
        stack.scale(1, -1);

        currentDrawContext.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                VIDEO_TEXTURE_ID,
                -centerX,
                -centerY,
                0, 0,
                width, height,
                width, height,
                width, height
        );
        stack.popMatrix();

        //System.out.println("[VideoPlayerMod] 绘制视频：尺寸" + width + "x" + height + "，坐标(" + x + "," + y + ")，纹理有效=" + isTextureValid);
    }

    /**
     * 初始化音频播放器，兼容更多视频格式
     */
    private void initAudioPlayer(FFmpegVideoDecoder decoder, String videoFormat) {
        VideoInfo info = decoder.getVideoInfo();
        this.audioPlayer = new OpenALAudioPlayer(
                info.getSampleRate(),
                info.getAudioChannels(),
                videoFormat
        );

        try {
            mc.execute(() -> {
                try {
                    audioPlayer.init();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                int alFormat = info.getAudioChannels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                System.out.println("[VideoPlayerMod] 音频初始化成功：采样率" + info.getSampleRate() + "，格式" + videoFormat);
            });
        } catch (Exception e) {
            System.err.println("[VideoPlayerMod] 音频初始化失败：" + e.getMessage());
        }
    }

    /**
     * 服务端触发播放，扩充格式支持，使用服务端传递的坐标
     */
    private void playLiveStream(String streamUrl, String videoFormat, Vec3d startPos, Vec3d endPos, float yaw) {
        if (isVideoPlaying) {
            stopVideo();
        }
        if (isCleaning) {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("§c资源清理中，请稍后再播放"), false);
            }
            return;
        }

        // 修复：提前赋值，确保HudRender能检测到播放状态
        isVideoPlaying = true; // 移到这里，不依赖后续步骤是否成功
        try {
            String finalFormat = videoFormat.toLowerCase();
//            if (!SUPPORTED_VIDEO_FORMATS.contains(finalFormat) || finalFormat.equals("default")) {
//                finalFormat = autoDetectVideoFormat(streamUrl);
//                System.out.println("[VideoPlayerMod] 自动探测视频格式：" + finalFormat);
//            }

            videoDecoder = new FFmpegVideoDecoder(streamUrl, finalFormat);
            VideoInfo videoInfo = videoDecoder.getVideoInfo();
            initAudioPlayer(videoDecoder, finalFormat);
            videoRenderer = new VideoRenderer(this, videoInfo.getWidth(), videoInfo.getHeight(), videoInfo.getFrameRate());

            // ========== 核心修复：补全这行代码，绑定音频播放器 ==========
            videoDecoder.setAudioPlayer(audioPlayer); // 缺失的关键代码，建立音频帧传递链路
            // ==========================================================

            videoRenderer.setVideoDecoder(videoDecoder);
            videoRenderer.setVirtualTVBounds(startPos, endPos);
            videoRenderer.getVirtualTV().setRotationYaw(yaw);

            videoDecoder.startDecoding();
            mc.execute(() -> {
                if (audioPlayer != null) {
                    audioPlayer.play();
                }
            });

            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("§a开始播放" + videoInfo.getTitle() + "（格式：" + finalFormat + "，旋转角：" + yaw + "°）"), false);
            }
        } catch (Exception e) {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("§c直播播放失败: " + e.getMessage()), false);
            }
            e.printStackTrace();
            stopVideo(); // 异常时停止，同时在stopVideo中设isVideoPlaying=false
        }
    }



    /**
     * 自动探测视频格式（根据URL后缀）
     */
    private String autoDetectVideoFormat(String streamUrl) {
        String lowerUrl = streamUrl.toLowerCase();
        for (String format : SUPPORTED_VIDEO_FORMATS) {
            if (format.equals("default")) continue;
            if (lowerUrl.endsWith("." + format) || lowerUrl.contains("format=" + format)) {
                return format;
            }
        }
        return "m3u8";
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        registerNetworkHandlers();

        // 保留Hud渲染回调
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            currentDrawContext = drawContext;
            if (client.player != null && client.player.isAlive()) {
                if (videoRenderer != null && isVideoPlaying) {
                    videoRenderer.updateFrame();
                }
                if (videoRenderer != null) {
                    videoRenderer.render();
                }
            }
        });

        // 保留客户端Tick回调
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (audioPlayer != null) {
                audioPlayer.tick();
            }
        });

        // 保留3D世界渲染回调
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (isVideoPlaying && videoRenderer != null) {
                try {
                    videoRenderer.getVirtualTV().renderTvScreen(context.matrixStack());
                } catch (Exception e) {
                    System.out.println("[VideoPlayerMod] 3D渲染失败：" + e.getMessage());
                }
            }
        });
    }

    /**
     * 注册网络处理器，传递旋转角参数（对齐B站音乐插件）
     */
    private void registerNetworkHandlers() {
        // 1. 注册Payload类型
        PayloadTypeRegistry.playS2C().register(VideoPayload.ID, VideoPayload.CODEC);
        System.out.println("[VideoPlayerMod] 网络通道注册成功：" + CHANNEL_ID);

        // 2. 注册全局接收器
        ClientPlayNetworking.registerGlobalReceiver(VideoPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();

            // 3. 处理播放命令（命令类型0）
            if (payload.commandType() == 0) {
                String videoUrl = payload.videoUrl();
                String videoFormat = payload.videoFormat();
                if (videoUrl.isEmpty()) {
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§c视频链接为空，无法播放"), false);
                        }
                    });
                    return;
                }

                Vec3d startPos = new Vec3d(payload.startX(), payload.startY(), payload.startZ());
                Vec3d endPos = new Vec3d(payload.endX(), payload.endY(), payload.endZ());
                float yaw = (float) payload.yaw();

                // 提交到MC主线程执行
                client.execute(() -> playLiveStream(videoUrl, videoFormat, startPos, endPos, yaw));
            }

            // 4. 处理停止命令（命令类型1）
            else if (payload.commandType() == 1) {
                client.execute(this::stopVideo);
            }
        });

        // 5. 监听客户端断开连接
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            stopVideo();
            isVideoPlaying = false;
        });
    }

    /**
     * 停止视频播放，清理资源
     */
    private void stopVideo() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (isCleaning || !cleanupLock.tryLock()) {
            return;
        }

        try {
            isCleaning = true;
            System.out.println("[VideoPlayerMod] 开始清理资源...");

            releaseTexture();

            // 停止音频
            if (audioPlayer != null) {
                if (mc.isOnThread()) {
                    audioPlayer.cleanup();
                } else {
                    final boolean[] audioDone = {false};
                    mc.execute(() -> {
                        audioPlayer.cleanup();
                        audioDone[0] = true;
                    });
                    long waitStart = System.currentTimeMillis();
                    while (!audioDone[0] && System.currentTimeMillis() - waitStart < 500) {
                        Thread.sleep(50);
                    }
                }
                audioPlayer = null;
            }

            // 停止视频解码器/渲染器
            if (videoDecoder != null) {
                videoDecoder.stop();
                Thread.sleep(500);
                videoDecoder = null;
            }

            if (videoRenderer != null) {
                if (mc.isOnThread()) {
                    videoRenderer.cleanup();
                } else {
                    final boolean[] cleanupDone = {false};
                    mc.execute(() -> {
                        videoRenderer.cleanup();
                        cleanupDone[0] = true;
                    });
                    long waitStart = System.currentTimeMillis();
                    while (!cleanupDone[0] && System.currentTimeMillis() - waitStart < 2000) {
                        Thread.sleep(50);
                    }
                }
                videoRenderer = null;
            }

            if (mc.player != null) {
                mc.execute(() -> mc.player.sendMessage(Text.literal("§a视频播放已停止"), false));
            }
            isVideoPlaying = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[VideoPlayerMod] 清理资源异常：" + e.getMessage());
        } finally {
            isTextureValid = false;
            isCleaning = false;
            cleanupLock.unlock();
        }
    }

    // Getter方法（不变）
    public DrawContext getCurrentDrawContext() {
        return currentDrawContext;
    }

    public int getScreenWidth() {
        return mc.getWindow().getScaledWidth();
    }

    public int getScreenHeight() {
        return mc.getWindow().getScaledHeight();
    }

    public GpuTextureView getGpuTextureView() {
        return this.gpuTextureView;
    }

    public double getPlaybackProgressInSeconds() {
        if (audioPlayer == null || !isVideoPlaying) {
            return 0.0;
        }
        return audioPlayer.getPlaybackProgressInSeconds();
    }

    public boolean isVideoPlaying() {
        return isVideoPlaying;
    }
}
