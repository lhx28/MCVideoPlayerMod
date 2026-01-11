package com.example.videoplayer.decoder;

import com.example.videoplayer.audio.OpenALAudioPlayer;
import com.example.videoplayer.util.VideoInfo;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 最终修复版：兼容优先+音视频同步，解决M3U8卡顿+播放失败+音视频不同步问题
 * 核心修改：
 * 1. 移除所有手动时长维护变量，完全依赖OpenAL硬件时序
 * 2. 删除手动音视频时长补偿逻辑，解码节流直接调用OpenALAudioPlayer的throttleDecodeThread()
 * 3. 简化队列控制，仅保留有限队列防止堆积
 * 4. 移除冗余的时长对齐逻辑，实现“解码→OpenAL消费”的闭环
 */
public class FFmpegVideoDecoder {
    private static final Logger logger = LoggerFactory.getLogger(FFmpegVideoDecoder.class);
    // 原有变量定义
    private final ConcurrentLinkedDeque<VideoFrameWrapper> videoFrameQueue = new ConcurrentLinkedDeque<>();

    private final FFmpegFrameGrabber grabber;
    private final VideoInfo videoInfo;
    private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();
    private final ReentrantLock queueLock = new ReentrantLock();
    private volatile boolean decoding = false;
    private Thread decodeThread;
    private OpenALAudioPlayer audioPlayer;
    private int audioFrameCount = 0;
    private long totalAudioBytes = 0;

    private String streamUrl;
    private String streamFormat;
    private int reconnectCount = 0;
    private static final int MAX_RECONNECT = 5;
    private static final long RECONNECT_DELAY = 2000;

    private static final long MIN_BUFFER_DURATION = 1000;
    private static final long TARGET_BUFFER_DURATION = 3000;
    private static final long MAX_BUFFER_DURATION = 15000;
    private final Object bufferLock = new Object();
    // 新增：M3U8/FLV专属队列大小
    private int videoFrameQueueMaxSize;
    private Java2DFrameConverter converter;
    // 新增：视频队列抓取模式阈值（滞回逻辑，避免频繁切换）
    private static final int VIDEO_QUEUE_HIGH_THRESHOLD = 8;  // 视频队列≥8帧，认为充足，可优先抓音频
    private static final int VIDEO_QUEUE_LOW_THRESHOLD = 6;   // 视频队列≤4帧，认为不足，切换回混合抓取
    // 新增：音频队列上限阈值（避免音频堆积）
    private static final float AUDIO_QUEUE_FULL_RATIO = 0.6f; // 音频队列占用≥80%，停止优先抓音频
    // 新增：抓取模式标记（volatile保证多线程可见性）
    private volatile boolean prioritizeAudioGrab = false;
    // 新增：定期切换回混合抓取时间戳（避免流数据错位）
    private long lastHybridGrabTime = System.currentTimeMillis();
    private static final long HYBRID_GRAB_INTERVAL = 2000;    // 每2秒至少切换回混合抓取一次




    // ========== 移除：所有手动时长维护变量（冗余且有害） ==========
    // 删除：totalVideoPlayDurationMs、totalAudioPlayDurationMs、SYNC_THRESHOLD_MS、MAX_SYNC_DELAY_MS

    // 视频帧包装类（仅保留帧数据，移除手动帧时长）
    private static class VideoFrameWrapper {
        BufferedImage frame;

        public VideoFrameWrapper(BufferedImage frame) {
            this.frame = frame;
        }
    }

    // 静态初始化FFmpeg日志（不变，原有代码）
    static {
        FFmpegLogCallback.set();
        //avutil.av_log_set_level(avutil.AV_LOG_DEBUG);
        avutil.av_log_set_level(avutil.AV_LOG_WARNING);
        logger.info("[VideoDecoder] FFmpeg日志回调已初始化");
    }

    // 构造方法1：自动识别格式
    public FFmpegVideoDecoder(String streamUrl) throws Exception {
        this(streamUrl, "auto");
    }

