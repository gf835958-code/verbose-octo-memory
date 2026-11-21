package com.kp.recapstudio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Frame-timed captions; in Audio Hub mode an animated decorative visualizer. */
@UnstableApi
final class MotionOverlay extends CanvasOverlay {
    static final class Cue {
        final long startUs, endUs;
        final String text;
        Cue(long startUs, long endUs, String text) { this.startUs=startUs; this.endUs=endUs; this.text=text; }
    }
    private final boolean videoMode;
    private final String text;
    private final List<Cue> cues;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    MotionOverlay(boolean videoMode, String caption, List<Cue> cues) {
        super(true);
        this.videoMode=videoMode;
        this.text=caption == null ? "" : caption.trim();
        this.cues=cues == null ? new ArrayList<>() : cues;
    }

    @Override public void onDraw(Canvas canvas, long presentationTimeUs) {
        int w=canvas.getWidth(), h=canvas.getHeight();
        if (w<=0 || h<=0) return;
        if (videoMode) {
            String current="";
            for (Cue cue : cues) {
                if (presentationTimeUs >= cue.startUs && presentationTimeUs < cue.endUs) { current=cue.text; break; }
            }
            if (current.isEmpty() && cues.isEmpty()) current=text;
            if (!current.isEmpty()) {
                paint.setColor(0xB9000716);
                canvas.drawRoundRect(new RectF(w*.05f,h*.78f,w*.95f,h*.97f),18,18,paint);
                drawText(canvas,current,(int)(w*.09f),(int)(h*.81f),(int)(w*.82f),Math.max(22,(int)(h*.047f)),Color.WHITE);
            }
        } else {
            // These bars intentionally animate from timestamps; they are NOT audio-reactive yet.
            float centerY=h*.89f, centerX=w/2f;
            int count=72;
            float areaWidth=w*.75f, barSpace=areaWidth/count;
            double sec=presentationTimeUs/1_000_000.0;
            for(int i=0;i<count;i++) {
                float phase=(float)(i*.29+sec*4.2);
                float amplitude=(float)(.20+.8*Math.abs(Math.sin(phase)*Math.cos(phase*.32+sec)));
                float barHeight=h*.105f*amplitude;
                paint.setColor((i%4==0)?0xFFB989FE:0xFF54F6DD);
                float x=centerX-areaWidth/2+i*barSpace;
                canvas.drawRoundRect(new RectF(x,centerY-barHeight,x+Math.max(2,barSpace*.65f),centerY+barHeight),4,4,paint);
            }
        }
    }

    private void drawText(Canvas c,String s,int x,int y,int width,int size,int color) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(color);tp.setTextSize(size);
        tp.setShadowLayer(3,2,2,Color.BLACK);
        StaticLayout lay=StaticLayout.Builder.obtain(s,0,s.length(),tp,width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(3).build();
        c.save(); c.translate(x,y);lay.draw(c);c.restore();
    }

    static List<Cue> parseSrt(String srt) {
        ArrayList<Cue> list=new ArrayList<>();
        if(srt==null) return list;
        String normal=srt.replace("\r\n","\n").replace('\r','\n').replace("\uFEFF","");
        for(String block:normal.split("\n\s*\n")){
            String[] lines=block.trim().split("\n");
            int idx=-1;
            for(int i=0;i<lines.length;i++) if(lines[i].contains("-->")) { idx=i; break; }
            if(idx<0 || idx+1>=lines.length) continue;
            try{
                String[] times=lines[idx].split("-->"), start=times[0].trim().split(" "), end=times[1].trim().split(" ");
                long a=parseTime(start[0]),b=parseTime(end[0]);
                StringBuilder sb=new StringBuilder();
                for(int j=idx+1;j<lines.length;j++){if(sb.length()>0)sb.append('\n');sb.append(lines[j]);}
                if(b>a && sb.length()>0)list.add(new Cue(a*1000,b*1000,sb.toString()));
            }catch(Exception ignored) { }
        }
        return list;
    }
    private static long parseTime(String s) {
        String[] p=s.trim().replace(',', '.').split("[:.]");
        if(p.length!=4) throw new IllegalArgumentException("Bad SRT time");
        return ((Long.parseLong(p[0])*3600+Long.parseLong(p[1])*60+Long.parseLong(p[2]))*1000)+Long.parseLong(p[3]);
    }
}
