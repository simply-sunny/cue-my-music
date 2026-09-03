package com.cuemymusic.client.ui;

import com.cuemymusic.CueMyMusic;
import com.cuemymusic.data.MusicLibrary;
import com.cuemymusic.data.MusicTrack;
import com.cuemymusic.data.SourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import java.util.*;

// ponytail: centered list — search, sort, top scrub next to search/sort, checkbox queue, preview > / ||
public class JukeboxLibraryScreen extends Screen {
    private static final Component TITLE = Component.literal("Cue My Music");
    private static final Component SUB = Component.literal("Vanilla Music & Discs");
    private static final int ROW_H=18, HEADER_H=14;
    private final Screen parent;
    private MusicLibrary library;
    private String search="";
    private enum SortMode { TITLE, ARTIST, SOURCE }
    private SortMode sortMode = SortMode.ARTIST;
    private final List<MusicTrack> displayed=new ArrayList<>();
    private int scroll=0;
    private EditBox searchField;
    private Button sortButton;
    private int panelL, panelR, panelW, headerY, tableTop, tableBottom, doneX, doneY, doneW, colCheckX, colTypeX, colTitleX, colArtistX, colPreviewX;
    private int topScrubX, topScrubY, topScrubW, topScrubH, topBarX, topBarY, topBarW, topBarH;
    private boolean scrubDragging=false;

    public JukeboxLibraryScreen(Screen parent){ super(TITLE); this.parent=parent; var inst=CueMyMusic.getInstance(); this.library=inst!=null?inst.getLibrary():null; if(this.library==null) this.library=new MusicLibrary(); }
    public JukeboxLibraryScreen(){this(null);}

    private void computeLayout(){
        panelL=10; panelR=width-10; panelW=panelR-panelL;
        int topRowY=30;
        headerY=topRowY+22;
        tableTop=headerY+HEADER_H+2;
        doneW=80; doneX=width/2-doneW/2; doneY=height-24;
        tableBottom=doneY-8;
        if(tableBottom-tableTop<40) tableBottom=tableTop+40;
        colCheckX=panelL+4; colTypeX=panelL+22; colTitleX=panelL+90; colArtistX=panelL+ (int)(panelW*0.55);
        colPreviewX=panelR-18;
        // top scrub next to search/sort — computed in render when visible, but layout gives fallback
        topScrubH=14; topScrubY=topRowY;
    }
    private Component sortLabel(){ return Component.literal(switch(sortMode){ case TITLE->"Sort: Title \u25BE"; case ARTIST->"Sort: Artist \u25BE"; case SOURCE->"Sort: Source \u25BE"; }); }

    @Override protected void init(){
        computeLayout();
        int searchW=200, searchX=width/2-searchW/2-70;
        int sortW=110, sortX=width/2+searchW/2-70+8+40;
        if(sortX+sortW > panelR) { sortX = panelR - sortW; searchX = sortX - searchW - 8 - 60; }
        if(searchX < panelL) searchX = panelL;
        searchField=new EditBox(font, searchX, 30, searchW, 18, Component.literal("Search"));
        searchField.setHint(Component.literal("Search tracks..."));
        searchField.setMaxLength(64);
        searchField.setValue(search);
        searchField.setResponder(v->{ search=v; rebuild(); scroll=0; clamp(); });
        addRenderableWidget(searchField);
        sortButton=Button.builder(sortLabel(), b->{ sortMode=SortMode.values()[(sortMode.ordinal()+1)%SortMode.values().length]; b.setMessage(sortLabel()); rebuild(); clamp(); }).bounds(sortX,30,sortW,20).build();
        addRenderableWidget(sortButton);
        addRenderableWidget(Button.builder(Component.literal("Done"), b->onClose()).bounds(doneX,doneY,doneW,20).build());
        rebuild(); clamp();
    }
    void rebuild(){
        var all=library.getAllTracks();
        Map<String,MusicTrack> dedup=new LinkedHashMap<>();
        for(var t: all) dedup.putIfAbsent(t.getId(), t);
        var filtered=new ArrayList<>(library.filter(new ArrayList<>(dedup.values()), search));
        Comparator<MusicTrack> cmp=switch(sortMode){
            case TITLE -> Comparator.comparing(t->t.getTitle()!=null?t.getTitle().toLowerCase(Locale.ROOT):t.getId().toLowerCase(Locale.ROOT));
            case ARTIST -> Comparator.comparing(t->t.getArtist()!=null?t.getArtist().toLowerCase(Locale.ROOT):"");
            case SOURCE -> Comparator.comparing((MusicTrack t)->t.getSourceType().name()).thenComparing(t->t.getTitle()!=null?t.getTitle():"");
        };
        filtered.sort(cmp);
        displayed.clear(); displayed.addAll(filtered);
    }
    private void clamp(){ int visible=tableBottom-tableTop; int total=displayed.size()*ROW_H; int max=Math.max(0,total-visible); if(scroll<0)scroll=0; if(scroll>max)scroll=max; }

