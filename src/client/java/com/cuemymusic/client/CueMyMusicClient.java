package com.cuemymusic.client;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.client.music.VanillaTrackRegistry;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;

public class CueMyMusicClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cue_my_music/client");
    @Override public void onInitializeClient(){
        LOGGER.info("[Cue My Music] init client");
        try{ var inst=CueMyMusic.getInstance(); if(inst!=null&&inst.getLibrary()!=null){ VanillaTrackRegistry.registerAll(inst.getLibrary()); inst.getLibrary().applyPersistedState(inst.getPersistenceManager().loadLibraryIndex()); LOGGER.info("registered {}",inst.getLibrary().getAllTracks().size()); }}catch(Exception e){LOGGER.warn("register fail",e);}
        // tick for ambient auto-next
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc->{
            try{ com.cuemymusic.client.playback.MusicDirector.getInstance().tick(mc);}catch(Exception ignored){}
        });

        if (Boolean.getBoolean("cuemymusic.autotest")) {
            try {
                Class.forName("com.cuemymusic.client.testing.AutomatedClientAudioDriver")
                        .getMethod("register").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Client audio test driver unavailable", e);
            }
        }
    }
}
