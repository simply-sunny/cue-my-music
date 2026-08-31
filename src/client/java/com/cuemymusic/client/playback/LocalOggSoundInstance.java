package com.cuemymusic.client.playback;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.nio.file.Path;

/**
 * Stub for local OGG playback. For MVP we wrap a vanilla sound event
 * and store the file path. Real streaming via OggAudioStream can be added
 * later without changing the public API.
 */
public class LocalOggSoundInstance extends AbstractSoundInstance {
    private final Path filePath;

    @SuppressWarnings("unchecked")
    private LocalOggSoundInstance(Path filePath, SoundSource source, float volume) {
        super(
                // Dummy event: use a generic music sound; resolved dynamically in real impl
                SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("minecraft", "music.game")),
                source,
                SoundInstance.createUnseededRandom()
        );
        this.filePath = filePath;
        this.volume = volume;
        this.pitch = 1.0f;
        this.looping = false;
    }

    public static LocalOggSoundInstance create(Path filePath, SoundSource source, float volume) {
        return new LocalOggSoundInstance(filePath, source, volume);
    }

    public Path getFilePath() {
        return filePath;
    }
}
