package com.cuemymusic.config;

public class CueMyMusicConfig {

    private boolean enableAmbientReplacement = true;
    private int nextTrackDelaySeconds = 300;

    public CueMyMusicConfig() {
    }

    public boolean isEnableAmbientReplacement() {
        return enableAmbientReplacement;
    }

    public void setEnableAmbientReplacement(boolean enableAmbientReplacement) {
        this.enableAmbientReplacement = enableAmbientReplacement;
    }

    public int getNextTrackDelaySeconds() {
        return nextTrackDelaySeconds;
    }

    public void setNextTrackDelaySeconds(int nextTrackDelaySeconds) {
        this.nextTrackDelaySeconds = nextTrackDelaySeconds;
    }
}
