package com.kp.recapstudio;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** User-initiated first-run multilingual Whisper model install; no silent data charges. */
final class SpeechModelStore {
    interface Progress {
        void update(String status);
        void completed(File model);
        void failed(String message);
    }
    private static final String MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin";
    private static final long MIN_BYTES=50_000_000L,MAX_BYTES=250_000_000L;
    private SpeechModelStore() {}

    static File model(Context context){
        File dir=new File(context.getFilesDir(),"speech-models");
        if(!dir.exists())dir.mkdirs();
        return new File(dir,"ggml-tiny.bin");
    }
    static boolean ready(Context context){
        File f=model(context);return f.isFile() && f.length()>MIN_BYTES;
    }
    static void installFromFile(Context context, Uri source, Progress callback){
        new Thread(()->{
            File dest=model(context),part=new File(dest.getAbsolutePath()+".partial");
            try(InputStream in=context.getContentResolver().openInputStream(source)){
                if(in==null)throw new Exception("Cannot open model");
                copy(in,part,-1,callback);
                finish(part,dest,callback);
            }catch(Exception e){part.delete();callback.failed(e.getMessage());}
        },"kp-model-import").start();
    }
    static void download(Context context,Progress callback){
        new Thread(()->{
            HttpURLConnection connection=null;
            File dest=model(context),part=new File(dest.getAbsolutePath()+".partial");
            try{
                connection=(HttpURLConnection)new URL(MODEL_URL).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent","KP-Recap-Studio/0.4");
                int status=connection.getResponseCode();
                if(status<200||status>=300)throw new Exception("Model server returned "+status);
                long length=connection.getContentLengthLong();
                if(length>MAX_BYTES)throw new Exception("Model too large");
                try(InputStream in=connection.getInputStream()){
                    copy(in,part,length,callback);
                }
                finish(part,dest,callback);
            }catch(Exception e){part.delete();callback.failed(e.getMessage());}
            finally{if(connection!=null)connection.disconnect();}
        },"kp-model-download").start();
    }
    private static void copy(InputStream in,File part,long total,Progress callback)throws Exception{
        long count=0,lastReport=0;
        try(FileOutputStream out=new FileOutputStream(part)){
            byte[] block=new byte[131072];
            int n;
            while((n=in.read(block))!=-1){
                count+=n;
                if(count>MAX_BYTES)throw new Exception("Model exceeds 250 MB");
                out.write(block,0,n);
                if(count-lastReport>=1_500_000){
                    lastReport=count;
                    String detail=total>0?(100*count/total)+"%":(count/1048576)+" MiB";
                    callback.update("Whisper model: "+detail);
                }
            }
            out.getFD().sync();
        }
        if(count<MIN_BYTES)throw new Exception("Model is incomplete ("+count+" bytes)");
    }
    private static void finish(File partial,File target,Progress callback)throws Exception {
        if(target.exists()&&!target.delete())throw new Exception("Cannot replace old model");
        if(!partial.renameTo(target))throw new Exception("Cannot activate new model file");
        callback.completed(target);
    }
}