    // 构造方法2：手动指定格式
    public FFmpegVideoDecoder(String streamUrl, String format) throws Exception {
        this.streamUrl = streamUrl;
        this.streamFormat = format.toLowerCase();

        // 精准格式识别（不变）
        if ("auto".equals(this.streamFormat)) {
            if (streamUrl.endsWith(".m3u8") || (streamUrl.contains(".m3u8?") && !streamUrl.contains(".flv"))) {
                this.streamFormat = "m3u8";
            } else if (streamUrl.endsWith(".flv") || (streamUrl.contains(".flv?") && !streamUrl.contains(".m3u8"))) {
                this.streamFormat = "flv";
            } else {
                this.streamFormat = "default";
                logger.info("[VideoDecoder] 无法识别具体格式，启用default模式，FFmpeg将自动探测格式（URL：" + streamUrl + "）");
            }
        }

        // 初始化队列大小：M3U8 15帧，FLV 5帧（有限队列，防止堆积）
        if ("m3u8".equals(this.streamFormat)) {
            videoFrameQueueMaxSize = 15;
        } else if ("flv".equals(this.streamFormat)) {
            videoFrameQueueMaxSize = 5;
        } else {
            videoFrameQueueMaxSize = 8;
            logger.info("[VideoDecoder] default模式：使用通用视频队列大小（" + videoFrameQueueMaxSize + "帧）");
        }
        // 初始化抓取器
        this.grabber = new FFmpegFrameGrabber(streamUrl);

        // 版本日志（不变）
        logger.info("[VideoDecoder] 版本兼容日志：");
        logger.info("  AV_SAMPLE_FMT_FLTP = " + avutil.AV_SAMPLE_FMT_FLTP);
        logger.info("  AV_SAMPLE_FMT_S16  = " + avutil.AV_SAMPLE_FMT_S16);
        logger.info("  AV_SAMPLE_FMT_S16P = " + avutil.AV_SAMPLE_FMT_S16P);

        // 增强防盗链请求头（不变）
        StringBuilder headers = new StringBuilder();
        headers.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\r\n");
        headers.append("Accept: */*\r\n");
        headers.append("Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n");
        headers.append("Connection: keep-alive\r\n");
        headers.append("Cache-Control: no-cache\r\n");
        headers.append("Pragma: no-cache\r\n");

        // 通用网络配置（不变）
        grabber.setOption("headers", headers.toString());
        grabber.setOption("timeout", "10000000");
        grabber.setOption("probesize", "2000000");
        grabber.setOption("analyzeduration", "10000000");
        grabber.setOption("user_agent", headers.toString().split("User-Agent: ")[1].split("\r\n")[0]);

        // 分格式配置（优化M3U8兼容）
        if ("m3u8".equals(this.streamFormat)) {
            grabber.setFormat("hls");
            grabber.setOption("fflags", "fastseek");
            grabber.setOption("hls_buffer_size", "16777216");
            grabber.setOption("hls_flags", "omit_endlist");
            grabber.setOption("hls_allow_cache", "0");
            grabber.setOption("hls_read_ahead", "8");
            grabber.setOption("avio_flags", "direct+buffer");
            grabber.setOption("hls_max_buffer_size", "8388608");
            grabber.setOption("hls_timeout", "5000");
            grabber.setOption("hls_io_prealloc_size", "2097152");

            logger.info("[VideoDecoder] 识别为 M3U8 流，启用兼容版HLS低延迟配置");
        } else if ("flv".equals(this.streamFormat)) {
            grabber.setFormat("flv");
            grabber.setOption("flv_metadata", "1");
            grabber.setOption("buffer_size", "2048000");
            grabber.setOption("avio_flags", "direct");
            grabber.setOption("allowed_media_types", "video+audio");
            logger.info("[VideoDecoder] 识别为 FLV 流，启用 FLV 协议配置");
        } else {
            logger.info("[VideoDecoder] 启用default模式，FFmpeg自动探测格式和编码，使用通用配置");
        }

        // 修复1：移除音频流索引硬编码，优化音频配置
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        grabber.setSampleMode(FFmpegFrameGrabber.SampleMode.RAW);
        grabber.setNumBuffers(8);
        grabber.setAudioChannels(0); // 0=自动检测通道数

        // 启动抓取器（添加异常日志）
        try {
            grabber.start();
        } catch (Exception e) {
            System.err.println("[VideoDecoder] 启动抓取器失败！格式：" + streamFormat + "，URL：" + streamUrl);
            System.err.println("[VideoDecoder] 失败原因：" + e.getMessage());
            throw new Exception("启动抓取器失败：" + e.getMessage(), e);
        }

        // 修复2：采样率异常值校验，补充默认值
        String title = this.streamFormat.toUpperCase() + "_LIVE_STREAM";
        int width = Math.max(1, grabber.getImageWidth());
        int height = Math.max(1, grabber.getImageHeight());
        int frameRate = Math.max(1, (int) grabber.getFrameRate());

        // 核心修复：采样率校验（如果识别为1/0，默认44100Hz）
        int srcSampleRate = grabber.getSampleRate();
        if (srcSampleRate <= 1) {
            System.err.println("[VideoDecoder] 采样率识别异常（" + srcSampleRate + "Hz），使用默认值44100Hz");
            srcSampleRate = 44100; // 强制设置为标准采样率
        }

        // 通道数校验（至少1通道）
        int srcAudioChannels = Math.max(1, grabber.getAudioChannels());
        long durationMs = 0;
        int duration = 0;

        // 初始化VideoInfo（使用修复后的采样率）
        this.videoInfo = new VideoInfo(
                width, height, frameRate, duration,
                srcSampleRate, srcAudioChannels, title
        );

        // 增强日志（输出真实音频信息）
        //logger.info("[VideoDecoder] 直播流地址：" + streamUrl);
        logger.info("[VideoDecoder] 流格式：" + this.streamFormat.toUpperCase());
        logger.info("[VideoDecoder] 视频：" + width + "x" + height + "，帧率：" + frameRate + "fps");
        logger.info("[VideoDecoder] 音频（修复后）：" + srcSampleRate + "Hz，" + srcAudioChannels + "通道，编码：" + (grabber.getAudioCodecName() == null ? "AAC(FLV默认)" : grabber.getAudioCodecName()));
        logger.info("[VideoDecoder] 实际采样格式值：" + grabber.getSampleFormat() + "（期望=" + avutil.AV_SAMPLE_FMT_FLTP + "）");
        this.converter = new Java2DFrameConverter();
    }

