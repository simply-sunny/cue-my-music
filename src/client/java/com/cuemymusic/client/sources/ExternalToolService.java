package com.cuemymusic.client.sources;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Isolated ProcessBuilder adapter for optional yt-dlp, spotDL, and ffmpeg tools. */
public final class ExternalToolService {
    private static final long PROBE_TIMEOUT_SECONDS = 3;
    private static final long JOB_TIMEOUT_MINUTES = 10;

    private ExternalToolService() {}

    public enum ToolStatus { AVAILABLE, MISSING, FAILED }

    public static Optional<Path> findTool(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Path direct;
        try { direct = Path.of(name); } catch (RuntimeException exception) { return Optional.empty(); }
        if (direct.getNameCount() > 1 && Files.isRegularFile(direct) && Files.isExecutable(direct)) {
            return Optional.of(direct.toAbsolutePath());
        }
        String path = System.getenv("PATH");
        if (path == null) return Optional.empty();
        String[] suffixes = isWindows() ? new String[]{"", ".exe", ".cmd", ".bat"} : new String[]{""};
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) continue;
            for (String suffix : suffixes) {
                Path candidate = Path.of(directory).resolve(name + suffix);
                if (Files.isRegularFile(candidate) && (isWindows() || Files.isExecutable(candidate))) {
                    return Optional.of(candidate.toAbsolutePath());
                }
            }
        }
        return Optional.empty();
    }

    public static ToolStatus status(String name) {
        Optional<Path> tool = findTool(name);
        if (tool.isEmpty()) return ToolStatus.MISSING;
        try {
            Process process = new ProcessBuilder(tool.get().toString(), "--version")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return ToolStatus.FAILED;
            }
            return process.exitValue() == 0 ? ToolStatus.AVAILABLE : ToolStatus.FAILED;
        } catch (IOException exception) {
            return ToolStatus.FAILED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolStatus.FAILED;
        }
    }

    public static boolean isAvailable(String name) {
        return status(name) == ToolStatus.AVAILABLE;
    }

    public static DownloadResult downloadViaYtDlp(String url, Path outputDir, String format) {
        if (url == null || url.isBlank()) return failure("URL is empty");
        Optional<Path> tool = findTool("yt-dlp");
        if (tool.isEmpty() || status("yt-dlp") != ToolStatus.AVAILABLE) return failure("yt-dlp is unavailable");
        String audioFormat = format == null || format.isBlank() ? "vorbis" : format;
        return run(tool.get(), outputDir, "yt-dlp", "-x", "--audio-format", audioFormat,
                "-o", outputDir.toAbsolutePath() + File.separator + "%(title)s.%(ext)s", url);
    }

    public static DownloadResult downloadViaSpotDL(String url, Path outputDir) {
        if (url == null || url.isBlank()) return failure("Spotify URL is empty");
        Optional<Path> tool = findTool("spotdl");
        if (tool.isEmpty() || status("spotdl") != ToolStatus.AVAILABLE) return failure("spotDL is unavailable");
        return run(tool.get(), outputDir, "spotDL", "download", url, "--output",
                outputDir.toAbsolutePath().toString());
    }

    public static DownloadResult convertToOgg(Path input, Path output) {
        Optional<Path> tool = findTool("ffmpeg");
        if (tool.isEmpty() || status("ffmpeg") != ToolStatus.AVAILABLE) return failure("ffmpeg is unavailable");
        DownloadResult result = run(tool.get(), output.getParent(), "ffmpeg", "-nostdin", "-y", "-i",
                input.toAbsolutePath().toString(), "-vn", "-c:a", "libvorbis", output.toAbsolutePath().toString());
        if (result.success && Files.isRegularFile(output)) return new DownloadResult(true, "Converted to OGG", output);
        return result.success ? failure("ffmpeg completed without producing an OGG file") : result;
    }

    private static DownloadResult run(Path executable, Path outputDir, String label, String... arguments) {
        if (outputDir == null) return failure("Output directory is required");
        Set<Path> before = listFiles(outputDir);
        Path log = null;
        try {
            Files.createDirectories(outputDir);
            log = Files.createTempFile("cue-my-music-", ".log");
            String[] command = new String[arguments.length + 1];
            command[0] = executable.toString();
            System.arraycopy(arguments, 0, command, 1, arguments.length);
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            if (!process.waitFor(JOB_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return failure(label + " timed out");
            }
            String details = truncate(Files.readString(log).trim(), 800);
            if (process.exitValue() != 0) return failure(label + " failed (exit " + process.exitValue() + "): " + details);
            Path output = newestNewAudioFile(outputDir, before);
            return new DownloadResult(true, label + " completed" + (details.isBlank() ? "" : ": " + details), output);
        } catch (IOException exception) {
            return failure(label + " failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure(label + " was interrupted");
        } finally {
            if (log != null) try { Files.deleteIfExists(log); } catch (IOException ignored) {}
        }
    }

    private static Set<Path> listFiles(Path directory) {
        if (!Files.isDirectory(directory)) return Set.of();
        try (Stream<Path> files = Files.list(directory)) { return new HashSet<>(files.toList()); }
        catch (IOException exception) { return Set.of(); }
    }

    private static Path newestNewAudioFile(Path directory, Set<Path> before) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> !before.contains(path)).filter(ExternalToolService::isAudio)
                    .max((a, b) -> modified(a).compareTo(modified(b))).orElse(null);
        } catch (IOException exception) { return null; }
    }

    private static java.nio.file.attribute.FileTime modified(Path path) {
        try { return Files.getLastModifiedTime(path); }
        catch (IOException exception) { return java.nio.file.attribute.FileTime.fromMillis(0); }
    }

    private static boolean isAudio(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ogg") || name.endsWith(".opus") || name.endsWith(".mp3") || name.endsWith(".m4a");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "...";
    }

    private static DownloadResult failure(String message) { return new DownloadResult(false, message, null); }

    public static final class DownloadResult {
        public final boolean success;
        public final String message;
        public final Path outputFile;

        public DownloadResult(boolean success, String message, Path outputFile) {
            this.success = success;
            this.message = message;
            this.outputFile = outputFile;
        }
    }
}
