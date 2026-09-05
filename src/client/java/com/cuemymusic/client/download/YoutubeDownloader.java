package com.cuemymusic.client.download;

import com.cuemymusic.client.music.YoutubeTrackRegistry;
import com.cuemymusic.data.YoutubeCatalogEntry;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class YoutubeDownloader {
    public enum DownloadStatus {
        MISSING,
        DOWNLOADING,
        READY,
        FAILED
    }

    public interface ProcessRunner {
        int run(List<String> command) throws Exception;
    }

    private static final ProcessRunner DEFAULT_RUNNER = command -> {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        return p.waitFor();
    };

    private final YoutubePackManager packManager;
    private final ProcessRunner processRunner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CueMyMusic-Downloader");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, DownloadStatus> inMemoryStatus = new ConcurrentHashMap<>();
    private Boolean ytDlpAvailable = null;

    public YoutubeDownloader(YoutubePackManager packManager) {
        this(packManager, DEFAULT_RUNNER);
    }

    public YoutubeDownloader(YoutubePackManager packManager, ProcessRunner processRunner) {
        this.packManager = packManager;
        this.processRunner = processRunner;
    }

    public synchronized boolean isYtDlpAvailable() {
        if (ytDlpAvailable != null) return ytDlpAvailable;
        try {
            int exit = processRunner.run(List.of("yt-dlp", "--version"));
            ytDlpAvailable = (exit == 0);
        } catch (Exception e) {
            ytDlpAvailable = false;
        }
        return ytDlpAvailable;
    }

    public DownloadStatus getStatus(String slug) {
        if (packManager.isTrackReady(slug)) {
            inMemoryStatus.remove(slug);
            return DownloadStatus.READY;
        }
        return inMemoryStatus.getOrDefault(slug, DownloadStatus.MISSING);
    }

    public boolean isDownloading() {
        return inMemoryStatus.values().stream().anyMatch(s -> s == DownloadStatus.DOWNLOADING);
    }

    public int getMissingCount() {
        int count = 0;
        for (var entry : YoutubeTrackRegistry.getCatalog()) {
            if (getStatus(entry.slug()) != DownloadStatus.READY) {
                count++;
            }
        }
        return count;
    }

    public List<String> buildCommand(String slug, String query) {
        File dest = packManager.getSoundFile(slug).toFile();
        return List.of(
                "yt-dlp",
                "-x",
                "--audio-format", "ogg",
                "-o", dest.getAbsolutePath(),
                "ytsearch1:" + query
        );
    }

    public void downloadTrack(String slug, Runnable onComplete) {
        var opt = YoutubeTrackRegistry.getBySlug(slug);
        if (opt.isEmpty()) return;
        var entry = opt.get();

        if (packManager.isTrackReady(slug)) {
            if (onComplete != null) onComplete.run();
            return;
        }

        inMemoryStatus.put(slug, DownloadStatus.DOWNLOADING);
        executor.submit(() -> {
            try {
                int exit = processRunner.run(buildCommand(slug, entry.query()));
                if (exit == 0 && packManager.isTrackReady(slug)) {
                    inMemoryStatus.put(slug, DownloadStatus.READY);
                } else {
                    inMemoryStatus.put(slug, DownloadStatus.FAILED);
                }
            } catch (Exception e) {
                inMemoryStatus.put(slug, DownloadStatus.FAILED);
            } finally {
                if (onComplete != null) onComplete.run();
            }
        });
    }

    public void downloadAll(Runnable onTrackComplete, Runnable onAllComplete) {
        executor.submit(() -> {
            for (var entry : YoutubeTrackRegistry.getCatalog()) {
                if (!packManager.isTrackReady(entry.slug())) {
                    inMemoryStatus.put(entry.slug(), DownloadStatus.DOWNLOADING);
                    try {
                        int exit = processRunner.run(buildCommand(entry.slug(), entry.query()));
                        if (exit == 0 && packManager.isTrackReady(entry.slug())) {
                            inMemoryStatus.put(entry.slug(), DownloadStatus.READY);
                        } else {
                            inMemoryStatus.put(entry.slug(), DownloadStatus.FAILED);
                        }
                    } catch (Exception e) {
                        inMemoryStatus.put(entry.slug(), DownloadStatus.FAILED);
                    }
                    if (onTrackComplete != null) onTrackComplete.run();
                }
            }
            if (onAllComplete != null) onAllComplete.run();
        });
    }
}
