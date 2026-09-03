package com.cuemymusic.persistence;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.config.CueMyMusicConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.file.*;
public class PersistenceManager {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private Path resolveGameDir(){ try{ var inst=FabricLoader.getInstance(); if(inst!=null) return inst.getGameDir(); }catch(Exception ignored){} return Path.of(System.getProperty("user.dir",".")); }
    public Path getDataDir(){ Path d=resolveGameDir().resolve("cue-my-music"); try{Files.createDirectories(d);}catch(IOException e){CueMyMusic.LOGGER.error("mkdir {}",d,e);} return d; }
    public Path getConfigDir(){return getDataDir();}
    public File getConfigFile(){return getDataDir().resolve("config.json").toFile();}
    public File getLibraryFile(){return getDataDir().resolve("library.json").toFile();}
    public CueMyMusicConfig loadConfig(){ File f=getConfigFile(); if(!f.exists()) return new CueMyMusicConfig(); try(Reader r=Files.newBufferedReader(f.toPath())){ var c=GSON.fromJson(r,CueMyMusicConfig.class); return c!=null?c:new CueMyMusicConfig(); }catch(Exception e){return new CueMyMusicConfig();} }
    public void saveConfig(CueMyMusicConfig c){ try(Writer w=Files.newBufferedWriter(getConfigFile().toPath())){ GSON.toJson(c,w); }catch(Exception e){CueMyMusic.LOGGER.error("save config",e);} }
    public PersistedLibraryState loadLibraryIndex(){ File f=getLibraryFile(); if(!f.exists()) return null; try(Reader r=Files.newBufferedReader(f.toPath())){ return GSON.fromJson(r,PersistedLibraryState.class); }catch(Exception e){CueMyMusic.LOGGER.error("load lib",e); return null;} }
    public void saveLibraryIndex(PersistedLibraryState s){ try(Writer w=Files.newBufferedWriter(getLibraryFile().toPath())){ GSON.toJson(s,w); }catch(Exception e){CueMyMusic.LOGGER.error("save lib",e);} }
}