    @Override public void extractRenderState(GuiGraphicsExtractor gfx, int mx,int my,float pt){
        computeLayout();
        gfx.centeredText(font,TITLE,width/2,8,0xFFFFFFFF);
        gfx.centeredText(font,SUB,width/2,18,0xFFAAAAAA);
        // top scrub bar next to search and sort — only when playing, otherwise hidden
        var director=com.cuemymusic.client.playback.MusicDirector.getInstance();
        Minecraft mc=Minecraft.getInstance();
        var curTrack=director.getCurrentTrack().orElse(null);
        boolean anyPlaying=false; try{ anyPlaying=curTrack!=null && director.isPlaying(mc); }catch(Exception ignored){}
        float scrubPos=0,scrubDur=0; try{ scrubPos=director.getPositionSecondsReal(mc); scrubDur=director.getDurationSeconds(); }catch(Exception ignored){}
        float effDur = scrubDur>0?scrubDur:180f;
        if(anyPlaying){
            // place scrub top next to search/sort: to the right of sort or between search and sort
            // layout: search at width/2-140, sort at width/2+110, scrub between or to right
            int searchW=200, sortW=110;
            int searchX=width/2-searchW/2-70;
            int sortX=width/2+searchW/2-70+8+40;
            if(sortX+sortW > panelR) { sortX = panelR - sortW; searchX = sortX - searchW - 8 - 60; }
            // scrub bar to the right of sort, if space else below search/sort but still top
            int availRight = panelR - (sortX+sortW) - 8;
            if(availRight >= 140){
                topScrubX=sortX+sortW+8; topScrubW=availRight; topScrubY=30; topScrubH=20;
            } else {
                // fallback: scrub below search/sort but still top area (y=52)
                topScrubX=panelL+6; topScrubW=panelW-12; topScrubY=52; topScrubH=14;
            }
            topBarH=3; topBarW=topScrubW-60; topBarX=topScrubX+30; topBarY=topScrubY+8;
            // background
            gfx.fill(topScrubX, topScrubY, topScrubX+topScrubW, topScrubY+topScrubH, 0xCC2A2A2A);
            gfx.fill(topScrubX, topScrubY, topScrubX+topScrubW, topScrubY+1, 0x44FFFFFF);
            // bar
            int fill=(int)(topBarW*Math.min(1f, scrubPos/effDur));
            gfx.fill(topBarX, topBarY, topBarX+topBarW, topBarY+topBarH, 0xFF1A1A1A);
            gfx.fill(topBarX, topBarY, topBarX+fill, topBarY+topBarH, 0xFF7ED321);
            int kx=topBarX+fill; gfx.fill(kx-1, topBarY-2, kx+1, topBarY+topBarH+2, 0xFFFFFFFF);
            String left=String.format("%d:%02d",(int)scrubPos/60,(int)scrubPos%60); String right=scrubDur>0?String.format("%d:%02d",(int)scrubDur/60,(int)scrubDur%60):String.format("%d:%02d",(int)effDur/60,(int)effDur%60);
            gfx.text(font, Component.literal(left), topBarX-28, topBarY-2,0xFFAAAAAA);
            gfx.text(font, Component.literal(right), topBarX+topBarW+4, topBarY-2,0xFFAAAAAA);
            if(scrubDragging) gfx.fill(topBarX, topBarY-2, topBarX+topBarW, topBarY+topBarH+2, 0x22FFFFFF);
            // also show track title above scrub when playing
            String titleLine = curTrack.getTitle() + " — " + (curTrack.getArtist()!=null?curTrack.getArtist():"Unknown");
            String trunc = titleLine; if(font.width(trunc) > topScrubW-60) { while(trunc.length()>2 && font.width(trunc+"...") > topScrubW-60) trunc=trunc.substring(0,trunc.length()-1); trunc+="..."; }
            gfx.text(font, Component.literal(trunc), topScrubX+ (topScrubW - font.width(trunc))/2, topScrubY-9, 0xFFAAAAAA);
        }
        gfx.fill(panelL,headerY,panelR,headerY+HEADER_H,0xFF2F2F2F);
        gfx.text(font, Component.literal("✓"), colCheckX, headerY+4,0xFFAAAAAA);
        gfx.text(font, Component.literal("TYPE"), colTypeX, headerY+4,0xFFAAAAAA);
        gfx.text(font, Component.literal("TITLE"), colTitleX, headerY+4,0xFFAAAAAA);
        gfx.text(font, Component.literal("ARTIST"), colArtistX, headerY+4,0xFFAAAAAA);
        gfx.fill(panelL,headerY+HEADER_H-1,panelR,headerY+HEADER_H,0xFF1A1A1A);
        gfx.fill(panelL-1,tableTop-1,panelR+1,tableBottom+1,0xFF000000);
        gfx.fill(panelL,tableTop,panelR,tableBottom,0xFF2F2F2F);
        int visibleH=tableBottom-tableTop;
        int first=visibleH<=0?0:scroll/ROW_H;
        int yOff=-(scroll%ROW_H);
        int rows=visibleH/ROW_H+3;
        gfx.enableScissor(panelL,tableTop,panelR,tableBottom);
        for(int i=0;i<rows;i++){
            int idx=first+i; if(idx>=displayed.size()) break;
            var t=displayed.get(idx);
            int y=tableTop+yOff+i*ROW_H; if(y+ROW_H<tableTop||y>=tableBottom) continue;
            boolean hover=mx>=panelL&&mx<panelR&&my>=y&&my<y+ROW_H;
            int bg=(idx&1)==0?0xFF3A3A3A:0xFF333333; if(hover) bg=0xFF444444;
            gfx.fill(panelL+1,y,panelR-1,y+ROW_H,bg);
            int cbX=colCheckX, cbY=y+4, cbS=10;
            gfx.fill(cbX,cbY,cbX+cbS,cbY+cbS,t.isAmbientEligible()?0xFF7ED321:0xFF222222);
            gfx.fill(cbX,cbY,cbX+cbS,cbY+1,0xFF555555); gfx.fill(cbX,cbY+cbS-1,cbX+cbS,cbY+cbS,0xFF555555);
            if(t.isAmbientEligible()) gfx.text(font, Component.literal("✓"), cbX+1, cbY-1, 0xFF000000);
            String type=t.getSourceType()==SourceType.MUSIC_DISC?"Disc":"Music";
            gfx.text(font, Component.literal(type), colTypeX, y+5, t.getSourceType()==SourceType.MUSIC_DISC?0xFF5AA9FF:0xFF7ED321);
            gfx.text(font, Component.literal(truncate(t.getTitle()!=null?t.getTitle():t.getId(), colArtistX-colTitleX-8)), colTitleX, y+5, 0xFFFFFFFF);
            gfx.text(font, Component.literal(truncate(t.getArtist()!=null?t.getArtist():"Unknown", colPreviewX-colArtistX-10)), colArtistX, y+5, 0xFFE8E8E8);
            boolean isCurrent=curTrack!=null && curTrack.getId().equals(t.getId());
            boolean isPlaying=false; try{ isPlaying=isCurrent && director.isPlaying(mc); }catch(Exception ignored){}
            String sym=isPlaying?"||":">";
            int btnX=colPreviewX, btnY=y+3, btnW=14, btnH=12;
            boolean btnHover=mx>=btnX&&mx<btnX+btnW&&my>=btnY&&my<btnY+btnH;
            int btnBg=btnHover?0xFF4A4A4A:0xFF2A2A2A;
            if(isCurrent && isPlaying) btnBg=btnHover?0xFF5A7A3A:0xFF4A5A3A;
            gfx.fill(btnX,btnY,btnX+btnW,btnY+btnH,btnBg);
            int tw=font.width(sym);
            gfx.text(font, Component.literal(sym), btnX+(btnW-tw)/2, btnY+2, 0xFFFFFFFF);
        }
        gfx.disableScissor();
        if(displayed.isEmpty()){
            String msg=search.isBlank()?"No tracks":"No matches for \""+search+"\"";
            gfx.text(font, Component.literal(msg), panelL+8, tableTop+12,0xFFAAAAAA);
        }
        int totalH=displayed.size()*ROW_H;
        if(totalH>visibleH){
            int thumbH=Math.max(18, visibleH*visibleH/Math.max(1,totalH));
            int thumbY=tableTop+ (scroll*(visibleH-thumbH)/Math.max(1,totalH-visibleH));
            gfx.fill(panelR-4,tableTop,panelR,tableBottom,0xFF1A1A1A);
            gfx.fill(panelR-4,thumbY,panelR,thumbY+thumbH,0xFFAAAAAA);
        }
        long selected=library.getAllTracks().stream().filter(MusicTrack::isAmbientEligible).count();
        String selText=selected+" selected";
        gfx.text(font, Component.literal(selText), colCheckX, tableBottom+2,0xFF7ED321);
        String count=displayed.size()+" tracks"+(displayed.size()!=library.getAllTracks().size()?" (filtered)":"");
        int cw=font.width(count); gfx.text(font, Component.literal(count), panelR-cw, tableBottom+2,0xFFAAAAAA);
        super.extractRenderState(gfx,mx,my,pt);
    }
    @Override public boolean mouseScrolled(double mx,double my,double sx,double sy){
        if(mx>=panelL&&mx<panelR&&my>=tableTop&&my<tableBottom){ scroll-=(int)(sy*ROW_H*2); clamp(); return true; }
        return super.mouseScrolled(mx,my,sx,sy);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean d){
        double mx=e.x(), my=e.y();
        // top scrub bar next to search/sort — only when playing
        if(e.button()==0){
            var dir=com.cuemymusic.client.playback.MusicDirector.getInstance(); Minecraft mc=Minecraft.getInstance();
            try{
                var cur=dir.getCurrentTrack().orElse(null);
                if(cur!=null && dir.isPlaying(mc)){
                    if(mx>=topBarX&&mx<topBarX+topBarW&&my>=topBarY-4&&my<topBarY+topBarH+4){
                        float dur=dir.getDurationSeconds(); float effDur=dur>0?dur:180f;
                        float f=Math.max(0,Math.min(1,(float)(mx - topBarX)/topBarW)); dir.seek(mc, f*effDur);
                        scrubDragging=true; return true;
                    }
                }
            }catch(Exception ignored){}
        }
        if(e.button()==0 && mx>=panelL&&mx<panelR&&my>=tableTop&&my<tableBottom){
            int idx=(int)((my-tableTop+scroll)/ROW_H);
            if(idx>=0&&idx<displayed.size()){
                var t=displayed.get(idx);
                int first=scroll/ROW_H;
                int yOff=-(scroll%ROW_H);
                int rowY=tableTop + yOff + (idx - first)*ROW_H;
                int cbX=colCheckX, cbY=rowY+4;
                if(mx>=cbX&&mx<cbX+10&&my>=cbY&&my<cbY+10){
                    t.setAmbientEligible(!t.isAmbientEligible());
                    try{ CueMyMusic.getInstance().saveAll(); }catch(Exception ignored){}
                    return true;
                }
                int btnX=colPreviewX, btnY=rowY+3;
                if(mx>=btnX&&mx<btnX+14&&my>=btnY&&my<btnY+12){
                    previewTrack(t);
                    return true;
                }
                return true;
            }
        }
        return super.mouseClicked(e,d);
    }
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){
        if(scrubDragging){
            var dir=com.cuemymusic.client.playback.MusicDirector.getInstance(); Minecraft mc=Minecraft.getInstance();
            try{
                var cur=dir.getCurrentTrack().orElse(null);
                if(cur!=null && dir.isPlaying(mc)){
                    float dur=dir.getDurationSeconds(); float effDur=dur>0?dur:180f;
                    float f=Math.max(0,Math.min(1,(float)(e.x()-topBarX)/topBarW)); dir.seek(mc, f*effDur);
                    return true;
                }
            }catch(Exception ignored){}
        }
        return super.mouseDragged(e,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e){ if(scrubDragging){ scrubDragging=false; return true; } return super.mouseReleased(e); }
    private void previewTrack(MusicTrack track){
        if(track==null) return;
        try{
            var mc=Minecraft.getInstance();
            var director=com.cuemymusic.client.playback.MusicDirector.getInstance();
            var cur=director.getCurrentTrack().orElse(null);
            if(cur!=null && cur.getId().equals(track.getId())){
                if(director.isPlaying(mc)){ director.togglePause(mc); return; }
                if(director.isPaused()){ director.togglePause(mc); return; }
            }
            try{ if(mc.getMusicManager()!=null) mc.getMusicManager().stopPlaying(); }catch(Exception ignored){}
            try{ mc.getSoundManager().stop(null, SoundSource.MUSIC); }catch(Exception ignored){}
            director.stopCurrent(mc);
            director.playTrack(mc, track);
        }catch(Exception ignored){}
    }
    @Override public boolean keyPressed(KeyEvent e){
        if(searchField!=null&&searchField.isFocused()) return super.keyPressed(e);
        return super.keyPressed(e);
    }
    @Override public void onClose(){
        try{ var mc=Minecraft.getInstance(); com.cuemymusic.client.playback.MusicDirector.getInstance().stopCurrent(mc); }catch(Exception ignored){}
        try{ CueMyMusic.getInstance().saveAll(); }catch(Exception ignored){}
        Minecraft.getInstance().setScreenAndShow(parent);
    }
    @Override public void removed(){
        try{ var mc=Minecraft.getInstance(); com.cuemymusic.client.playback.MusicDirector.getInstance().stopCurrent(mc); }catch(Exception ignored){}
    }
    private String truncate(String s,int maxW){ if(font.width(s)<=maxW) return s; String ell="..."; int ew=font.width(ell); String out=s; while(out.length()>0&&font.width(out)+ew>maxW) out=out.substring(0,out.length()-1); return out+ell; }
}
