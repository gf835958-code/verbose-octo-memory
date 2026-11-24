package com.kp.recapstudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * Decode any Android MediaCodec-supported video/audio input to a PCM WAV file.
 * Streams to disk; never loads a whole movie into memory. Whisper resamples the WAV.
 */
final class MediaAudioDecoder {
    interface Progress { void onProgress(int value); }
    private MediaAudioDecoder() {}

    private static void u16(RandomAccessFile f,int n)throws IOException{
        f.writeByte(n&255);f.writeByte((n>>8)&255);
    }
    private static void u32(RandomAccessFile f,long n)throws IOException{
        u16(f,(int)n);u16(f,(int)(n>>16));
    }
    private static void header(RandomAccessFile f,int sampleRate,int channels,long payload)throws IOException{
        f.seek(0);f.writeBytes("RIFF");u32(f,36+payload);f.writeBytes("WAVEfmt ");
        u32(f,16);u16(f,1);u16(f,channels);u32(f,sampleRate);
        u32(f,(long)sampleRate*channels*2);u16(f,channels*2);u16(f,16);
        f.writeBytes("data");u32(f,payload);
    }
    static File decode(Context context, Uri input, Progress progress) throws Exception {
        MediaExtractor ex=new MediaExtractor();
        MediaCodec decoder=null;
        File result=new File(context.getCacheDir(),"kp-asr-"+System.currentTimeMillis()+".wav");
        try {
            ex.setDataSource(context,input,new HashMap<>());
            int chosen=-1;MediaFormat format=null;
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat f=ex.getTrackFormat(i);
                if(f.containsKey(MediaFormat.KEY_MIME) &&
                   f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){chosen=i;format=f;break;}
            }
            if(chosen<0)throw new IOException("This video has no audio track.");
            ex.selectTrack(chosen);
            String mime=format.getString(MediaFormat.KEY_MIME);
            if("audio/raw".equals(mime))throw new IOException("Raw PCM containers are not supported here. Import WAV to transcribe.");
            decoder=MediaCodec.createDecoderByType(mime);
            decoder.configure(format,null,null,0);decoder.start();
            int sampleRate=format.containsKey(MediaFormat.KEY_SAMPLE_RATE)?format.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int channels=format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?format.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
            int encoding=AudioFormat.ENCODING_PCM_16BIT;
            long durationUs=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
            if(channels<1||channels>2)throw new IOException("Only mono and stereo audio supported");
            try(RandomAccessFile file=new RandomAccessFile(result,"rw")){
                file.setLength(0);header(file,sampleRate,channels,0);
                long total=0;boolean inputEOS=false,outputEOS=false;int stalls=0;
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                while(!outputEOS){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Transcription cancelled");
                    if(!inputEOS){
                        int in=decoder.dequeueInputBuffer(10000);
                        if(in>=0){
                            ByteBuffer buf=decoder.getInputBuffer(in);
                            if(buf==null)throw new IOException("No decoder input buffer");
                            buf.clear();
                            int count=ex.readSampleData(buf,0);
                            if(count<0){decoder.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEOS=true;}
                            else {
                                decoder.queueInputBuffer(in,0,count,Math.max(0,ex.getSampleTime()),0);
                                if(progress!=null&&durationUs>0)
                                    progress.onProgress((int)Math.min(99,100*ex.getSampleTime()/durationUs));
                                ex.advance();
                            }
                        }
                    }
                    int out=decoder.dequeueOutputBuffer(info,10000);
                    if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        MediaFormat o=decoder.getOutputFormat();
                        sampleRate=o.containsKey(MediaFormat.KEY_SAMPLE_RATE)?o.getInteger(MediaFormat.KEY_SAMPLE_RATE):sampleRate;
                        channels=o.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?o.getInteger(MediaFormat.KEY_CHANNEL_COUNT):channels;
                        encoding=o.containsKey(MediaFormat.KEY_PCM_ENCODING)?o.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                        if(channels<1||channels>2)throw new IOException("Cannot render audio with "+channels+" channels");
                        if(total!=0)throw new IOException("Decoder changed audio format after samples started");
                    }else if(out>=0){
                        stalls=0;
                        ByteBuffer data=decoder.getOutputBuffer(out);
                        if(data!=null&&info.size>0){
                            data.position(info.offset);data.limit(info.offset+info.size);
                            if(encoding==AudioFormat.ENCODING_PCM_FLOAT){
                                data.order(ByteOrder.LITTLE_ENDIAN);
                                while(data.remaining()>=4){
                                    float v=data.getFloat();
                                    if(!Float.isFinite(v))v=0;
                                    short sample=(short)(Math.max(-1,Math.min(1,v))*32767);
                                    file.writeByte(sample&255);file.writeByte((sample>>>8)&255);total+=2;
                                }
                            }else if(encoding==AudioFormat.ENCODING_PCM_16BIT){
                                byte[] block=new byte[Math.min(65536,Math.max(4096,data.remaining()))];
                                while(data.hasRemaining()){
                                    int n=Math.min(block.length,data.remaining());data.get(block,0,n);file.write(block,0,n);total+=n;
                                }
                            }else throw new IOException("Decoder returned unsupported PCM format "+encoding);
                        }
                        outputEOS=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                        decoder.releaseOutputBuffer(out,false);
                        if(total>0xFFFFFFFFL-36)throw new IOException("WAV exceeds 4 GiB");
                    }else if(++stalls>3000)throw new IOException("Audio decoder timed out");
                }
                if(total<=0)throw new IOException("No voice/audio decoded");
                if(total%(channels*2)!=0)throw new IOException("Misaligned decoded PCM bytes");
                header(file,sampleRate,channels,total);
                if(progress!=null)progress.onProgress(100);
            }
            return result;
        }catch(Exception e){result.delete();throw e;}
        finally{
            if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}
            ex.release();
        }
    }
}
