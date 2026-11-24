package com.kp.recapstudio;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creates a video-duration PCM16 WAV, preserving SRT time windows.
 * Each translated caption is synthesized separately, silence is inserted before its
 * intended start, and overlong speech is truncated at the next caption start.
 * It does not change speech speed or claim lip-sync.
 */
final class TimedDubber {
    interface Listener {
        void progress(String message);
        void done(File wav);
        void error(String message);
    }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final long MAX_WAV_DATA=0xFFFFFFFFL-36;
    private TimedDubber(){}

    static void create(Context context,TextToSpeech tts,List<MotionOverlay.Cue> input,
                       long videoDurationMs,long clipStartMs,Listener callbacks){
        if(tts==null){callbacks.error("Android TTS is not available");return;}
        if(videoDurationMs<=0){callbacks.error("Video duration unavailable");return;}
        ArrayList<MotionOverlay.Cue> cues=new ArrayList<>();
        for(MotionOverlay.Cue cue:input){
            if(cue.text!=null&&!cue.text.trim().isEmpty() &&
                cue.endUs>clipStartMs*1000L &&
                cue.startUs<(videoDurationMs+clipStartMs)*1000L)
                cues.add(cue);
        }
        cues.sort(Comparator.comparingLong(c->c.startUs));
        if(cues.isEmpty()){callbacks.error("No timed subtitles in this clip");return;}
        if(cues.size()>300){callbacks.error("Too many captions; maximum 300 per render");return;}

        File root=context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if(root==null){callbacks.error("Music directory unavailable");return;}
        File folder=new File(root,"kp-synced-"+System.nanoTime());
        if(!folder.mkdirs()){callbacks.error("Cannot create temporary audio folder");return;}
        ArrayList<File> parts=new ArrayList<>();
        AtomicBoolean active=new AtomicBoolean(true);
        final int[] index={0};
        final Runnable[] next={null};
        final boolean[] pending={false};

        next[0]=()->{
            if(!active.get())return;
            if(index[0]>=cues.size()){
                active.set(false);
                MAIN.post(()->callbacks.progress("Aligning voice with subtitle timestamps..."));
                new Thread(()->{
                    File audio=new File(root,"kp-synced-dub-"+System.currentTimeMillis()+".wav");
                    try{
                        align(cues,parts,audio,videoDurationMs,clipStartMs);
                        MAIN.post(()->callbacks.done(audio));
                    }catch(Exception e){
                        audio.delete();
                        MAIN.post(()->callbacks.error(e.getMessage()));
                    }finally{
                        for(File p:parts)p.delete();
                        folder.delete();
                    }
                },"kp-dub-align").start();
                return;
            }
            MotionOverlay.Cue cue=cues.get(index[0]);
            File out=new File(folder,String.format(java.util.Locale.US,"part-%04d.wav",index[0]));
            parts.add(out);
            int current=index[0]++;
            pending[0]=true;
            MAIN.post(()->callbacks.progress("Narrating cue "+(current+1)+"/"+cues.size()));
            int accepted=tts.synthesizeToFile(cue.text.trim(),new Bundle(),out,"KP_TIMED_"+current);
            if(accepted!=TextToSpeech.SUCCESS){
                pending[0]=false;active.set(false);
                MAIN.post(()->callbacks.error("Speech engine rejected subtitle "+(current+1)));
            }
        };
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){}
            @Override public void onDone(String id){
                if(active.get()&&id!=null&&id.equals("KP_TIMED_"+(index[0]-1))&&pending[0]){
                    pending[0]=false;
                    MAIN.post(next[0]);
                }
            }
            @Override public void onError(String id){
                if(active.compareAndSet(true,false))MAIN.post(()->callbacks.error("Speech engine failed: "+id));
            }
        });
        MAIN.post(next[0]);
    }

    private static int u16(RandomAccessFile in)throws IOException {
        return in.readUnsignedByte()|(in.readUnsignedByte()<<8);
    }
    private static long u32(RandomAccessFile in)throws IOException {
        return ((long)u16(in))|(((long)u16(in))<<16);
    }
    private static String ascii(RandomAccessFile in)throws IOException{
        byte[] b=new byte[4];in.readFully(b);
        return new String(b,java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static void put2(RandomAccessFile f,int n)throws IOException{
        f.writeByte(n&255);f.writeByte((n>>>8)&255);
    }
    private static void put4(RandomAccessFile f,long n)throws IOException{
        put2(f,(int)n);put2(f,(int)(n>>>16));
    }
    private static void putHeader(RandomAccessFile f,int rate,int channels,long bytes)throws IOException{
        f.seek(0);f.writeBytes("RIFF");put4(f,bytes+36);
        f.writeBytes("WAVEfmt ");put4(f,16);put2(f,1);put2(f,channels);
        put4(f,rate);put4(f,(long)rate*channels*2);put2(f,channels*2);put2(f,16);
        f.writeBytes("data");put4(f,bytes);
    }
    private static final class Info {
        int rate,channels,bitDepth,format;
        long dataOffset,dataLength;
    }
    private static Info inspect(File part)throws IOException{
        try(RandomAccessFile in=new RandomAccessFile(part,"r")){
            if(in.length()<44||!"RIFF".equals(ascii(in)))throw new IOException("Voice is not a WAV: "+part.getName());
            u32(in);if(!"WAVE".equals(ascii(in)))throw new IOException("Voice file is not a WAVE");
            Info info=new Info();boolean fmt=false,audio=false;
            while(in.getFilePointer()+8<=in.length()){
                String tag=ascii(in);long n=u32(in);long start=in.getFilePointer();
                if(n<0||n>in.length()-start)throw new IOException("Corrupted voice WAV");
                if("fmt ".equals(tag)&&n>=16){
                    info.format=u16(in);info.channels=u16(in);info.rate=(int)u32(in);
                    u32(in);u16(in);info.bitDepth=u16(in);fmt=true;
                }else if("data".equals(tag)){
                    info.dataOffset=start;info.dataLength=n;audio=true;
                }
                in.seek(Math.min(in.length(),start+n+(n%2)));
            }
            if(!fmt||!audio||info.format!=1||info.bitDepth!=16||info.channels<1||
               info.channels>2||info.rate<=0)
                throw new IOException("TTS voice needs 16-bit PCM WAV");
            return info;
        }
    }
    private static void silence(RandomAccessFile out,long bytes)throws IOException{
        byte[] zeros=new byte[32768];
        while(bytes>0){int n=(int)Math.min(zeros.length,bytes);out.write(zeros,0,n);bytes-=n;}
    }
    private static void align(List<MotionOverlay.Cue> cues,List<File> parts,File destination,
                              long videoDurationMs,long clipStartMs)throws IOException{
        if(parts.size()!=cues.size())throw new IOException("Narration part count mismatch");
        Info standard=inspect(parts.get(0));
        int frameBytes=standard.channels*2,rate=standard.rate;
        long totalFrames=(videoDurationMs*rate)/1000L;
        if(totalFrames*frameBytes>MAX_WAV_DATA)
            throw new IOException("Dubbed WAV exceeds 4 GiB limit");
        long writtenFrames=0, clipStartUs=clipStartMs*1000L;
        byte[] buffer=new byte[65536];
        try(RandomAccessFile out=new RandomAccessFile(destination,"rw")){
            out.setLength(0);putHeader(out,rate,standard.channels,0);
            out.seek(44);
            for(int i=0;i<cues.size();i++){
                Info item=inspect(parts.get(i));
                if(item.rate!=rate||item.channels!=standard.channels)
                    throw new IOException("Voice format changed; choose one Android TTS voice.");
                MotionOverlay.Cue cue=cues.get(i);
                long startUs=Math.max(0,cue.startUs-clipStartUs);
                long endUs=Math.min(videoDurationMs*1000L,cue.endUs-clipStartUs);
                long cueStartFrames=Math.min(totalFrames,(startUs*rate)/1000000L);
                if(i+1<cues.size()){
                    long nextStart=Math.max(0,cues.get(i+1).startUs-clipStartUs);
                    endUs=Math.min(endUs,nextStart);
                }
                long cueEndFrames=Math.min(totalFrames,Math.max(0,(endUs*rate)/1000000L));
                if(cueStartFrames<writtenFrames)cueStartFrames=writtenFrames;
                if(cueEndFrames<=cueStartFrames)continue;
                if(cueStartFrames>writtenFrames){
                    silence(out,(cueStartFrames-writtenFrames)*frameBytes);
                    writtenFrames=cueStartFrames;
                }
                long available=Math.max(0,cueEndFrames-writtenFrames);
                long partFrames=item.dataLength/frameBytes;
                long readFrames=Math.min(available,partFrames);
                try(RandomAccessFile in=new RandomAccessFile(parts.get(i),"r")){
                    in.seek(item.dataOffset);
                    long bytes=readFrames*frameBytes;
                    while(bytes>0){
                        int n=(int)Math.min(buffer.length,bytes);
                        in.readFully(buffer,0,n);out.write(buffer,0,n);bytes-=n;
                    }
                }
                writtenFrames+=readFrames;
            }
            if(totalFrames>writtenFrames)silence(out,(totalFrames-writtenFrames)*frameBytes);
            putHeader(out,rate,standard.channels,totalFrames*frameBytes);
            out.setLength(44+totalFrames*frameBytes);
        }
    }
}