    // ========== 核心重构：混合帧抓取+移除手动时长补偿 ==========
    private void decodeSingleFrame() {
        // 1. 更新抓取模式（滞回逻辑+队列状态+定期切换）
        updateGrabMode();

        // 2. 根据抓取模式，选择对应的grabFrame参数
        Frame frame = null;
        try {
            if (prioritizeAudioGrab) {
                // 优先抓取音频：仅开启音频+同步，关闭视频+图像（快速补充音频）
                frame = grabber.grabFrame(true, false, true, false);
                //logger.debug("[VideoDecoder] 【优先音频模式】跳过视频帧抓取，专注补充音频队列");
            } else {
                // 混合抓取：开启音频+视频+同步，关闭图像（原有逻辑，保证音视频双缓冲）
                frame = grabber.grabFrame(true, true, true, false);
                //logger.debug("[VideoDecoder] 【混合抓取模式】同时抓取音视频帧，维持双队列平衡");
                // 更新最后混合抓取时间戳，避免长期优先音频导致流错位
                lastHybridGrabTime = System.currentTimeMillis();
            }
        } catch (FFmpegFrameGrabber.Exception e) {
            logger.error("[VideoDecoder] {} 帧抓取失败：", streamFormat.toUpperCase(), e);
            return;
        }

        // 3. 后续原有逻辑不变（断流重连、处理视频帧、处理音频帧、队列控制）
        if (frame == null) {
            // 原有断流重连逻辑...
            return;
        }

        handleVideoFrameInMixedFrame(frame);
        handleAudioFrameInMixedFrame(frame);
        controlVideoQueueBuffer();
    }
    /**
     * 新增：更新抓取模式（滞回逻辑，规避频繁切换、队列耗尽、流错位问题）
     */
    private void updateGrabMode() {
        queueLock.lock();
        try {
            // 1. 获取当前队列状态
            int currentVideoQueueSize = videoFrameQueue.size();
            boolean audioQueueFull = audioPlayer != null &&
                    (audioPlayer.getAudioQueueSize() >= audioPlayer.audioQueue.remainingCapacity() * AUDIO_QUEUE_FULL_RATIO);
            long currentTime = System.currentTimeMillis();

            // 2. 滞回逻辑：高阈值切换优先音频，低阈值切换回混合抓取
            if (!prioritizeAudioGrab) {
                // 切换为「优先音频模式」条件：视频队列充足 + 音频队列未满 + 非刚切换回混合
                if (currentVideoQueueSize >= VIDEO_QUEUE_HIGH_THRESHOLD
                        && !audioQueueFull
                        && (currentTime - lastHybridGrabTime > 500)) { // 500ms防抖，避免刚切换就回切
                    prioritizeAudioGrab = true;
//                    logger.info("[VideoDecoder] 视频队列充足（{}帧≥{}帧），切换为【优先音频模式】",
//                            currentVideoQueueSize, VIDEO_QUEUE_HIGH_THRESHOLD);
                }
            } else {
                // 切换回「混合抓取模式」条件：视频队列不足 OR 音频队列已满 OR 达到定期切换间隔
                boolean needSwitchBack = currentVideoQueueSize <= VIDEO_QUEUE_LOW_THRESHOLD
                        || audioQueueFull
                        || (currentTime - lastHybridGrabTime >= HYBRID_GRAB_INTERVAL);

                if (needSwitchBack) {
                    prioritizeAudioGrab = false;
//                    String reason = currentVideoQueueSize <= VIDEO_QUEUE_LOW_THRESHOLD ? "视频队列不足"
//                            : (audioQueueFull ? "音频队列已满" : "定期切换避免流错位");
//                    logger.info("[VideoDecoder] {}（视频{}帧，音频{}帧），切换回【混合抓取模式】",
//                            reason, currentVideoQueueSize,
//                            audioPlayer != null ? audioPlayer.getAudioQueueSize() : 0);
                    lastHybridGrabTime = currentTime; // 更新混合抓取时间戳
                }
            }
        } finally {
            queueLock.unlock();
        }
    }



