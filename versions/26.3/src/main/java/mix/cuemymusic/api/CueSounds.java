package mix.cuemymusic.api;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Util;

import mix.cuemymusic.library.MusicLibrary;

/**
 * For add-ons that write songs into a resource pack: picks up what changed
 * without reloading every resource. Only the sound list is read again, and
 * the sound engine keeps running, so the song that's on carries on.
 */
public final class CueSounds {
    /** True while a refresh's sound list is going in, so the engine isn't restarted. Read by a mixin. */
    public static volatile boolean keepEngine;

    private CueSounds() {
    }

    /**
     * Reads the sound list again and refreshes the player's library. Call on
     * the client thread. Returns false, doing nothing, if the resources loaded
     * now don't have {@code namespace} yet (a pack's first songs): then only a
     * full resource reload finds them.
     */
    public static boolean refresh(String namespace) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.getResourceManager().getNamespaces().contains(namespace)) return false;
        PreparableReloadListener.PreparationBarrier now = new PreparableReloadListener.PreparationBarrier() {
            @Override
            public <T> CompletableFuture<T> wait(T value) {
                return CompletableFuture.completedFuture(value);
            }
        };
        mc.getSoundManager()
                .reload(new PreparableReloadListener.SharedState(mc.getResourceManager()), Util.backgroundExecutor(), now, task -> mc.execute(() -> {
                    keepEngine = true;
                    try {
                        task.run();
                    } finally {
                        keepEngine = false;
                    }
                }))
                .whenComplete((done, error) -> mc.execute(() -> {
                    if (error == null) {
                        MusicLibrary.refresh();
                        return;
                    }
                    // Without this the library would keep showing songs that are gone, with nothing said.
                    org.slf4j.LoggerFactory.getLogger("cuemymusic").warn("Couldn't read {}'s sounds again; reloading resources", namespace, error);
                    mc.reloadResourcePacks();
                }));
        return true;
    }
}
