package com.kp.recapstudio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Optional online Burmese translation; app never contains a Cloudflare API key.
 *  Only text/SRT cues are submitted to a user-owned HTTPS Worker.
 */
final class MyanmarTranslationClient {
    interface Callback {
        void update(String text);
        void finished(String translatedText,String translatedSrt);
        void error(String cause);
    }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private MyanmarTranslationClient(){}
    static String endpoint(Context c){
        return c.getSharedPreferences("kp-recap-settings",Context.MODE_PRIVATE).getString("translate_endpoint","");
    }
    static void saveEndpoint(Context c,String endpoint){
        String v=endpoint.trim().replaceAll("/+$","");
        if(!v.isEmpty()&&!v.startsWith("https://"))throw new IllegalArgumentException("Use HTTPS Worker URL");
        c.getSharedPreferences("kp-recap-settings",Context.MODE_PRIVATE).edit().putString("translate_endpoint",v).apply();
    }
    static void translateText(Context context,String original,String chosenLanguage,Callback callback){
        if(original==null||original.trim().isEmpty()){callback.error("No text to translate");return;}
        String server=endpoint(context);
        if(server.isEmpty()){callback.error("Cloudflare translation Worker is not configured. Connect/deploy backend first.");return;}
        identify(original,chosenLanguage,lang->{
            new Thread(()->{
                try {
                    StringBuilder translated=new StringBuilder();
                    List<String> parts=split(original,430);
                    for(int i=0;i<parts.size();i++){
                        progress(callback,"Translating "+(i+1)+"/"+parts.size());
                        translated.append(call(server,parts.get(i),lang));
                        if(i<parts.size()-1)translated.append("\n");
                    }
                    MAIN.post(()->callback.finished(translated.toString(),""));
                }catch(Exception e){MAIN.post(()->callback.error(e.getMessage()));}
            },"kp-myanmar-translation").start();
        },callback);
    }
    static void translateSrt(Context context,String srt,String chosenLanguage,Callback callback){
        List<MotionOverlay.Cue> cues=MotionOverlay.parseSrt(srt);
        if(cues.isEmpty()){callback.error("No timed SRT segments available");return;}
        String server=endpoint(context);
        if(server.isEmpty()){callback.error("Cloudflare translation Worker not configured");return;}
        String sample= srt.replaceAll("(?m)^\\d+$","").replaceAll("(?m)^.*-->.*$","");
        identify(sample,chosenLanguage,lang->{
            new Thread(()->{
                try{
                    StringBuilder newSrt=new StringBuilder(),combined=new StringBuilder();
                    for(int i=0;i<cues.size();i++){
                        MotionOverlay.Cue cue=cues.get(i);
                        progress(callback,"Myanmar subtitle "+(i+1)+"/"+cues.size());
                        StringBuilder tr=new StringBuilder();
                        List<String> parts=split(cue.text,400);
                        for(String part:parts){
                            if(tr.length()>0)tr.append(' ');
                            tr.append(call(server,part,lang));
                        }
                        newSrt.append(i+1).append('\n')
                            .append(timestamp(cue.startUs/1000)).append(" --> ")
                            .append(timestamp(cue.endUs/1000)).append('\n')
                            .append(tr).append("\n\n");
                        if(combined.length()>0)combined.append(' ');
                        combined.append(tr);
                    }
                    MAIN.post(()->callback.finished(combined.toString(),newSrt.toString()));
                }catch(Exception e){MAIN.post(()->callback.error(e.getMessage()));}
            },"kp-srt-translation").start();
        },callback);
    }
    interface LangCallback {void ready(String code);}
    private static void identify(String text,String requested,LangCallback go,Callback callback){
        if(requested!=null&&!requested.equalsIgnoreCase("auto")){
            String code=requested.toLowerCase(Locale.ROOT).trim();
            if(!code.matches("[a-z]{2,3}")){callback.error("Enter language code such as en, th, ja or auto");return;}
            go.ready(code);return;
        }
        LanguageIdentifier identifier=LanguageIdentification.getClient();
        String sample=text.substring(0,Math.min(text.length(),350));
        identifier.identifyLanguage(sample)
            .addOnSuccessListener(code->{
                identifier.close();
                if(code==null||code.equals("und"))callback.error("Language detection uncertain. Enter source language code manually.");
                else go.ready(code);
            })
            .addOnFailureListener(error->{identifier.close();callback.error("Language detection failed: "+error.getMessage());});
    }
    private static String call(String server,String snippet,String lang)throws Exception {
        URL url=new URL(server+"/translate");
        HttpURLConnection con=(HttpURLConnection)url.openConnection();
        try{
            con.setConnectTimeout(20000);con.setReadTimeout(90000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type","application/json; charset=utf-8");
            JSONObject payload=new JSONObject()
                .put("text",snippet)
                .put("source_lang",lang)
                .put("target_lang","my");
            byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);
            if(body.length>2500)throw new IllegalArgumentException("Translation segment too large");
            try(OutputStream out=con.getOutputStream()){out.write(body);}
            int status=con.getResponseCode();
            try(InputStream stream=status>=200&&status<300?con.getInputStream():con.getErrorStream()){
                if(stream==null)throw new Exception("Translation server HTTP "+status);
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                byte[] buf=new byte[4096];int n;
                while((n=stream.read(buf))!=-1){
                    if(output.size()+n>100_000)throw new Exception("Translation response too large");
                    output.write(buf,0,n);
                }
                JSONObject result=new JSONObject(output.toString("UTF-8"));
                if(status<200||status>=300)throw new Exception("Translation error HTTP "+status+": "+result.optString("error"));
                String text=result.optString("translated_text","").trim();
                if(text.isEmpty())throw new Exception("Translation response was empty");
                return text;
            }
        }finally{con.disconnect();}
    }
    private static List<String> split(String s,int max){
        ArrayList<String> parts=new ArrayList<>();
        int pos=0;
        while(pos<s.length()){
            int end=Math.min(s.length(),pos+max);
            if(end<s.length()){
                int space=s.lastIndexOf(' ',end);
                if(space>pos+max/2)end=space+1;
            }
            String part=s.substring(pos,end).trim();
            if(!part.isEmpty())parts.add(part);
            pos=end;
        }
        return parts;
    }
    private static String timestamp(long t){
        return String.format(Locale.US,"%02d:%02d:%02d,%03d",
           t/3600000,(t/60000)%60,(t/1000)%60,t%1000);
    }
    private static void progress(Callback cb,String s){MAIN.post(()->cb.update(s));}
}
