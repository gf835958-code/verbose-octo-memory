package com.kp.recapstudio;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** PDF studio: extract selectable text, author/edit Burmese, export PDF and audiobook WAV.
 *  Auto-translation and OCR for scanned PDFs are NOT supplied by this class.
 */
public final class PdfStudioActivity extends Activity {
    private static final int PICK_PDF=301, SAVE_PDF=302;
    private static final int BG=0xFF080E21, PANEL=0xFF172242, CYAN=0xFF54E8D9;
    private EditText sourceText, myanmarText, titleText;
    private TextView status;
    private Uri pdfUri;
    private TextToSpeech tts;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile boolean processing=false;
    private int voiceIndex;
    private List<String> voiceChunks;
    private ArrayList<File> voiceParts;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout col=new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(22,24,22,48);
        scroll.addView(col);setContentView(scroll);
        col.addView(label("‹    KP RECAP • PDF STUDIO",23,Color.WHITE));
        col.addView(label("PDF → Text → Myanmar PDF / Audiobook",15,CYAN));
        col.addView(label("Selectable-text PDFs only. Scanned images need OCR. Automatic translation is not available in this build.",13,0xFFE1D08D));
        button(col,"1. SELECT PDF BOOK",()->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            startActivityForResult(intent,PICK_PDF);
        });
        col.addView(label("Book title",14,CYAN));
        titleText=input("စာအုပ်အမည် / Book title",1);col.addView(titleText);
        col.addView(label("Extracted original text (editable)",14,CYAN));
        sourceText=input("Choose a PDF to extract readable text",7);col.addView(sourceText);
        button(col,"COPY SOURCE TEXT TO TRANSLATION APP",()->{
            String text=sourceText.getText().toString().trim();
            if(text.isEmpty()){show("Import a PDF first");return;}
            Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT,text.length()>18000?text.substring(0,18000):text);
            startActivity(Intent.createChooser(send,"Translate with an app you choose"));
        });
        col.addView(label("Myanmar text • edit or paste translated text below",14,CYAN));
        myanmarText=input("မြန်မာစာကို ဒီနေရာမှာ ရေးပါ၊ ကူးထည့်ပါ။",9);col.addView(myanmarText);
        button(col,"2. EXPORT MYANMAR PDF",()->{
            if(myanmarText.getText().toString().trim().isEmpty()){show("Add Myanmar text before PDF export.");return;}
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_TITLE,"KP_Myanmar_Book.pdf");
            startActivityForResult(i,SAVE_PDF);
        });
        button(col,"3. GENERATE AUDIOBOOK (.WAV)",this::createAudiobook);
        button(col,"OPEN AUDIOBOOK VIDEO STUDIO",()->{
            Intent i=new Intent(this,MainActivity.class);
            i.putExtra("open_mode","audiobook");
            startActivity(i);
        });
        status=label("Ready • No PDF imported",13,CYAN);col.addView(status);
        col.addView(label("Free / No login. TTS requires a device voice for the chosen language. Large documents are processed in speech chunks.",12,0xFFB9CAEA));
        tts=new TextToSpeech(this,code->{
            if(code!=TextToSpeech.SUCCESS)show("System TTS is unavailable.");
        });
    }

    private TextView label(String text,int size,int color){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);
        v.setPadding(8,11,8,11);return v;
    }
    private EditText input(String hint,int minLines){
        EditText e=new EditText(this);
        e.setHint(hint);e.setHintTextColor(0xFF91A4C6);e.setTextColor(Color.WHITE);
        e.setTextSize(14);e.setMinLines(minLines);e.setGravity(Gravity.TOP);
        e.setPadding(16,16,16,16);e.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,8,0,12);e.setLayoutParams(p);return e;
    }
    private void button(LinearLayout col,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF42339B));
        col.addView(b);b.setOnClickListener(v->action.run());
    }
    private void show(String s){ui.post(()->{if(status!=null)status.setText(s);});}

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        if(req==PICK_PDF){
            pdfUri=data.getData();
            show("Extracting PDF text...");
            new Thread(()->extractText(pdfUri)).start();
        }else if(req==SAVE_PDF){
            String text=myanmarText.getText().toString(),title=titleText.getText().toString();
            Uri destination=data.getData();
            new Thread(()->{
                try(OutputStream out=getContentResolver().openOutputStream(destination)){
                    if(out==null)throw new IOException("No output stream");
                    writePdf(text,title,out);
                    show("Success: PDF exported. Layout is reconstructed from text, not a copy of the original.");
                }catch(Exception e){show("PDF export error: "+e.getMessage());}
            }).start();
        }
    }

    private void extractText(Uri file){
        try(InputStream input=getContentResolver().openInputStream(file)){
            if(input==null)throw new IOException("Cannot open PDF file");
            try(PDDocument doc=PDDocument.load(input)){
                String raw=new PDFTextStripper().getText(doc);
                if(raw.trim().isEmpty())throw new IOException("No selectable text: scanned PDFs require OCR.");
                final String txt=raw.length()>180000?raw.substring(0,180000):raw;
                final int pages=doc.getNumberOfPages();
                ui.post(()->{
                    sourceText.setText(txt);
                    show("Extracted "+pages+" pages / "+txt.length()+" chars. Review extraction, then paste/edit Myanmar translation.");
                });
            }
        }catch(Exception e){show("PDF text extraction failed: "+e.getMessage());}
    }

    private static void writePdf(String content,String title,OutputStream output) throws IOException {
        PdfDocument doc=new PdfDocument();
        try{
            String full=(title.isEmpty()?"":title+"\n\n")+content;
            TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);paint.setTextSize(16);paint.setTypeface(android.graphics.Typeface.DEFAULT);
            int offset=0,pageNumber=1;
            while(offset<full.length() && pageNumber<=500){
                StaticLayout measure=StaticLayout.Builder.obtain(full,offset,full.length(),paint,515)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
                int lines=Math.max(1,measure.getLineForVertical(728));
                int end=Math.max(offset+1,measure.getLineEnd(Math.min(lines,measure.getLineCount()-1)));
                end=Math.min(end,full.length());
                String part=full.substring(offset,end);
                PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(595,842,pageNumber).create());
                Canvas c=page.getCanvas();c.drawColor(Color.WHITE);
                c.save();c.translate(40,49);
                StaticLayout draw=StaticLayout.Builder.obtain(part,0,part.length(),paint,515)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
                draw.draw(c);c.restore();
                Paint footer=new Paint(Paint.ANTI_ALIAS_FLAG);footer.setColor(0xFF777777);footer.setTextSize(10);
                c.drawText("KP Recap Studio • "+pageNumber,40,816,footer);
                doc.finishPage(page);
                offset=end;pageNumber++;
            }
            if(offset<full.length())throw new IOException("Book exceeds 500 output pages.");
            doc.writeTo(output);
        }finally{doc.close();}
    }

    private void createAudiobook(){
        if(processing){show("Audio generation is already running.");return;}
        if(tts==null){show("TTS unavailable");return;}
        String text=myanmarText.getText().toString().trim();
        if(text.isEmpty())text=sourceText.getText().toString().trim();
        if(text.isEmpty()){show("Import PDF or type content.");return;}
        Locale locale=myanmarText.getText().toString().trim().isEmpty()?Locale.ENGLISH:new Locale("my","MM");
        int code=tts.setLanguage(locale);
        if(code==TextToSpeech.LANG_NOT_SUPPORTED || code==TextToSpeech.LANG_MISSING_DATA){
            show("Voice "+locale+" not installed in Android TTS settings.");return;
        }
        voiceChunks=split(text,2300);
        if(voiceChunks.size()>200){show("Audio text too long. Limit: 200 speech chunks.");return;}
        voiceParts=new ArrayList<>();voiceIndex=0;processing=true;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){show("Reading chapter "+(voiceIndex+1)+"/"+voiceChunks.size());}
            @Override public void onDone(String id){ui.post(()->nextSpeech());}
            @Override public void onError(String id){ui.post(()->{processing=false;show("Speech engine failed on chunk "+(voiceIndex+1));});}
        });
        nextSpeech();
    }

    private static ArrayList<String> split(String input,int cap){
        ArrayList<String> out=new ArrayList<>();
        for(int p=0;p<input.length();){
            int end=Math.min(p+cap,input.length());
            if(end<input.length()){
                int space=Math.max(input.lastIndexOf('\n',end),input.lastIndexOf(' ',end));
                if(space>p+cap/2)end=space+1;
            }
            out.add(input.substring(p,end));p=end;
        }
        return out;
    }

    private void nextSpeech(){
        if(!processing)return;
        if(voiceIndex>=voiceChunks.size()){
            show("Combining speech chunks...");
            processing=false;
            new Thread(()->{
                try{
                    File out=new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC),"KP_Audiobook_"+System.currentTimeMillis()+".wav");
                    WavAssembler.merge(voiceParts,out);
                    Uri published=saveAudio(out);
                    show("Audiobook ready: "+out.length()/1024+" KB WAV saved to Music/KP Recap Studio");
                    ui.post(()->{
                        Intent i=new Intent(this,MainActivity.class);
                        i.putExtra("open_mode","audiobook");
                        i.setData(published);
                        // User may open studio from the button; no unexpected navigation.
                    });
                }catch(Exception e){show("Could not merge audio: "+e.getMessage());}
                finally{for(File f:voiceParts)f.delete();}
            }).start();return;
        }
        File parent=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        File f=new File(parent,"kp_voice_part_"+System.currentTimeMillis()+"_"+voiceIndex+".wav");
        voiceParts.add(f);
        String utteranceId="KP_"+voiceIndex;
        int result=tts.synthesizeToFile(voiceChunks.get(voiceIndex),new Bundle(),f,utteranceId);
        if(result!=TextToSpeech.SUCCESS){processing=false;show("TTS request was rejected.");return;}
        voiceIndex++;
    }

    private Uri saveAudio(File file)throws Exception{
        if(Build.VERSION.SDK_INT<29)return Uri.fromFile(file);
        ContentValues values=new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME,file.getName());
        values.put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,Environment.DIRECTORY_MUSIC+"/KP Recap Studio");
        values.put(MediaStore.Audio.Media.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IOException("Cannot create MediaStore audio");
        try(InputStream input=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null)throw new IOException("Audio output unavailable");
            byte[] buf=new byte[65536];int n;
            while((n=input.read(buf))!=-1)out.write(buf,0,n);
        }catch(Exception e){getContentResolver().delete(uri,null,null);throw e;}
        values.clear();values.put(MediaStore.Audio.Media.IS_PENDING,0);
        getContentResolver().update(uri,values,null,null);
        return uri;
    }
    @Override protected void onDestroy(){
        processing=false;
        if(tts!=null){tts.stop();tts.shutdown();}
        super.onDestroy();
    }
}
