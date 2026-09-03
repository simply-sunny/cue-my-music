package com.cuemymusic.client.playback;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.data.MusicTrack;
import net.minecraft.client.Minecraft;
import java.util.*;

// ponytail: minimal director — eligible = enabled && ambientEligible, no local files, ambient timer
public final class MusicDirector {
    private static final MusicDirector INSTANCE = new MusicDirector();
    private final Random random = new Random();
    private com.cuemymusic.data.MusicLibrary library;
    private final NativeMinecraftPlayback nativePlayback = NativeMinecraftPlayback.getInstance();
    // ambient scheduling
    private long nextDelayMs = 0; // 0 = not scheduled
    private long trackEndMs = 0;
    private boolean skipRequested = false;
    private MusicDirector(){}
    public static MusicDirector getInstance(){return INSTANCE;}
    public void init(com.cuemymusic.data.MusicLibrary lib){ this.library=lib; }
    public Optional<MusicTrack> chooseNextTrack(){
        var c=getEligibleCandidates(); if(c.isEmpty()) return Optional.empty(); return Optional.of(c.get(random.nextInt(c.size())));
    }
    public List<MusicTrack> getEligibleCandidates(){
        if(library==null) return List.of();
        List<MusicTrack> eligible=new ArrayList<>();
        for(var t: library.getAllTracks()) if(t.isEnabled() && t.isAmbientEligible()) eligible.add(t);
        return List.copyOf(eligible);
    }
    public boolean playNext(Minecraft mc){ var n=chooseNextTrack(); return n.isPresent() && playTrack(mc,n.get()); }
    public synchronized boolean playTrack(Minecraft mc, MusicTrack track){
        if(track==null) return false;
        boolean ok=nativePlayback.play(mc,track);
        if(ok){ scheduleNextDelay(); skipRequested=false; }
        return ok;
    }
    public synchronized void stopCurrent(Minecraft mc){ nativePlayback.stop(mc); nextDelayMs=0; trackEndMs=0; skipRequested=false; }
    public boolean isPlaying(Minecraft mc){ return nativePlayback.isPlaying(mc); }
    public boolean isPaused(){ return nativePlayback.isPaused(); }
    public PlaybackState getState(Minecraft mc){ return nativePlayback.getState(); }
    public PlaybackState getState(){ return nativePlayback.getState(); }
    public long getElapsedMs(Minecraft mc){ return nativePlayback.getElapsedMs(mc); }
    public boolean togglePause(Minecraft mc){ return nativePlayback.togglePause(mc); }
    public Optional<MusicTrack> skip(Minecraft mc){ stopCurrent(mc); if(playNext(mc)) return getCurrentTrack(); return Optional.empty(); }
    public Optional<MusicTrack> getCurrentTrack(){ return nativePlayback.getCurrentTrack(); }
    public float getPositionSecondsReal(Minecraft mc){ float p=nativePlayback.getPositionSecondsReal(mc); if(p>=0) return p; return getElapsedMs(mc)/1000f; }
    public boolean seek(Minecraft mc,float sec){ return nativePlayback.seek(mc,sec); }
    public int getDurationSeconds(){ var cur=getCurrentTrack().orElse(null); if(cur!=null&&cur.getDurationSeconds()!=null) return cur.getDurationSeconds(); return nativePlayback.getDurationSeconds(); }

    // ambient timer — called from client tick or widget
    private void scheduleNextDelay(){
        try{
            var cfg=CueMyMusic.getInstance()!=null?CueMyMusic.getInstance().getConfig():null;
            int base = cfg!=null?cfg.getNextTrackDelaySeconds():300;
            // frequency: constant=0, frequent=30, default=base
            // read Minecraft option if available; fallback to config
            try{
                var freq=Minecraft.getInstance().options.musicFrequency().get();
                String name=freq.name();
                if("CONSTANT".equals(name)) base=0;
                else if("FREQUENT".equals(name)) base=Math.min(base, 60);
            }catch(Exception ignored){}
            if(skipRequested) base=0;
            nextDelayMs=base*1000L;
            int dur=getDurationSeconds(); if(dur>0) trackEndMs=System.currentTimeMillis()+dur*1000L; else trackEndMs=System.currentTimeMillis();
        }catch(Exception e){ nextDelayMs=300_000; trackEndMs=System.currentTimeMillis(); }
    }
    public long getTimeUntilNextMs(Minecraft mc){
        var cur=getCurrentTrack().orElse(null);
        if(cur!=null && isPlaying(mc)){
            float pos=getPositionSecondsReal(mc); int dur=getDurationSeconds();
            long remaining = dur>0 ? Math.max(0, (long)((dur - pos)*1000)) : 0;
            return remaining + nextDelayMs;
        }
        // not playing — countdown to next
        if(trackEndMs==0) return nextDelayMs;
        long sinceEnd=System.currentTimeMillis()-trackEndMs;
        return Math.max(0, nextDelayMs - sinceEnd);
    }
    public void requestSkipToNext(){ skipRequested=true; nextDelayMs=0; }
    public boolean isSkipRequested(){return skipRequested;}
    public void clearSkip(){ skipRequested=false; scheduleNextDelay(); }
    // tick hook for auto-play next when delay elapsed
    public void tick(Minecraft mc){
        if(library==null) return;
        var cur=getCurrentTrack().orElse(null);
        if(cur!=null && isPlaying(mc)) return;
        if(cur!=null && isPaused()) return;
        // stopped — check delay
        if(trackEndMs==0 && cur==null){
            // first run, schedule
            if(nextDelayMs==0) scheduleNextDelay();
        }
        long until=getTimeUntilNextMs(mc);
        if(until<=0){
            playNext(mc);
        }
    }
}
