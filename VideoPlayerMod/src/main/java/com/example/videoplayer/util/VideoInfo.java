package com.example.videoplayer.util;

public class VideoInfo {
    private final int width;
    private final int height;
    private final int frameRate;
    private final long duration; // 毫秒
    private final int sampleRate;
    private final int audioChannels;
    private final String title;

    public VideoInfo(int width, int height, int frameRate, long duration, int sampleRate, int audioChannels, String title) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.duration = duration;
        this.sampleRate = sampleRate;
        this.audioChannels = audioChannels;
        this.title = title;
    }

    // Getter
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getFrameRate() { return frameRate; }
    public long getDuration() { return duration; }
    public int getSampleRate() { return sampleRate; }
    public int getAudioChannels() { return audioChannels; }
    public String getTitle() { return title; }

    @Override
    public String toString() {
        return "VideoInfo{" +
                "width=" + width +
                ", height=" + height +
                ", frameRate=" + frameRate +
                ", duration=" + duration +
                ", sampleRate=" + sampleRate +
                ", audioChannels=" + audioChannels +
                '}';
    }
}
