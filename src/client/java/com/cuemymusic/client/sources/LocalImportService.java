package com.cuemymusic.client.sources;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import com.cuemymusic.persistence.PersistenceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for importing local audio files into the library and audio cache.
 *
 * <p>Validates that the source exists and is OGG (or converts via ffmpeg if present),
 * copies the result into {@link PersistenceManager#getAudioCacheDir()}, creates a
 * {@link MusicTrack} with id {@code local:<filename_without_ext>_<timestamp>},
 * persists via {@link CueMyMusic#saveAll()}, and returns a {@link Result}.</p>
 *
 * <p>Callers should run {@link #importLocalFile} off-thread if invoked from the
 * client/render thread — ffmpeg conversion may block briefly.</p>
 */
public class LocalImportService {

    private final PersistenceManager persistenceManager;
    private final MusicLibrary library;

    /**
     * Creates a service using the global mod instance when available,
     * otherwise falls back to fresh instances (useful in unit tests).
     */
    public LocalImportService() {
        CueMyMusic inst = CueMyMusic.getInstance();
        if (inst != null) {
            this.persistenceManager = inst.getPersistenceManager();
            this.library = inst.getLibrary();
        } else {
            this.persistenceManager = new PersistenceManager();
            this.library = new MusicLibrary();
        }
    }

    /**
     * Test / manual wiring constructor.
     */
    public LocalImportService(PersistenceManager persistenceManager, MusicLibrary library) {
        this.persistenceManager = persistenceManager != null ? persistenceManager : new PersistenceManager();
        this.library = library != null ? library : new MusicLibrary();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Convenience wrapper that parses a raw path string.
     *
     * @param pathString file path as typed/pasted by the user
     * @param type       source type to assign (e.g. LOCAL_GENERIC)
     */
    public Result importFromPathString(String pathString, SourceType type) {
        if (pathString == null || pathString.isBlank()) {
            return Result.failure("Path is empty");
        }
        // Strip surrounding quotes user may have pasted
        String trimmed = pathString.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        Path p = Path.of(trimmed);
        return importLocalFile(p, type, null);
    }

    /**
     * Imports a local file into the audio cache and library.
     *
     * <ul>
     *   <li>Validates file exists</li>
     *   <li>If .ogg: copies directly to {@code <dataDir>/audio}</li>
     *   <li>If not .ogg: checks for ffmpeg via {@link ExternalToolService#isAvailable(String)};
     *       if present converts with {@code ffmpeg -i input -c:a libvorbis -q:a 4 output.ogg},
     *       otherwise fails with "Only OGG supported, ffmpeg not found"</li>
     *   <li>Creates {@link MusicTrack} with id {@code local:<filename_without_ext>_<timestamp>},
     *       title = override or filename, artist "Local", enabled true, localAudioPath set</li>
     *   <li>Adds to library and calls {@link CueMyMusic#saveAll()} when instance available</li>
     * </ul>
     *
     * @param sourceFile    absolute or relative path to source file
     * @param sourceType    source type for the new track (LOCAL_GENERIC / YOUTUBE / SPOTIFY etc.)
     * @param titleOverride optional title override; when blank filename is used
     */
    public Result importLocalFile(Path sourceFile, SourceType sourceType, String titleOverride) {
        if (sourceFile == null) {
            return Result.failure("Source file is null");
        }
        if (sourceType == null) {
            sourceType = SourceType.LOCAL_GENERIC;
        }
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            return Result.failure("File does not exist: " + sourceFile);
        }

        String fileName = sourceFile.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean isOgg = lower.endsWith(".ogg");

        // Derive base name (filename without extension)
        String baseName = fileName;
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            baseName = fileName.substring(0, dot);
        }
        if (baseName.isBlank()) baseName = "track";
        // Sanitize base for id/file: keep alnum, dash, underscore; replace others with underscore
        String sanitizedBase = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        long timestamp = System.currentTimeMillis();

        Path audioCacheDir = persistenceManager.getAudioCacheDir();
        try {
            Files.createDirectories(audioCacheDir);
        } catch (IOException e) {
            return Result.failure("Failed to create audio cache dir: " + e.getMessage());
        }

        Path cachedPath;
        if (isOgg) {
            // Use unique filename to avoid collisions: <sanitized>_<timestamp>.ogg
            String cachedFileName = sanitizedBase + "_" + timestamp + ".ogg";
            cachedPath = audioCacheDir.resolve(cachedFileName);
            try {
                Files.copy(sourceFile, cachedPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                return Result.failure("Failed to copy to audio cache: " + e.getMessage());
            }
        } else {
            // Need ffmpeg for conversion
            if (!ExternalToolService.isAvailable("ffmpeg")) {
                return Result.failure("Only OGG supported, ffmpeg not found. Install ffmpeg to import " + fileName + " or convert to OGG first.");
            }
            String cachedFileName = sanitizedBase + "_" + timestamp + ".ogg";
            cachedPath = audioCacheDir.resolve(cachedFileName);
            Result conversion = convertWithFfmpeg(sourceFile, cachedPath);
            if (!conversion.success) {
                return conversion;
            }
            // conversion already placed file at cachedPath
        }

        // Build track
        String id = "local:" + sanitizedBase + "_" + timestamp;
        String title = (titleOverride != null && !titleOverride.isBlank()) ? titleOverride.trim() : baseName;
        MusicTrack track = new MusicTrack(id, title, "Local", sourceType);
        track.setLocalAudioPath(cachedPath.toString());
        track.setEnabled(true);
        track.setAmbientEligible(true);

        // Add to library
        try {
            library.addOrReplaceTrack(track);
        } catch (Exception e) {
            return Result.failure("Failed to add track to library: " + e.getMessage());
        }

        // Persist if mod instance available
        CueMyMusic inst = CueMyMusic.getInstance();
        if (inst != null) {
            try {
                inst.saveAll();
            } catch (Exception e) {
                CueMyMusic.LOGGER.warn("[Cue My Music] Failed to save after local import", e);
                // Still return success — track is in memory; persistence retry can happen later
            }
        } else {
            // No global instance (unit test) — best-effort persist via local manager
            try {
                var state = library.toPersistedState();
                persistenceManager.saveLibraryIndex(state);
            } catch (Exception e) {
                // ignore in test fallback
            }
        }

        return Result.success(track, "Imported " + fileName + " as " + id);
    }

    // -------------------------------------------------------------------------
    // ffmpeg conversion
    // -------------------------------------------------------------------------

    private Result convertWithFfmpeg(Path input, Path output) {
        // ffmpeg -y -i input -c:a libvorbis -q:a 4 output.ogg
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", input.toAbsolutePath().toString(),
                "-c:a", "libvorbis",
                "-q:a", "4",
                output.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        String log;
        int exit;
        try {
            Process p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                log = reader.lines().collect(Collectors.joining("\n"));
            }
            boolean done = p.waitFor(2, TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                return Result.failure("ffmpeg timed out converting " + input.getFileName());
            }
            exit = p.exitValue();
        } catch (IOException e) {
            return Result.failure("Failed to run ffmpeg: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure("ffmpeg interrupted");
        }

        if (exit != 0) {
            String snippet = log != null && log.length() > 800 ? log.substring(0, 800) + "..." : String.valueOf(log);
            CueMyMusic.LOGGER.warn("[Cue My Music] ffmpeg failed (exit {}): {}", exit, snippet);
            return Result.failure("ffmpeg conversion failed (exit " + exit + "): " + snippet);
        }
        if (!Files.exists(output)) {
            return Result.failure("ffmpeg reported success but output missing: " + output);
        }
        return Result.success(null, "Converted via ffmpeg");
    }

    // -------------------------------------------------------------------------
    // Result
    // -------------------------------------------------------------------------

    /**
     * Outcome of an import operation.
     */
    public static final class Result {
        public final boolean success;
        public final String message;
        public final MusicTrack track; // nullable on failure or conversion step

        private Result(boolean success, String message, MusicTrack track) {
            this.success = success;
            this.message = message;
            this.track = track;
        }

        public static Result success(MusicTrack track, String message) {
            return new Result(true, message, track);
        }

        public static Result failure(String message) {
            return new Result(false, message, null);
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public MusicTrack getTrack() { return track; }

        @Override
        public String toString() {
            return "Result{success=" + success + ", message='" + message + "', track=" + track + "}";
        }
    }
}
