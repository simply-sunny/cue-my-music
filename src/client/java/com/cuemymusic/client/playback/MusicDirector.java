package com.cuemymusic.client.playback;

import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public final class MusicDirector {
    private static final MusicDirector INSTANCE = new MusicDirector();

    private final Random random = new Random();
    private MusicLibrary library;
    private MusicTrack currentTrack;
    private SoundInstance currentSound;

    private MusicDirector() {}

    public static MusicDirector getInstance() { return INSTANCE; }

    public void init(MusicLibrary library) {
        this.library = library;
        this.currentTrack = null;
        this.currentSound = null;
    }

    public Optional<MusicTrack> chooseNextTrack() {
        List<MusicTrack> candidates = getEligibleCandidates();
        if (candidates.isEmpty()) return Optional.empty();
        return Optional.of(candidates.get(random.nextInt(candidates.size())));
    }

    public List<MusicTrack> getEligibleCandidates() {
        if (library == null) return List.of();
        List<MusicTrack> active = eligible(library.getTracksForPreset(library.getActivePresetId()));
        if (!active.isEmpty()) return List.copyOf(active);
        return List.copyOf(eligible(library.getAllTracks()));
    }

    private List<MusicTrack> eligible(List<MusicTrack> tracks) {
        List<MusicTrack> result = new ArrayList<>();
        for (MusicTrack t : tracks) {
            if (!t.isEnabled() || !t.isAmbientEligible()) continue;
            if (t.requiresLocalFile() && (!t.hasLocalPath() || !Files.isRegularFile(Path.of(t.getLocalAudioPath())))) continue;
            result.add(t);
        }
        return result;
    }

    public boolean playNext(Minecraft client) {
        Optional<MusicTrack> selected = chooseNextTrack();
        if (selected.isEmpty()) return false;
        MusicTrack track = selected.get();
        SoundInstance sound;
        if (track.requiresLocalFile()) {
            sound = LocalOggSoundInstance.create(Path.of(track.getLocalAudioPath()), SoundSource.MUSIC, 1.0f);
        } else {
            if (track.getSourceId() == null) return false;
            Identifier id = Identifier.tryParse(track.getSourceId());
            if (id == null) return false;
            // Try to resolve sound event from built-in registry
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(id);
            if (event == null) {
                // fallback: variable range event
                event = SoundEvent.createVariableRangeEvent(id);
            }
            sound = SimpleSoundInstance.forMusic(event);
        }
        stopCurrent(client);
        client.getSoundManager().play(sound);
        currentTrack = track;
        currentSound = sound;
        return true;
    }

    public void stopCurrent(Minecraft client) {
        if (currentSound != null) {
            try { client.getSoundManager().stop(currentSound); } catch (Exception ignored) {}
        }
        currentSound = null;
        currentTrack = null;
    }

    public Optional<MusicTrack> skip(Minecraft client) {
        stopCurrent(client);
        if (playNext(client)) return getCurrentTrack();
        return Optional.empty();
    }

    public Optional<MusicTrack> getCurrentTrack() { return Optional.ofNullable(currentTrack); }
}
