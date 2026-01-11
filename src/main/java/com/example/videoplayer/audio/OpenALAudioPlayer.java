package com.example.videoplayer.audio;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 重构后：锚定OpenAL硬件时序的音频播放器
 * 核心改造：
 * 1. 移除所有手动时长变量，仅基于OpenAL采样偏移计算播放进度
 * 2. 固定缓冲区数量（4个），避免一次性补充过多导致超速
 * 3. 所有OpenAL操作强制主线程执行
 * 4. 解码线程节流逻辑（基于队列缓冲时长休眠）
 * 5. 提供音频进度接口，供VideoRenderer同步视频帧
 */
public class OpenALAudioPlayer {
    private int alSource = 0;
    private int sampleRate;
    private int channels;
    private int alFormat;
    private boolean playing = false;
    // 新增：标记OpenAL是否初始化完成
    private volatile boolean openALInitialized = false;
    private final List<Integer> activeBuffers = new ArrayList<>();
    // 重构：固定队列容量（适配4个缓冲区的节流逻辑）
    public final LinkedBlockingQueue<ByteBuffer> audioQueue;
    private final String streamFormat;

    // 音频tick时间戳（仅用于固定tick频率）
    private long lastAudioTickTime = System.currentTimeMillis();
    // 重构：固定缓冲区数量（核心，避免超速）
    private static final int FIXED_BUFFER_COUNT = 12;
    // 解码节流：队列缓冲时长阈值（毫秒），超过则休眠解码线程
    private static final long DECODE_THROTTLE_THRESHOLD_MS = 200;
    // 解决主线程问题，添加volatile保证多线程可见性
    private volatile double PlaybackProgress;


    // 重载构造方法：支持指定流格式，固定队列容量
    public OpenALAudioPlayer(int sampleRate, int channels, String streamFormat) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.streamFormat = streamFormat;
        this.alFormat = channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;

        // 重构：固定队列容量（基于缓冲区数量*单缓冲区时长）
        // 单缓冲区约50ms音频数据 → 4个缓冲区对应200ms，匹配节流阈值
        int queueCapacity = FIXED_BUFFER_COUNT * 2;
        this.audioQueue = new LinkedBlockingQueue<>(queueCapacity);

