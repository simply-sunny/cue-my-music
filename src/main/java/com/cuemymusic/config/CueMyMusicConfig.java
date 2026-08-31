package com.cuemymusic.config;

/**
 * Simple POJO for mod configuration.
 * Persisted as JSON via {@link com.cuemymusic.persistence.PersistenceManager}.
 */
public class CueMyMusicConfig {

    private String activePresetId = "my_mix";
    private float musicVolume = 1.0f;
    private boolean enableAmbientReplacement = true;
    private int nextTrackDelaySeconds = 300;
    private boolean enableDebugCommands = true;

    /** Default constructor with documented defaults. */
    public CueMyMusicConfig() {
    }

    public String getActivePresetId() {
        return activePresetId;
    }

    public void setActivePresetId(String activePresetId) {
        this.activePresetId = activePresetId;
    }

    public float getMusicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(float musicVolume) {
        this.musicVolume = musicVolume;
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

    public boolean isEnableDebugCommands() {
        return enableDebugCommands;
    }

    public void setEnableDebugCommands(boolean enableDebugCommands) {
        this.enableDebugCommands = enableDebugCommands;
    }
}
