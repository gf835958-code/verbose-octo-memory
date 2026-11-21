package com.kp.recapstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Offline OCR for scanned PDFs, with on-demand open Tesseract language packs. */
final class PdfOcrEngine {
    interface Callback {
        void message(String text);
        void ready(String text);
        void failed(String problem);
    }
    private PdfOcrEngine(){}
    static File langFile(Context c,String lang){
        File root=new File(c.getFilesDir(),"ocr/tessdata");
        if(!root.exists())root.mkdirs();
        return new File(root,lang+".traineddata");
    }
    static boolean installed(Context c,String lang){
        File f=langFile(c,lang);return f.isFile()&&f.length()>200_000;
    }
    private static String valid(String text){
        String code=text.trim().toLowerCase(java.util.Locale.ROOT);
        if(!code.matches("[a-z_]{3,10}"))throw new IllegalArgumentException("Use OCR code eng, mya, tha, jpn, chi_sim...");
        return code;
    }
    static void downloadLanguage(Context c,String language,Callback cb){
        String lang;
        try{lang=valid(language);}catch(Exception e){cb.failed(e.getMessage());return;}
        new Thread(()->{
            File dest=langFile(c,lang),part=new File(dest+".partial");
            HttpURLConnection con=null;
            try{
                con=(HttpURLConnection)new URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"+lang+".traineddata").openConnection();
                con.setConnectTimeout(30000);con.setReadTimeout(60000);
                int response=con.getResponseCode();
                if(response!=200)throw new Exception("OCR language not found (HTTP "+response+")");
                try(InputStream input=con.getInputStream();FileOutputStream out=new FileOutputStream(part)){
                    byte[] buf=new byte[65536];long total=0;int n;
                    while((n=input.read(buf))!=-1){
                        total+=n;if(total>50_000_000)throw new Exception("OCR model too large");
                        out.write(buf,0,n);
                    }
                }
                if(part.length()<200_000)throw new Exception("OCR model appears incomplete");
                if(dest.exists()&&!dest.delete())throw new Exception("Cannot overwrite OCR data");
                if(!part.renameTo(dest))throw new Exception("Could not finalize OCR language data");
                cb.message("Offline "+lang+" OCR ready: "+dest.length()/1048576+" MB");
            }catch(Exception e){part.delete();cb.failed(e.getMessage());}
            finally{if(con!=null)con.disconnect();}
        },"kp-ocr-download").start();
    }
    static void readScannedPdf(Context c,Uri fileUri,String language,Callback cb){
        String lang;
        try{lang=valid(language);}catch(Exception e){cb.failed(e.getMessage());return;}
        if(!installed(c,lang)){cb.failed("OCR language data missing. Download "+lang+" model first.");return;}
        new Thread(()->{
            File copy=new File(c.getCacheDir(),"kp-ocr-input-"+System.nanoTime()+".pdf");
            TessBaseAPI api=null;
            try {
                try(InputStream in=c.getContentResolver().openInputStream(fileUri);
                    FileOutputStream out=new FileOutputStream(copy)){
                    if(in==null)throw new Exception("Cannot open PDF");
                    byte[] buf=new byte[65536];int n;long size=0;
                    while((n=in.read(buf))!=-1){
                        size+=n;
                        if(size>250_000_000)throw new Exception("PDF bigger than 250 MB");
                        out.write(buf,0,n);
                    }
                }
                api=new TessBaseAPI();
                String base=new File(c.getFilesDir(),"ocr").getAbsolutePath();
                if(!api.init(base,lang))throw new Exception("Tesseract failed to initialize for "+lang);
                StringBuilder found=new StringBuilder();
                try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(copy,ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer pdf=new PdfRenderer(fd)){
                    int pages=pdf.getPageCount();
                    if(pages>200)throw new Exception("OCR batch limit: 200 pages per run");
                    for(int i=0;i<pages;i++){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException("OCR cancelled");
                        cb.message("Reading scanned PDF page "+(i+1)+"/"+pages);
                        try(PdfRenderer.Page page=pdf.openPage(i)){
                            float scale=Math.min(1.8f,2000f/Math.max(page.getWidth(),page.getHeight()));
                            int w=Math.max(1,Math.round(page.getWidth()*scale));
                            int h=Math.max(1,Math.round(page.getHeight()*scale));
                            Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                            try{
                                bitmap.eraseColor(android.graphics.Color.WHITE);
                                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                api.setImage(bitmap);
                                String text=api.getUTF8Text();
                                if(text!=null&&!text.trim().isEmpty()){
                                    found.append("\n\n--- Page ").append(i+1).append(" ---\n").append(text);
                                }
                                api.clear();
                            }finally{bitmap.recycle();}
                        }
                    }
                }
                if(found.length()==0)throw new Exception("No readable text detected from scanned pages");
                cb.ready(found.toString());
            }catch(Exception e){cb.failed(e.getMessage());}
            finally{if(api!=null)api.recycle();copy.delete();}
        },"kp-pdf-ocr").start();
    }
}