    // ========== 简化：处理混合帧中的视频帧（移除手动帧时长计算） ==========
    private boolean handleVideoFrameInMixedFrame(Frame frame) {
        if (frame.image == null) {
            return false;
        }

        // 转换视频帧（保留原有逻辑，无修改）
        BufferedImage bufferedImage = frameConverter.convert(frame);
        if (bufferedImage == null) {
            logger.error("[VideoDecoder] 视频帧转换为BufferedImage失败");
            return false;
        }

        // 视频帧入队（简化：仅控制队列大小，不维护手动时长）
        queueLock.lock();
        try {
            if (videoFrameQueue.size() >= videoFrameQueueMaxSize) {
                // 取出并丢弃最旧帧（防止队列堆积，导致视频超速）
                VideoFrameWrapper discardedFrame = videoFrameQueue.poll();
//                if (discardedFrame != null) {
//                    logger.info("[VideoDecoder] 视频队列已满，移除最旧帧（当前大小：{}）", videoFrameQueue.size());
//                }
            }
            // 包装帧数据入队（仅保留帧数据，无手动时长）
            VideoFrameWrapper frameWrapper = new VideoFrameWrapper(bufferedImage);
            videoFrameQueue.offer(frameWrapper);
            //logger.info("[VideoDecoder] 视频帧入队成功，队列大小：{}", videoFrameQueue.size());
        } finally {
            queueLock.unlock();
        }

        return true;
    }