        System.out.println("[OpenALAudioPlayer] 初始化：采样率=" + sampleRate + "Hz，流格式=" + streamFormat);
        System.out.println("[OpenALAudioPlayer] 固定缓冲区数量=" + FIXED_BUFFER_COUNT + "，解码队列容量=" + queueCapacity);
    }


    /**
     * 重构：使用executeAndWait确保初始化完成后再返回，避免时序脱钩
     */
    /**
     * 重构：同步初始化OpenAL资源，确保初始化完成后再返回，避免时序脱钩
     */
    public void init() throws InterruptedException {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            System.err.println("[OpenALAudioPlayer] 警告：init需在主线程执行，自动切换并等待...");
            // 同步执行，使用CountDownLatch等待初始化完成
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            client.execute(() -> {
                try {
                    innerInit();
                } finally {
                    latch.countDown();
                }
            });
            // 超时等待（1秒），避免无限阻塞
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new RuntimeException("OpenAL初始化超时，超过1秒");
            }
            return;
        }
        innerInit();
    }


    // 内部初始化方法，确保同步完成
    private void innerInit() {
        try {
            // 严格校验OpenAL上下文（主线程）
            long currentContext = ALC10.alcGetCurrentContext();
            if (currentContext == 0) {
                throw new RuntimeException("MC未初始化OpenAL上下文，无法创建源");
            }

            // 创建AL源（仅主线程）
            alSource = AL10.alGenSources();
            if (alSource == 0) {
                int error = AL10.alGetError();
                throw new RuntimeException("生成OpenAL源失败，错误码：" + error + " (" + getALErrorName(error) + ")");
            }

            // 标准化源参数
            AL10.alSourcef(alSource, AL10.AL_GAIN, 1.0f);
            AL10.alSourcef(alSource, AL10.AL_PITCH, 1.0f);
            AL10.alSource3f(alSource, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
            AL10.alSource3f(alSource, AL10.AL_VELOCITY, 0.0f, 0.0f, 0.0f);
            AL10.alSource3f(alSource, AL10.AL_DIRECTION, 0.0f, 0.0f, 0.0f);
            AL10.alSourcei(alSource, AL10.AL_LOOPING, AL10.AL_FALSE);

            this.openALInitialized = true;
            System.out.println("[OpenALAudioPlayer] 复用MC的OpenAL上下文成功，源ID：" + alSource);
        } catch (Exception e) {
            System.err.println("[OpenALAudioPlayer] 初始化失败：" + e.getMessage());
            e.printStackTrace();
            this.openALInitialized = false;
        }
    }


    /**
     * 核心重构：固定tick频率（20次/秒）+ 固定缓冲区数量管理
     * 所有OpenAL操作严格限制在主线程
     */
    public void tick() {
        if (alSource == 0 || !playing) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            // 非主线程则提交到主线程执行
            client.execute(this::tick);
            return;
        }

        try {
            // 固定音频tick频率（20次/秒，50ms间隔）
            long currentTime = System.currentTimeMillis();
            final long FIXED_AUDIO_TICK_INTERVAL = 50;
            if (currentTime - lastAudioTickTime < FIXED_AUDIO_TICK_INTERVAL) {
                return;
            }
            lastAudioTickTime = currentTime;

            // 1. 清理已播放的缓冲区（主线程）
            int processed = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_PROCESSED);
            if (processed > 0) {
                IntBuffer bufferIds = BufferUtils.createIntBuffer(processed);
                AL10.alSourceUnqueueBuffers(alSource, bufferIds);

                for (int i = 0; i < processed; i++) {
                    int bufferId = bufferIds.get(i);
                    AL10.alDeleteBuffers(bufferId);
                    activeBuffers.remove((Integer) bufferId);
                }
                //System.out.println("[OpenALTick] 清理已处理缓冲区：" + processed + "个，当前活跃缓冲区：" + activeBuffers.size());
            }

            // 2. 补充缓冲区到固定数量（核心：仅补充到4个，避免超速）
            int currentQueued = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED);
            // 补充：清除OpenAL错误，确保currentQueued获取准确
            int queuedError = AL10.alGetError();
            if (queuedError != AL10.AL_NO_ERROR) {
                System.err.println("[OpenALTick] 获取队列缓冲区数量失败：" + getALErrorName(queuedError));
                currentQueued = 0;
            }
            int needAdd = FIXED_BUFFER_COUNT - currentQueued;
            if (needAdd > 0 && !audioQueue.isEmpty()) {
                int addedCount = 0;
                while (addedCount < needAdd && !audioQueue.isEmpty()) {
                    ByteBuffer audioData = audioQueue.poll();
                    if (audioData != null) {
                        queueAudioBuffer(audioData);
                        addedCount++;
                    }
                }
                if (addedCount > 0) {
                    // 重新获取队列数量，确保后续播放触发准确
                    currentQueued = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED);
                    //System.out.println("[OpenALTick] 补充缓冲区：" + addedCount + "个，当前队列缓冲：" + audioQueue.size() + "，OpenAL源队列数量：" + currentQueued);
                }
            }

            // 3. 强化播放触发逻辑（核心：重试播放，无论当前状态是否为STOPPED）
            int sourceState = AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE);
            String stateName = getALStateName(sourceState);
            // 播放触发条件：队列中有缓冲区（currentQueued > 0），且未处于播放状态
            if (currentQueued > 0) {
                if (sourceState != AL10.AL_PLAYING) {
                    AL10.alSourcePlay(alSource);
                    // 检查播放是否触发成功
                    int playError = AL10.alGetError();
                    if (playError != AL10.AL_NO_ERROR) {
                        System.err.println("[OpenALTick] 重试音频播放失败：" + getALErrorName(playError) + "，当前队列数量：" + currentQueued);
                    } else {
                        int newState = AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE);
                        //System.out.println("[OpenALTick] 重试音频播放成功，队列数量：" + currentQueued + "，播放前状态：" + stateName + "，播放后状态：" + getALStateName(newState));
                    }
                } else {
                    //System.out.println("[OpenALTick] 音频正常播放中，队列数量：" + currentQueued + "，状态：" + stateName);
                }
            } else {
                System.err.println("[OpenALTick] 无法触发播放：OpenAL源队列为空，当前音频队列缓冲：" + audioQueue.size());
            }

        } catch (Exception e) {
            System.err.println("[OpenALAudioPlayer] Tick错误：" + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * 重构核心：获取音频真实播放进度（秒），基于OpenAL硬件采样偏移
     * 唯一时间基准，供VideoRenderer同步视频帧
     * @return 已播放音频秒数（精确到0.001秒）
     */
    /**
     * 重构：同步获取音频真实播放进度（秒），基于OpenAL硬件采样偏移
     * 唯一时间基准，供VideoRenderer同步视频帧
     * @return 已播放音频秒数（精确到0.001秒）
     */
    public double getPlaybackProgressInSeconds() {
        if (alSource == 0 || !playing || !openALInitialized) return 0.0;

        MinecraftClient client = MinecraftClient.getInstance();
        // 同步获取：非主线程时阻塞等待主线程执行完成
        if (!client.isOnThread()) {
            // 使用CountDownLatch实现同步等待
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            client.execute(() -> {
                try {
                    innerGetPlaybackProgress();
                } finally {
                    latch.countDown();
                }
            });
            try {
                // 超时等待，避免卡死（50ms足够，对应音频tick频率）
                latch.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[OpenALAudioPlayer] 获取播放进度等待中断：" + e.getMessage());
            }
            return this.PlaybackProgress;
        }
        return innerGetPlaybackProgress();
    }


    // 内部同步获取播放进度
    // 内部同步获取播放进度
    private double innerGetPlaybackProgress() {
        // 前置校验：如果 OpenAL 未初始化或源无效，直接返回当前进度（避免返回 0.0 覆盖有效值）
        if (!openALInitialized || alSource == 0) {
            System.err.println("[OpenALAudioPlayer] 无法获取进度：OpenAL 未初始化或源无效");
            return this.PlaybackProgress;
        }

        try {
            // 1. 先清除之前的 OpenAL 错误（避免干扰当前获取操作的错误判断）
            AL10.alGetError();

            // 2. 关键：获取硬件采样偏移量（AL11.AL_SAMPLE_OFFSET）
            int sampleOffset = AL11.alGetSourcei(alSource, AL11.AL_SAMPLE_OFFSET);

            // 3. 检查获取采样偏移是否出错（核心：OpenAL 错误检查，解决返回 0 的隐藏问题）
            int alError = AL10.alGetError();
            if (alError != AL10.AL_NO_ERROR) {
                String errorMsg = getALErrorName(alError);
                System.err.println("[OpenALAudioPlayer] 获取采样偏移失败：" + errorMsg + "，采样偏移返回值=" + sampleOffset);
                // 返回当前已保存的进度，避免返回 0.0 覆盖有效值
                return this.PlaybackProgress;
            }

            // 4. 计算播放进度（样本数 / 采样率 = 秒数，精确到毫秒级）
            double progress = (double) sampleOffset / this.sampleRate;

            // 5. 获取当前音频源状态（详细日志，排查是否真的在播放）
            int sourceState = AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE);
            String stateName = getALStateName(sourceState);

            // 6. 修复状态判断逻辑：无论是否播放，都更新进度（避免暂停时进度冻结在 0.0）
//            if (sourceState != AL10.AL_PLAYING) {
//                System.out.println("[OpenALAudioPlayer] 音频未播放（状态：" + stateName + "），当前进度=" + String.format("%.3f", progress) + "秒");
//            } else {
//                System.out.println("[OpenALAudioPlayer] 音频播放中（状态：" + stateName + "），采样偏移=" + sampleOffset + "，进度=" + String.format("%.3f", progress) + "秒");
//            }

            // 7. 更新全局进度变量（确保非主线程能获取到最新值）
            this.PlaybackProgress = progress;

            // 8. 返回有效进度
            return progress;

        } catch (Exception e) {
            System.err.println("[OpenALAudioPlayer] 获取播放进度异常：" + e.getMessage());
            e.printStackTrace();
            // 异常时返回当前已保存的进度，避免返回 0.0
            return this.PlaybackProgress;
        }
    }


    /**
     * 重构：解码线程节流逻辑（基于队列缓冲时长休眠）
     * 供FFmpegVideoDecoder调用，避免解码速度超过播放速度
     */
    public void throttleDecodeThread() {
        try {
            // 计算当前队列缓冲的音频时长（毫秒）
            int totalSamplesInQueue = 0;
            for (ByteBuffer buffer : audioQueue) {
                // 16位音频：每个样本2字节；立体声则每个样本占2通道*2字节=4字节
                int bytesPerSample = channels * 2;
                totalSamplesInQueue += buffer.remaining() / bytesPerSample;
            }
            long bufferedDurationMs = (long) (totalSamplesInQueue * 1000.0 / sampleRate);

            // 如果缓冲时长超过阈值，休眠解码线程（避免队列堆积导致超速）
            if (bufferedDurationMs > DECODE_THROTTLE_THRESHOLD_MS) {
                long sleepTime = bufferedDurationMs - DECODE_THROTTLE_THRESHOLD_MS;
                Thread.sleep(Math.min(sleepTime, 50)); // 单次休眠不超过50ms，避免卡死
                //System.out.println("[OpenALAudioPlayer] 解码节流：缓冲时长=" + bufferedDurationMs + "ms，休眠" + sleepTime + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[OpenALAudioPlayer] 解码节流休眠中断：" + e.getMessage());
        } catch (Exception e) {
            System.err.println("[OpenALAudioPlayer] 解码节流计算失败：" + e.getMessage());
        }
    }

    /**
     * 入队音频数据（带超时+节流）
     */
    public boolean offerAudioData(ByteBuffer audioBuffer) {
        if (audioBuffer == null || audioBuffer.remaining() == 0) return false;
        try {
            // M3U8入队超时延长到500ms，FLV为100ms
            long timeout = "m3u8".equals(streamFormat) ? 500 : 100;
            boolean success = audioQueue.offer(audioBuffer, timeout, TimeUnit.MILLISECONDS);
            if (!success) {
                //System.err.println("[OpenALAudioPlayer] 音频数据入队失败：队列已满（容量=" + audioQueue.size() + "/" + audioQueue.remainingCapacity() + ")");
            }
            // 入队后立即执行节流，从源头控制解码速度
            throttleDecodeThread();
            return success;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[OpenALAudioPlayer] 数据入队中断：" + e.getMessage());
            return false;
        }
    }

    /**
     * 内部：队列单个缓冲区（仅主线程调用）
     */
    private void queueAudioBuffer(ByteBuffer audioData) {
        if (alSource == 0 || !MinecraftClient.getInstance().isOnThread()) {
            System.err.println("[OpenALAudioPlayer] 缓冲区操作必须在主线程执行");
            return;
        }

        try {
            IntBuffer bufferId = BufferUtils.createIntBuffer(1);
            AL10.alGenBuffers(bufferId);
            int buffer = bufferId.get(0);

            if (buffer == 0) {
                int error = AL10.alGetError();
                System.err.println("[OpenALAudioPlayer] 创建缓冲区失败，错误码：" + error + " (" + getALErrorName(error) + ")");
                return;
            }

            // 1. 填充数据前清除旧错误
            AL10.alGetError();
            // 2. 填充数据并队列到源（关键：确保参数匹配）
            AL10.alBufferData(buffer, alFormat, audioData, sampleRate);
            // 3. 检查缓冲区填充是否成功（核心：避免无效缓冲区）
            int bufferError = AL10.alGetError();
            if (bufferError != AL10.AL_NO_ERROR) {
                System.err.println("[OpenALAudioPlayer] 填充缓冲区数据失败：" + getALErrorName(bufferError)
                        + "，采样率=" + sampleRate + "，格式=" + (alFormat == AL10.AL_FORMAT_STEREO16 ? "STEREO16" : "MONO16"));
                AL10.alDeleteBuffers(buffer); // 清理无效缓冲区
                return;
            }

            // 4. 队列缓冲区到音频源
            AL10.alSourceQueueBuffers(alSource, bufferId);
            int queueError = AL10.alGetError();
            if (queueError != AL10.AL_NO_ERROR) {
                System.err.println("[OpenALAudioPlayer] 队列缓冲区到源失败：" + getALErrorName(queueError));
                AL10.alDeleteBuffers(buffer); // 清理无效缓冲区
                return;
            }

            activeBuffers.add(buffer);
            //System.out.println("[OpenALAudioPlayer] 缓冲区队列成功：ID=" + buffer + "，当前活跃缓冲区数=" + activeBuffers.size());

        } catch (Exception e) {
            System.err.println("[OpenALAudioPlayer] 队列缓冲区失败：" + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * 播放控制（主线程）
     */
    public void play() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(this::innerPlay);
            return;
        }
        innerPlay();
    }

    private void innerPlay() {
        this.playing = true;
        if (alSource != 0 && openALInitialized) {
            AL10.alSourcePlay(alSource);
            System.out.println("[OpenALAudioPlayer] 音频播放已启动");
        }
    }

    /**
     * 暂停控制（主线程）
     */
    public void pause() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(this::innerPause);
            return;
        }
        innerPause();
    }

    private void innerPause() {
        this.playing = false;
        if (alSource != 0 && openALInitialized) {
            AL10.alSourcePause(alSource);
            System.out.println("[OpenALAudioPlayer] 音频已暂停");
        }
    }

    /**
     * 停止+清理（主线程）
     */
    public void cleanup() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(this::innerCleanup);
            return;
        }
        innerCleanup();
    }

    private void innerCleanup() {
        try {
            this.playing = false;
            this.openALInitialized = false;
            if (alSource != 0) {
                AL10.alSourceStop(alSource);
                AL10.alSourcei(alSource, AL10.AL_BUFFER, 0);

                // 清理所有活跃缓冲区
                for (int buffer : activeBuffers) {
                    AL10.alDeleteBuffers(buffer);
                }
                activeBuffers.clear();

                // 删除AL源
                AL10.alDeleteSources(alSource);
                alSource = 0;
            }

            // 清空队列
            audioQueue.clear();
            System.out.println("[OpenALAudioPlayer] 音频资源已完全清理");
        } catch (Exception e) {
            System.err.println("[OpenALAudioPlayer] 清理失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== 补全FFmpegVideoDecoder所需的缺失方法 ==========
    /**
     * 获取音频队列当前大小（供解码器判断队列是否堆积）
     */
    public int getAudioQueueSize() {
        return audioQueue.size();
    }

    /**
     * 弹出队列头部的音频数据（供解码器丢弃冗余帧，解决时长偏差）
     */
    public ByteBuffer pollAudioData() {
        return audioQueue.poll();
    }

    /**
     * 获取音频缓冲时长（毫秒）（供解码器初始化缓冲判断）
     */
    public long getAudioBufferDurationMs() {
        int totalSamplesInQueue = 0;
        int bytesPerSample = channels * 2;
        for (ByteBuffer buffer : audioQueue) {
            totalSamplesInQueue += buffer.remaining() / bytesPerSample;
        }
        return (long) (totalSamplesInQueue * 1000.0 / sampleRate);
    }

    /**
     * 获取并重置已播放时长（兼容解码器原有逻辑，实际返回0，因为已移除手动时长）
     */
    public long getAndResetPlayedAudioDuration() {
        return 0; // 不再需要手动维护，返回0避免解码器报错
    }

    // ========== 辅助方法 ==========
    // 辅助方法：获取AL错误名称
    private String getALErrorName(int error) {
        return switch (error) {
            case AL10.AL_NO_ERROR -> "AL_NO_ERROR";
            case AL10.AL_INVALID_NAME -> "AL_INVALID_NAME";
            case AL10.AL_INVALID_ENUM -> "AL_INVALID_ENUM";
            case AL10.AL_INVALID_VALUE -> "AL_INVALID_VALUE";
            case AL10.AL_INVALID_OPERATION -> "AL_INVALID_OPERATION";
            case AL10.AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY";
            default -> "UNKNOWN_ERROR(" + error + ")";
        };
    }

    // 辅助方法：获取AL状态名称
    public String getALStateName(int state) {
        return switch (state) {
            case AL10.AL_INITIAL -> "AL_INITIAL";
            case AL10.AL_PLAYING -> "AL_PLAYING";
            case AL10.AL_PAUSED -> "AL_PAUSED";
            case AL10.AL_STOPPED -> "AL_STOPPED";
            default -> "UNKNOWN_STATE(" + state + ")";
        };
    }

    // ========== Getter（供外部调用） ==========
    public boolean isPlaying() {
        return playing;
    }

    public boolean isOpenALInitialized() {
        return openALInitialized;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    // 移除手动时长相关的setter（冗余，无需再维护）
    public void setSingleBufferExactDurationMs(long durationMs) {
        // 空实现，不再需要手动维护单缓冲区时长
    }
}
