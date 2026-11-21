package com.kp.recapstudio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.util.List;

/** Joins PCM-WAV clips produced by compatible Android TTS engines. */
final class WavAssembler {
    private static final class Chunk {
        long offset, length;
        int format, channels, sampleRate, byteRate, align, bits;
    }
    private WavAssembler(){}

    private static int read2(RandomAccessFile f)throws IOException {
        int a=f.readUnsignedByte(), b=f.readUnsignedByte();
        return a | b<<8;
    }
    private static long read4(RandomAccessFile f)throws IOException {
        long a=read2(f),b=read2(f);return a | b<<16;
    }
    private static String id(RandomAccessFile f)throws IOException {
        byte[] b=new byte[4];f.readFully(b);return new String(b,java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static Chunk inspect(File file)throws IOException {
        try(RandomAccessFile f=new RandomAccessFile(file,"r")){
            if(f.length()<44 || !"RIFF".equals(id(f)))throw new IOException("Not a RIFF WAV: "+file.getName());
            read4(f);
            if(!"WAVE".equals(id(f)))throw new IOException("Not WAVE");
            Chunk c=new Chunk();boolean hasFormat=false,hasAudio=false;
            while(f.getFilePointer()+8<=f.length()){
                String name=id(f);long len=read4(f),pos=f.getFilePointer();
                if(len<0||len>f.length()-pos)throw new IOException("Damaged WAV chunk");
                if("fmt ".equals(name)&&len>=16){
                    c.format=read2(f);c.channels=read2(f);c.sampleRate=(int)read4(f);
                    c.byteRate=(int)read4(f);c.align=read2(f);c.bits=read2(f);
                    hasFormat=true;
                }else if("data".equals(name)){c.offset=pos;c.length=len;hasAudio=true;}
                f.seek(Math.min(f.length(),pos+len+(len%2)));
            }
            if(!hasFormat||!hasAudio)throw new IOException("WAV audio data missing");
            if(c.format!=1)throw new IOException("Expected PCM WAV from TTS, format="+c.format);
            if(c.channels<1||c.channels>2 || c.bits!=16)throw new IOException("Unsupported voice WAV format");
            return c;
        }
    }
    private static void le2(OutputStream o,int v)throws IOException {
        o.write(v&255);o.write(v>>8&255);
    }
    private static void le4(OutputStream o,long v)throws IOException {
        le2(o,(int)v);le2(o,(int)(v>>>16));
    }
    static void merge(List<File> segments,File destination)throws IOException {
        if(segments==null||segments.isEmpty())throw new IOException("No audio parts to combine.");
        Chunk format=inspect(segments.get(0));
        long total=0;
        for(File f:segments){
            Chunk c=inspect(f);
            if(c.channels!=format.channels || c.sampleRate!=format.sampleRate
                || c.bits!=format.bits || c.align!=format.align)
                throw new IOException("TTS engine changed audio format mid-book.");
            total+=c.length;
        }
        if(total<=0||total>0xFFFFFFFFL-36)throw new IOException("Audiobook WAV exceeds 4GB.");
        File tmp=new File(destination.getAbsolutePath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(new byte[]{'R','I','F','F'});le4(out,total+36);
            out.write(new byte[]{'W','A','V','E','f','m','t',' '});le4(out,16);
            le2(out,format.format);le2(out,format.channels);
            le4(out,format.sampleRate);le4(out,format.byteRate);
            le2(out,format.align);le2(out,format.bits);
            out.write(new byte[]{'d','a','t','a'});le4(out,total);
            byte[] buf=new byte[32768];
            for(File file:segments){
                Chunk c=inspect(file);
                try(FileInputStream in=new FileInputStream(file)){
                    long skip=c.offset;
                    while(skip>0){long n=in.skip(skip);if(n<=0)throw new IOException("Could not seek WAV audio");skip-=n;}
                    long remain=c.length;
                    while(remain>0){
                        int n=in.read(buf,0,(int)Math.min(buf.length,remain));
                        if(n<=0)throw new IOException("Truncated WAV segment");
                        out.write(buf,0,n);remain-=n;
                    }
                }
            }
        }catch(Exception e){tmp.delete();throw e;}
        if(!tmp.renameTo(destination))throw new IOException("Unable to finalize audiobook file");
    }
}