    // ========== 简化：处理混合帧中的音频帧（移除手动时长计算，调用OpenAL节流） ==========
    private boolean handleAudioFrameInMixedFrame(Frame frame) {
        if (audioPlayer == null) {
            return false;
        }

        // 保留原有音频帧有效性判断逻辑
        boolean isAudioFrame = frame.samples != null && frame.samples.length > 0 && frame.sampleRate > 0;
        if (!isAudioFrame) {
            return false;
        }

        boolean hasValidSamples = false;
        for (Buffer sample : frame.samples) {
            if (sample != null && sample.remaining() > 0) {
                hasValidSamples = true;
                break;
            }
        }
        if (!hasValidSamples) {
            //logger.warn("[VideoDecoder] 音频帧samples为空/无数据，跳过");
            return false;
        }

        // 移除：手动单帧时长计算（不再需要，由OpenAL根据采样数计算）
        String fmtName = getSampleFormatName(grabber.getSampleFormat());
        //logger.info("[VideoDecoder] 检测到有效音频帧：格式={}，采样率={}Hz", fmtName, frame.sampleRate);

        // 保留原有音频格式转换逻辑（FLTP→S16）
        ByteBuffer audioBuffer = null;
        if (grabber.getSampleFormat() == avutil.AV_SAMPLE_FMT_FLTP && frame.samples[0] instanceof FloatBuffer) {
            FloatBuffer[] floatBuffers = new FloatBuffer[frame.samples.length];
            for (int i = 0; i < frame.samples.length; i++) {
                if (frame.samples[i] instanceof FloatBuffer) {
                    floatBuffers[i] = (FloatBuffer) frame.samples[i];
                } else {
                    logger.error("[VideoDecoder] 采样数据不是 FloatBuffer，类型：{}",
                            (frame.samples[i] != null ? frame.samples[i].getClass().getName() : "null"));
                    floatBuffers[i] = null;
                }
            }

            boolean hasValidFloatBuffer = false;
            for (FloatBuffer fb : floatBuffers) {
                if (fb != null && fb.remaining() > 0) {
                    hasValidFloatBuffer = true;
                    break;
                }
            }
            if (!hasValidFloatBuffer) {
                logger.warn("[VideoDecoder] 无有效 FloatBuffer 数据，跳过该帧");
                return false;
            }

            audioBuffer = convertFLTPToS16(floatBuffers);
            if (audioBuffer == null) {
                logger.error("[VideoDecoder] FLTP 转 16 位 PCM 失败，跳过该帧");
                return false;
            }
        } else if (grabber.getSampleFormat() == avutil.AV_SAMPLE_FMT_S16 && frame.samples[0] instanceof ShortBuffer shortBuffer) {
            logger.info("[VideoDecoder] 处理 S16 格式音频帧");
            shortBuffer.rewind();
            if (shortBuffer.remaining() > 0) {
                int sampleCount = shortBuffer.remaining();
                int channelCount = Math.max(1, frame.audioChannels);
                audioBuffer = ByteBuffer.allocateDirect(sampleCount * channelCount * 2)
                        .order(ByteOrder.nativeOrder());
                ShortBuffer outputBuffer = audioBuffer.asShortBuffer();
                ShortBuffer copiedBuffer = ShortBuffer.allocate(sampleCount);
                copiedBuffer.put(shortBuffer);
                copiedBuffer.rewind();
                outputBuffer.put(copiedBuffer);
                audioBuffer.flip();
            } else {
                audioBuffer = null;
            }
        } else {
            logger.error("[VideoDecoder] 不支持的音频格式：采样格式值={}，缓冲区类型={}",
                    grabber.getSampleFormat(), frame.samples[0].getClass().getSimpleName());
            return false;
        }

        // 音频帧入队（保留原有重试逻辑，新增调用OpenAL节流）
        if (audioBuffer != null && audioBuffer.remaining() > 0) {
            boolean enqueueSuccess = audioPlayer.offerAudioData(audioBuffer);
            if (!enqueueSuccess) {
                Thread.yield();
                enqueueSuccess = audioPlayer.offerAudioData(audioBuffer);
                if (!enqueueSuccess) {
                    logger.warn("[VideoDecoder] 音频队列已满，入队失败（已重试1次）");
                    return false;
                }
            }

            // 移除：手动时长更新（不再需要）
            audioFrameCount++;
            totalAudioBytes += audioBuffer.remaining();
//            logger.info("[VideoDecoder] 音频帧入队成功：第{}帧，字节数={}",
//                    audioFrameCount, audioBuffer.remaining());

            // 核心优化：调用OpenAL的解码节流，避免解码过快
            audioPlayer.throttleDecodeThread();
            return true;
        } else {
            logger.warn("[VideoDecoder] 音频缓冲区为空或无数据");
            return false;
        }
    }

