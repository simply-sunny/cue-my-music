package com.cuemymusic;

import com.cuemymusic.config.CueMyMusicConfig;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.persistence.PersistenceManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CueMyMusic implements ModInitializer {
    public static final String MOD_ID = "cue_my_music";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static CueMyMusic instance;
    private PersistenceManager persistenceManager;
    private MusicLibrary library;
    private CueMyMusicConfig config;

    @Override public void onInitialize() {
        instance=this; persistenceManager=new PersistenceManager(); config=persistenceManager.loadConfig();
        library=new MusicLibrary(); var p=persistenceManager.loadLibraryIndex(); if(p!=null) library.applyPersistedState(p);
        LOGGER.info("[Cue My Music] init {} tracks", library.getAllTracks().size());
    }
    public static CueMyMusic getInstance(){return instance;}
    public PersistenceManager getPersistenceManager(){return persistenceManager;}
    public MusicLibrary getLibrary(){return library;}
    public CueMyMusicConfig getConfig(){return config;}
    public void saveAll(){ try{ persistenceManager.saveConfig(config); persistenceManager.saveLibraryIndex(library.toPersistedState()); }catch(Exception e){LOGGER.error("save fail",e);} }
}
