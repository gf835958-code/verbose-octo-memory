package com.kp.recapstudio;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;

/** Read actual decoded PCM energy in 100-ms windows for audio-reactive video waveforms. */
final class AudioEnvelope {
    interface Callback {
        void update(String message);
        void ready(float[] envelope);
        void failed(String problem);
    }
    private AudioEnvelope(){}
    static void analyze(Context context,Uri source,Callback callback){
        new Thread(()->{
            File wav=null;
            try{
                callback.update("Decoding audio for real waveform...");
                wav=MediaAudioDecoder.decode(context,source,pct->{
                    if(pct%20==0)callback.update("Audio waveform decode "+pct+"%");
                });
                float[] levels=fromWav(wav);
                callback.ready(levels);
            }catch(Exception e){callback.failed(e.getMessage());}
            finally{if(wav!=null)wav.delete();}
        },"kp-waveform-audio").start();
    }
    private static int u16(byte[] bytes,int offset){
        return (bytes[offset]&255)|((bytes[offset+1]&255)<<8);
    }
    private static int u32(byte[] bytes,int offset){
        return u16(bytes,offset)|(u16(bytes,offset+2)<<16);
    }
    static float[] fromWav(File input)throws IOException{
        ArrayList<Float> values=new ArrayList<>();
        try(FileInputStream stream=new FileInputStream(input)){
            byte[] header=new byte[44];int read=0;
            while(read<header.length){int n=stream.read(header,read,44-read);if(n<0)throw new IOException("Bad WAV header");read+=n;}
            if(header[0]!='R'||header[1]!='I'||header[2]!='F'||header[3]!='F')
                throw new IOException("Not a PCM WAV");
            int channels=u16(header,22),rate=u32(header,24);
            if(channels<1||channels>2||rate<8000||rate>192000)
                throw new IOException("Unsupported PCM sample format");
            int frames=Math.max(1,rate/10);
            int samplesPerWindow=frames*channels;
            byte[] block=new byte[65536];
            int pending=-1;long squares=0;int count=0,n;
            while((n=stream.read(block))!=-1){
                for(int i=0;i<n;i++){
                    int byteValue=block[i]&255;
                    if(pending<0){pending=byteValue;continue;}
                    short pcm=(short)(pending|(byteValue<<8));pending=-1;
                    long sample=pcm;
                    squares+=sample*sample;count++;
                    if(count>=samplesPerWindow){
                        float rms=(float)Math.sqrt((double)squares/count)/32768f;
                        values.add(rms);
                        squares=0;count=0;
                        if(values.size()>220000)throw new IOException("Waveform exceeds six hours");
                    }
                }
            }
            if(count>0)values.add((float)Math.sqrt((double)squares/count)/32768f);
        }
        if(values.isEmpty())throw new IOException("No audio samples");
        float max=.005f;
        for(float v:values)max=Math.max(max,v);
        float[] result=new float[values.size()];
        for(int i=0;i<result.length;i++){
            float ratio=values.get(i)/max;
            result[i]=(float)Math.max(.05,Math.min(1,Math.sqrt(ratio)));
        }
        return result;
    }
}