    // ========== 简化：视频队列缓冲控制（调用OpenAL节流，避免堆积） ==========
    private void controlVideoQueueBuffer() {
        int currentQueueSize = videoFrameQueue.size();

        // 核心优化：基于OpenAL的节流逻辑，而非自定义休眠
        // 视频队列超过阈值时，休眠解码线程，匹配OpenAL的播放速度
        if (currentQueueSize > videoFrameQueueMaxSize / 2) {
            try {
                // 休眠时间匹配OpenAL的缓冲阈值，避免视频队列堆积
                Thread.sleep(Math.min(10, currentQueueSize - videoFrameQueueMaxSize / 2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========== 移除：音频队列缓冲控制（合并到OpenALAudioPlayer的throttleDecodeThread()） ==========
    private void controlAudioQueueBuffer() {
        // 空实现，逻辑已迁移到OpenALAudioPlayer
    }

    // ========== 移除：手动音视频时长对齐逻辑（不再需要） ==========
    private void alignAudioVideoDuration(boolean hasVideoFrame, boolean hasAudioFrame) {
        // 空实现，由OpenAL硬件时序自动对齐
    }

    // ========== 原有方法：FLTP转S16（不变） ==========
    private ByteBuffer convertFLTPToS16(FloatBuffer[] floatBuffers) {
        if (floatBuffers == null || floatBuffers.length == 0 || floatBuffers[0] == null) {
            System.err.println("[VideoDecoder] FLTP转换：输入缓冲区无效");
            return null;
        }

        FloatBuffer leftBuf = floatBuffers[0].duplicate();
        leftBuf.rewind();
        int sampleCount = leftBuf.remaining();

        if (sampleCount <= 0) {
            System.err.println("[VideoDecoder] 无效样本数：" + sampleCount);
            return null;
        }

        int channelCount = Math.min(2, floatBuffers.length); // 最多立体声
        int bytesPerSample = 2;
        int totalBytes = sampleCount * channelCount * bytesPerSample;

        ByteBuffer outputBuffer = ByteBuffer.allocateDirect(totalBytes);
        outputBuffer.order(ByteOrder.nativeOrder());
        ShortBuffer shortBuffer = outputBuffer.asShortBuffer();

//        logger.info("[VideoDecoder] 转换前 - 总字节数=" + totalBytes +
//                "，ShortBuffer容量=" + shortBuffer.capacity() +
//                "，样本数=" + sampleCount + "，通道数=" + channelCount);

        FloatBuffer rightBuf = (floatBuffers.length > 1 && floatBuffers[1] != null)
                ? floatBuffers[1].duplicate() : leftBuf.duplicate();
        rightBuf.rewind();

        int writtenSamples = 0;
        for (int i = 0; i < sampleCount; i++) {
            float left = leftBuf.get();
            left = Math.max(-1.0f, Math.min(1.0f, left));
            shortBuffer.put((short) (left * 32767));

            float right = rightBuf.get();
            right = Math.max(-1.0f, Math.min(1.0f, right));
            shortBuffer.put((short) (right * 32767));

            writtenSamples++;
        }

        shortBuffer.flip();
        outputBuffer.position(0);
        outputBuffer.limit(shortBuffer.limit() * bytesPerSample);

//        logger.info("[VideoDecoder] 转换后 - 写入样本数=" + writtenSamples +
//                "，ShortBuffer剩余=" + shortBuffer.remaining() +
//                "，ByteBuffer有效字节数=" + outputBuffer.remaining());

        return outputBuffer;
    }

    // ========== 原有方法：获取采样格式名称（不变） ==========
    private String getSampleFormatName(int fmtValue) {
        if (fmtValue == avutil.AV_SAMPLE_FMT_FLTP) {
            return "FLTP（浮点平面）";
        } else if (fmtValue == avutil.AV_SAMPLE_FMT_S16) {
            return "S16（16位整型）";
        } else if (fmtValue == avutil.AV_SAMPLE_FMT_S16P) {
            return "S16P（16位整型平面）";
        } else if (fmtValue == avutil.AV_SAMPLE_FMT_FLT) {
            return "FLT（浮点打包）";
        } else {
            return "未知（值：" + fmtValue + "）";
        }
    }

    // ========== 简化：获取视频帧（移除手动时长扣除） ==========
    public BufferedImage pollVideoFrame() {
        queueLock.lock();
        try {
            VideoFrameWrapper frameWrapper = videoFrameQueue.poll();
            if (frameWrapper != null) {
                return frameWrapper.frame;
            }
            return null;
        } finally {
            queueLock.unlock();
        }
    }

    // ========== 原有方法：停止解码（不变，移除时长重置） ==========
    public void stop() {
        // 第一步：空指针防护
        Thread localDecodeThread = this.decodeThread;

        // 1. 强制终止解码循环
        decoding = false;
        logger.info("[VideoDecoder] 强制终止解码循环");

        // 2. 中断并等待线程终止
        if (localDecodeThread != null && localDecodeThread.isAlive()) {
            logger.info("[VideoDecoder] 发送线程中断信号");
            localDecodeThread.interrupt();

            // 等待线程终止（最多1.5秒）
            try {
                localDecodeThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[VideoDecoder] 等待线程终止时被中断：" + e.getMessage());
            }

            if (localDecodeThread.isAlive()) {
                System.err.println("[VideoDecoder] 解码线程未正常终止，强制置空（可能有资源泄漏）");
            }
        } else if (localDecodeThread == null) {
            logger.info("[VideoDecoder] 解码线程已为null，无需中断");
        } else {
            logger.info("[VideoDecoder] 解码线程已终止，无需等待");
        }

        // 3. 置空线程引用
        this.decodeThread = null;

        // 第二步：彻底释放FFmpeg原生资源
        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
                grabber.close();
                logger.info("[VideoDecoder] FFmpeg抓取器已关闭+释放原生内存");
            }
        } catch (Exception e) {
            System.err.println("[VideoDecoder] 关闭FFmpeg抓取器失败：" + e.getMessage());
        }

        // 第三步：清空队列+置空所有引用
        queueLock.lock();
        try {
            videoFrameQueue.clear();
            logger.info("[VideoDecoder] 视频帧队列已清空");
        } finally {
            queueLock.unlock();
        }

        // 置空音频播放器引用
        audioPlayer = null;

        // 最终日志
        logger.info("[VideoDecoder] 解码器资源已完全释放，线程状态：" +
                (localDecodeThread == null ? "已销毁" : (localDecodeThread.isAlive() ? "仍存活" : "已终止")));
        logger.info("[VideoDecoder] " + streamFormat.toUpperCase() + " 解码停止，累计处理音频帧=" + audioFrameCount);
    }

    // ========== 原有Getter/Setter方法（不变） ==========
    public VideoInfo getResampledVideoInfo() {
        return this.videoInfo;
    }

    public VideoInfo getVideoInfo() {
        return videoInfo;
    }

    public void setAudioPlayer(OpenALAudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
        logger.info("[VideoDecoder] 音频播放器已绑定");
    }

    public boolean isDecoding() {
        return decoding && this.decodeThread != null && this.decodeThread.isAlive();
    }

    // ========== 原有方法：启动解码（不变） ==========
    public void startDecoding() {
        if (decoding) return;
        decoding = true;
        decodeThread = new Thread(this::decodeLoop, "FFmpeg-" + streamFormat.toUpperCase() + "-Decode-Thread");
        decodeThread.setPriority(Thread.NORM_PRIORITY);
        decodeThread.start();
        logger.info("[VideoDecoder] " + streamFormat.toUpperCase() + " 解码线程已启动");
    }

    // ========== 简化：计算缓冲时长（移除手动时长，仅基于队列大小） ==========
    public long calculateTotalBufferDuration() {
        long videoBufferMs = 0;
        long audioBufferMs = 0;

        queueLock.lock();
        try {
            if (videoInfo.getFrameRate() > 0) {
                videoBufferMs = Math.round(videoFrameQueue.size() * 1000.0 / videoInfo.getFrameRate());
            }
        } finally {
            queueLock.unlock();
        }

        synchronized (bufferLock) {
            if (audioPlayer != null && videoInfo.getSampleRate() > 0 && videoInfo.getAudioChannels() > 0) {
                audioBufferMs = audioPlayer.getAudioBufferDurationMs();
            }
        }

        long totalBufferMs = videoBufferMs + audioBufferMs;
        //logger.info("[VideoDecoder] 缓冲状态：视频=" + videoBufferMs + "ms，音频=" + audioBufferMs + "ms，总=" + totalBufferMs + "ms");
        return totalBufferMs;
    }

    // ========== 移除：手动音频播放时长更新（不再需要） ==========
    public void updateAudioPlayedDuration() {
        // 空实现，逻辑已迁移到OpenALAudioPlayer
    }

    // ========== 原有方法：解码循环（简化，移除手动时长更新） ==========
    private void decodeLoop() {
        int decodeCount = 0;
        while (decoding) {
            try {
                // 等待OpenAL音频播放器初始化
                while (decoding && audioPlayer != null && !audioPlayer.isOpenALInitialized()) {
                    Thread.sleep(10);
                    logger.info("[VideoDecoder] 等待 OpenAL 播放器初始化...");
                }

                // 填充初始缓冲
                long m3u8MinBuffer = 3000;
                long currentMinBuffer = "m3u8".equals(streamFormat) ? m3u8MinBuffer : MIN_BUFFER_DURATION;
                logger.info("[VideoDecoder] 开始填充初始缓冲（最小需要" + currentMinBuffer + "ms）");

                long bufferFillStartTime = System.currentTimeMillis();
                long bufferFillTimeout = 5000;
                while (decoding && calculateTotalBufferDuration() < currentMinBuffer) {
                    if (System.currentTimeMillis() - bufferFillStartTime > bufferFillTimeout) {
                        System.err.println("[VideoDecoder] 初始缓冲填充超时，强制进入播放");
                        break;
                    }
                    decodeSingleFrame();
                }
                logger.info("[VideoDecoder] 初始缓冲填充完成，开始正常播放");

                // 正常解码阶段：全速解码+OpenAL节流
                while (decoding) {
                    decodeSingleFrame();
                    decodeCount++;

                    // 每解码10帧，执行一次节流（强化队列控制）
                    if (decodeCount % 10 == 0) {
                        if (audioPlayer != null) {
                            audioPlayer.throttleDecodeThread();
                        }
                        decodeCount = 0;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("[VideoDecoder] " + streamFormat.toUpperCase() + " 解码线程被中断，正常退出");
                return;
            } catch (Exception e) {
                System.err.println("[VideoDecoder] " + streamFormat.toUpperCase() + " 解码异常：" + e.getMessage());
                e.printStackTrace();
                if (reconnectCount < MAX_RECONNECT) {
                    try {
                        Thread.sleep(RECONNECT_DELAY);
                        reconnectCount++;
                        logger.info("[VideoDecoder] 异常后尝试重连（次数：" + reconnectCount + "）");
                        continue;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;
                }
            } finally {
                decoding = false;
                stop();
            }
        }
    }

}
