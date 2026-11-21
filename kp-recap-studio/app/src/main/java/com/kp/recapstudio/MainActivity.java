package com.kp.recapstudio;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.effect.Presentation;
import androidx.media3.common.Effect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * KP Recap Studio: locally-editable, original Android media studio prototype.
 * The demo does NOT include an automatic Myanmar translation model or FFmpeg.
 */
@UnstableApi
public final class MainActivity extends Activity {
    private static final int PICK_MEDIA=101, PICK_IMAGE=102, PICK_SRT=103, VOICE_DICTATION=104, PICK_WHISPER_MODEL=105, SAVE_SRT=106;
    private static final int VIDEO=0, AUDIOBOOK=1, DHAMMA=2;
    private static final int BG=0xFF080E21, PANEL=0xFF172242, CYAN=0xFF45E5D4, WHITE=Color.WHITE, MUTED=0xFFABBDDB;
    private int mode=VIDEO;
    private Uri selectedMedia, selectedImage, lastSavedUri;
    private String selectedFileName="", srtText="";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private TextToSpeech tts;
    private EditText titleInput, subtitleInput, startSecondsInput, endSecondsInput, langInput;
    private TextView statusText, fileText, srtStatus;
    private ProgressBar progress;
    private Button exportButton;
    private File narrationFile;
    private boolean autoMyanmarFlow=false;
    private boolean ttsWorking=false;
    private List<String> speechChunks;
    private List<File> speechParts;
    private int speechIndex=0;
    private CheckBox useVoiceoverCheck;
    private Spinner ratioSpinner;
    private CheckBox cropCheck;
    private LinearLayout body;
    private volatile boolean exporting=false;
    private File exportFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildScreen();
        if("audiobook".equals(getIntent().getStringExtra("open_mode"))){
            openEditor(1);
            Uri incoming=getIntent().getData();
            if(incoming!=null){
                selectedMedia=incoming;
                selectedFileName="KP PDF Audiobook voice.wav";
                root();
            }
        }
        tts=new TextToSpeech(this, result -> {
            if(result!=TextToSpeech.SUCCESS) runOnUiThread(()->toast("System TTS unavailable on this phone"));
        });
    }

    // Real Android UI. Promotional references are not screenshots of this APK.
    private int screen=0;  // 0 dashboard, 1 editor, 2 projects, 3 tools, 4 settings
    private android.widget.ImageView mediaPreview;
    private TextView previewHint;
    private int selectedScene=0;
    private TextView[] presetButtons;

    private android.graphics.drawable.GradientDrawable gradient(int start,int end,int stroke,int radius){
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{start,end});
        d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);
        return d;
    }
    private LinearLayout column(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=label(value,size,color,bold);t.setLineSpacing(dp(2),1f);return t;
    }
    private void pad(LinearLayout parent,int height){
        parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));
    }
    private void root(){
        LinearLayout shell=column();shell.setBackgroundColor(BG);setContentView(shell);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        body=column();body.setPadding(dp(16),dp(15),dp(16),dp(25));scroll.addView(body);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(screen==0)home();else if(screen==1)editor();else if(screen==2)projects();
        else if(screen==3)toolsPage();else settingsPage();
        LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);
        nav.setBackground(gradient(0xFF111D39,0xFF0C1530,0xFF2A3D64,0));
        String[] texts={"⌂\nHome","▣\nProjects","✦\nTools","⚙\nSettings"};
        for(int i=0;i<4;i++){
            final int page=i==0?0:i+1;
            TextView n=text(texts[i],12,screen==page || screen==1&&i==0?0xFFB889FF:MUTED,true);
            n.setGravity(Gravity.CENTER);n.setOnClickListener(v->go(page));
            nav.addView(n,new LinearLayout.LayoutParams(0,dp(65),1));
        }
        shell.addView(nav,new LinearLayout.LayoutParams(-1,dp(67)));
    }
    private void go(int page){
        if(exporting&&page!=1){toast("Render လုပ်နေစဉ် Editor မှ မထွက်ပါနှင့်။");return;}
        screen=page;root();
    }
    private void buildScreen(){screen=0;root();}
    private void header(String title,boolean isHome){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        if(isHome){
            android.widget.ImageView icon=new android.widget.ImageView(this);
            icon.setImageResource(com.kp.recapstudio.R.mipmap.ic_launcher);
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            row.addView(icon,new LinearLayout.LayoutParams(dp(47),dp(47)));
        }else{
            TextView back=text("‹",31,WHITE,true);back.setGravity(Gravity.CENTER);
            back.setOnClickListener(v->go(0));row.addView(back,new LinearLayout.LayoutParams(dp(47),dp(47)));
        }
        LinearLayout heading=column();heading.addView(text(title,18,WHITE,true));
        heading.addView(text(isHome?"AI Video & Audiobook Creator":"CREATE  •  EDIT  •  EXPORT",10,CYAN,false));
        LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(0,-2,1);hl.leftMargin=dp(9);row.addView(heading,hl);
        TextView settings=text("⚙",22,MUTED,true);settings.setGravity(Gravity.CENTER);
        settings.setOnClickListener(v->go(4));row.addView(settings,new LinearLayout.LayoutParams(dp(40),dp(45)));
        body.addView(row);pad(body,19);
    }
    private void title(String title,String detail){
        body.addView(text(title,22,WHITE,true));pad(body,6);
        body.addView(text(detail,12,MUTED,false));pad(body,13);
    }
    private LinearLayout panel(String title,String desc){
        LinearLayout b=column();b.setPadding(dp(15),dp(15),dp(15),dp(13));
        b.setBackground(gradient(0xFF1B2B52,0xFF111A34,0xFF324E7D,17));
        if(title!=null){b.addView(text(title,15,WHITE,true));pad(b,5);}
        if(desc!=null){b.addView(text(desc,12,MUTED,false));pad(b,12);}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(13);
        body.addView(b,lp);return b;
    }
    private TextView styled(String text,boolean primary,Runnable task){
        TextView v=text(text,14,WHITE,true);v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9),dp(13),dp(9),dp(13));
        v.setBackground(primary?
            gradient(0xFF0DBAF1,0xFF8532E5,0,14):
            gradient(0xFF263C67,0xFF1D2B51,0xFF405D88,13));
        v.setOnClickListener(w->task.run());return v;
    }
    private void action(LinearLayout parent,String txt,boolean primary,Runnable task){
        TextView v=styled(txt,primary,task);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(10);parent.addView(v,lp);
    }
    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;
    }
    private void tile(LinearLayout parent,String icon,String name,String hint,int color,Runnable task){
        LinearLayout card=column();card.setPadding(dp(13),dp(14),dp(7),dp(13));
        card.setMinimumHeight(dp(144));card.setBackground(gradient(color,0xFF19203D,0xFF405287,18));
        card.addView(text(icon,27,WHITE,true));pad(card,10);
        card.addView(text(name,15,WHITE,true));pad(card,5);
        card.addView(text(hint,11,0xFFC7D5EF,false));
        card.setOnClickListener(v->task.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
        if(parent.getChildCount()==0)lp.rightMargin=dp(6);else lp.leftMargin=dp(6);
        parent.addView(card,lp);
    }
    private void home(){
        header("KP RECAP STUDIO",true);
        LinearLayout hero=column();hero.setPadding(dp(17),dp(20),dp(17),dp(17));
        hero.setBackground(gradient(0xFF39228F,0xFF056CB2,0xFF686DDE,22));
        hero.addView(text("✦  YOUR CREATIVE STUDIO",11,0xFF69FFF0,true));
        pad(hero,12);hero.addView(text("Create stories with video.",22,WHITE,true));
        pad(hero,6);hero.addView(text("Recap • မြန်မာစာတန်းထိုး • Audiobook",12,0xFFDDEBFF,false));
        pad(hero,15);action(hero,"▶  START VIDEO EDITING",true,()->openEditor(0));
        body.addView(hero);pad(body,24);
        body.addView(text("CREATE",12,CYAN,true));pad(body,11);
        LinearLayout a=row();
        tile(a,"▣","Video Recap","Manual SRT • MP4",0xFF0A5CA9,()->openEditor(0));
        tile(a,"♫","Audiobook Studio","Audio + stage",0xFF7130A6,()->openEditor(1));
        pad(body,12);
        LinearLayout b=row();
        tile(b,"✿","2D Dhamma Stage","50 audience figures",0xFF986124,()->openEditor(2));
        tile(b,"▤","My Projects","Saved MP4 exports",0xFF0A736E,()->go(2));
        pad(body,23);body.addView(text("STUDIO TOOLS",12,CYAN,true));pad(body,11);
        LinearLayout c=row();
        tile(c,"CC","Subtitle Tools","Timed captions",0xFF30579A,()->openEditor(0));
        tile(c,"◷","Export History","Watch saved videos",0xFF613E94,()->go(2));
        pad(body,12);
        LinearLayout pdf=row();
        tile(pdf,"▤","PDF Book Studio","PDF → Myanmar PDF",0xFF1B6287,this::openPdfStudio);
        tile(pdf,"♫","PDF Audiobook","PDF → Voice WAV",0xFF7242A0,this::openPdfStudio);
        pad(body,16);
        LinearLayout note=panel("TEST VERSION", "Offline-first manual editor. Automatic AI translation and smart dubbing are not available in this build.");
        note.addView(text("No account • No subscription • Android 8+",12,CYAN,false));
    }
    private void openEditor(int newMode){
        if(mode!=newMode){selectedMedia=null;selectedImage=null;selectedFileName="";srtText="";selectedScene=0;}
        mode=newMode;screen=1;root();
    }
    private void preview(){
        FrameLayout frame=new FrameLayout(this);
        frame.setBackground(gradient(0xFF202B56,0xFF0B152C,0xFF537EAB,20));
        frame.setClipToOutline(true);
        mediaPreview=new android.widget.ImageView(this);
        mediaPreview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        frame.addView(mediaPreview,new FrameLayout.LayoutParams(-1,-1));
        previewHint=text(selectedMedia==null?(mode==0?"▣\nTap to add video":"♫\nTap to add audio"):"▶  PLAY PREVIEW",17,WHITE,true);
        previewHint.setGravity(Gravity.CENTER);
        frame.addView(previewHint,new FrameLayout.LayoutParams(-1,-1));
        body.addView(frame,new LinearLayout.LayoutParams(-1,dp(200)));
        frame.setOnClickListener(v->{if(selectedMedia!=null)previewInput();else pick(PICK_MEDIA,mode==0?"video/*":"audio/*");});
        refreshPreview();
    }
    private void refreshPreview(){
        if(mediaPreview==null)return;
        if(mode!=0){
            try{
                File image=StageArt.create(this,mode==2,selectedImage,"","",selectedScene);
                android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inSampleSize=2;
                mediaPreview.setImageBitmap(android.graphics.BitmapFactory.decodeFile(image.getAbsolutePath(),o));
            }catch(Exception ignored){}
            return;
        }
        if(selectedMedia==null){mediaPreview.setImageDrawable(null);return;}
        Uri current=selectedMedia;android.widget.ImageView target=mediaPreview;
        new Thread(()->{
            android.media.MediaMetadataRetriever m=new android.media.MediaMetadataRetriever();
            android.graphics.Bitmap frame=null;
            try{m.setDataSource(this,current);frame=m.getFrameAtTime(0);}catch(Exception ignored){}
            finally{try{m.release();}catch(Exception ignored){}}
            android.graphics.Bitmap result=frame;
            runOnUiThread(()->{
                if(target==mediaPreview && current.equals(selectedMedia)){
                    target.setImageBitmap(result);
                    if(result!=null && previewHint!=null)previewHint.setText("▶");
                }
            });
        }).start();
    }
    private void editor(){
        header(mode==0?"AI Video Recap":mode==1?"Audiobook Studio":"2D Dhamma Stage",false);
        TextView steps=text("01   IMPORT       ›       02   EDIT       ›       03   EXPORT",11,0xFFE8E2FF,true);
        steps.setGravity(Gravity.CENTER);steps.setPadding(dp(7),dp(13),dp(7),dp(13));
        steps.setBackground(gradient(0xFF23245F,0xFF173154,0xFF54519A,12));
        body.addView(steps);pad(body,15);
        preview();pad(body,14);
        LinearLayout media=panel(mode==0?"VIDEO SOURCE":"AUDIO SOURCE","Import a file, then tap the preview above to play it.");
        action(media,mode==0?"▣  SELECT VIDEO":"♫  SELECT AUDIO",true,()->pick(PICK_MEDIA,mode==0?"video/*":"audio/*"));
        fileText=text(selectedMedia==null?"No file selected":selectedFileName,12,CYAN,false);
        media.addView(fileText);pad(media,7);
        if(mode!=0){
            action(media,"▤  CHOOSE STUDIO BACKGROUND",false,()->pick(PICK_IMAGE,"image/*"));
        }
        if(mode==0){
            LinearLayout ratio=panel("VIDEO SETTINGS","Choose ratio and Fit or Fill/Crop for your export.");
            ratioSpinner=new Spinner(this);
            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Original","9:16  Portrait","3:4  Portrait","16:9  Landscape","1:1  Square","4:5  Social"});
            ratioSpinner.setAdapter(adapter);ratioSpinner.setBackground(gradient(0xFF29406D,0xFF1C2B4F,0xFF46628D,11));
            gap(ratio,ratioSpinner,10);
            cropCheck=new CheckBox(this);cropCheck.setText("Fill / Crop   (unchecked = Fit)");
            cropCheck.setTextColor(WHITE);gap(ratio,cropCheck,12);
            ratio.addView(text("TRIM START / END (seconds)",11,CYAN,true));pad(ratio,8);
            startSecondsInput=edit("Start: 0",false);
            startSecondsInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
            gap(ratio,startSecondsInput,7);
            endSecondsInput=edit("End: full video",false);
            endSecondsInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
            gap(ratio,endSecondsInput,6);
        }else{
            LinearLayout studio=panel(mode==1?"AUDIOBOOK STUDIO":"DHAMMA STAGE","Live illustrated preview. Add your own background image if desired.");
            studio.addView(text(mode==1?"✦ Original narrator, desk & studio scene":"✿ Original stage, trees & 50 audience figures",13,CYAN,true));
            pad(studio,8);
            studio.addView(text("Choose a look for your exported video:",12,MUTED,false));
            pad(studio,9);
            LinearLayout presets=new LinearLayout(this);studio.addView(presets);
            String[] names=mode==1?new String[]{"Classic","Violet","Cyan","Royal"}:
                new String[]{"Forest","Sunset","Evening","Golden"};
            presetButtons=new TextView[4];
            for(int i=0;i<4;i++){
                final int selected=i;
                TextView b=text(names[i],11,WHITE,true);b.setGravity(Gravity.CENTER);
                b.setPadding(dp(2),dp(13),dp(2),dp(13));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
                lp.leftMargin=dp(3);lp.rightMargin=dp(3);
                presets.addView(b,lp);presetButtons[i]=b;
                b.setOnClickListener(v->{selectedScene=selected;paintPresetButtons();refreshPreview();});
            }
            paintPresetButtons();
            pad(studio,9);
            studio.addView(text("Waveform animation is decorative, not yet audio-reactive.",11,MUTED,false));
        }
        LinearLayout caption=panel("TITLE & SUBTITLES","Myanmar Unicode supported.");
        titleInput=edit("Title / ခေါင်းစဉ်",false);gap(caption,titleInput,9);
        subtitleInput=edit(mode==0?"မြန်မာစာတန်းထိုး / Myanmar subtitle":"Subtitle / Speaker name",true);
        gap(caption,subtitleInput,9);
        if(mode==0){
            action(caption,"CC  IMPORT TIMED SUBTITLES (.SRT)",false,()->pick(PICK_SRT,"*/*"));
            srtStatus=text(srtText.isEmpty()?"No SRT imported":"SRT loaded: "+MotionOverlay.parseSrt(srtText).size()+" captions",12,CYAN,false);
            gap(caption,srtStatus,8);
            action(caption,"★  ONE-CLICK MYANMAR VIDEO (BETA)",true,this::oneClickMyanmarVideo);
            action(caption,"◉  AUTO TRANSCRIBE VIDEO (OFFLINE WHISPER)",true,this::transcribeVideo);
            action(caption,"⬇  DOWNLOAD OFFLINE SPEECH MODEL (~75 MB)",false,this::confirmSpeechModelDownload);
            action(caption,"▤  IMPORT EXISTING WHISPER MODEL (.bin)",false,()->pick(PICK_WHISPER_MODEL,"*/*"));
            action(caption,"CC  EXPORT GENERATED SRT FILE",false,this::exportSrtFile);
            langInput=edit("Source language: auto (or en, th, ja, zh...)",false);
            langInput.setText("auto");gap(caption,langInput,7);
            action(caption,"🌐  TRANSLATE SRT → MYANMAR (ONLINE)",false,this::translateWhisperSrt);
            action(caption,"🎙  DICTATE SCRIPT",false,this::dictate);
            action(caption,"♫  GENERATE SYSTEM TTS FILE",false,this::synthesizeVoice);
            useVoiceoverCheck=new CheckBox(this);
            useVoiceoverCheck.setText("Replace video audio with the generated voice in MP4");
            useVoiceoverCheck.setTextColor(WHITE);gap(caption,useVoiceoverCheck,8);
            caption.addView(text("TTS depends on installed Android Myanmar voices. Generate a voice before enabling MP4 replacement.",11,MUTED,false));
        }
        LinearLayout exportingPanel=panel("RENDER / EXPORT","MP4 (H.264/AAC) → Movies/KP Recap Studio");
        exportButton=addButton(exportingPanel,"▶   RENDER & SAVE MP4",this::export);
        exportButton.setBackgroundTintList(ColorStateList.valueOf(0xFF7642E8));
        action(exportingPanel,"✕  CANCEL RENDER",false,()->{
            if(transformer!=null&&exporting){transformer.cancel();exporting=false;status("Render cancelled");exportButton.setEnabled(true);}
        });
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);gap(exportingPanel,progress,8);
        statusText=text("Ready • Select a file to begin.",12,CYAN,false);gap(exportingPanel,statusText,10);
        action(exportingPanel,"▣  OPEN LAST EXPORTED VIDEO",false,this::openOutput);
        action(exportingPanel,"▤  SAVE EDITABLE PROJECT",false,this::saveEditableProject);
        panel("FEATURE NOTICE","Offline Whisper recognition works after installing its ~75 MB multilingual model. Myanmar AI translation, PDF OCR and green screen still require additional engines. TTS video replacement needs installed Myanmar voice.");
    }
    private void paintPresetButtons(){
        if(presetButtons==null)return;
        for(int i=0;i<4;i++)presetButtons[i].setBackground(
            i==selectedScene?gradient(0xFF8044E8,0xFF167AD2,0xFF76EAE1,10):
                gradient(0xFF21355F,0xFF182648,0xFF40577E,10));
    }
    private void projects(){
        header("My Projects",false);
        title("Export History","Video files generated during this session.");
        LinearLayout saved=panel("SAVED PROJECTS","Tap a project to continue editing. Project data is local.");
        File dir=new File(getFilesDir(),"saved-projects");
        File[] list=dir.listFiles((folder,name)->name.endsWith(".json"));
        if(list==null||list.length==0)saved.addView(text("No projects saved yet.",12,MUTED,false));
        else{
            Arrays.sort(list,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            for(int i=0;i<Math.min(24,list.length);i++){
                File file=list[i];
                action(saved,"▤  "+file.getName(),false,()->loadEditableProject(file));
            }
        }
        LinearLayout recent=panel("LATEST MP4","Your last exported file, if present.");
        action(recent,"▶  OPEN LAST VIDEO",true,this::openOutput);
        LinearLayout info=panel("GALLERY LOCATION","Movies / KP Recap Studio");
        info.addView(text("Use your phone's Gallery or Files app to browse all exports.",12,MUTED,false));
    }
    private void openPdfStudio(){
        if(exporting){toast("Finish rendering first");return;}
        startActivity(new Intent(this,PdfStudioActivity.class));
    }
    private void toolsPage(){
        header("Studio Tools",false);title("Tools","Select a working editor.");
        LinearLayout tools=panel("WORKING TOOLS","Video editor, audio studio, SRT captions, MP4 export.");
        action(tools,"▣  VIDEO & SRT TOOLS",true,()->openEditor(0));
        action(tools,"♫  AUDIOBOOK VIDEO",false,()->openEditor(1));
        action(tools,"✿  DHAMMA STAGE",false,()->openEditor(2));
        LinearLayout backend=panel("MYANMAR TRANSLATION BACKEND",
          "Owner-hosted HTTPS Cloudflare Worker. Only transcript text is sent; a server connection is required. No user API key.");
        EditText address=edit("https://your-worker.workers.dev",false);
        address.setText(MyanmarTranslationClient.endpoint(this));
        gap(backend,address,8);
        action(backend,"SAVE TRANSLATION SERVER URL",true,()->{
            try {
                MyanmarTranslationClient.saveEndpoint(this,address.getText().toString());
                toast("Translation endpoint saved.");
            } catch(Exception e){toast(e.getMessage());}
        });
        LinearLayout pdf=panel("PDF BOOK TOOLS","Extract PDF text, edit Myanmar PDF, and create audiobook WAV.");
        action(pdf,"▤  OPEN PDF BOOK STUDIO",true,this::openPdfStudio);
        panel("COMING LATER","Automatic Myanmar AI translation, speech-to-text and chroma key.");
    }
    private void settingsPage(){
        header("App Settings",false);title("KP Recap Studio","Original Android video creation app");
        panel("VERSION","0.4 alpha • Local media • On-device Whisper • Android 8+");
        panel("READY TO TEST","Whisper offline ASR, timed subtitles, 2D animated audience, PDF OCR, studio presets, TTS, and MP4 export (device QA pending).");
        panel("NOT READY","Cloudflare Myanmar backend needs authenticated deployment. Scanned PDF OCR, synchronized dubbing, background rendering and chroma key remain incomplete.");
    }

    private void saveEditableProject(){
        if(selectedMedia==null){toast("Choose a video or audio file first");return;}
        try {
            org.json.JSONObject obj=new org.json.JSONObject();
            obj.put("mode",mode);
            obj.put("media",selectedMedia.toString());
            obj.put("image",selectedImage==null?"":selectedImage.toString());
            obj.put("name",selectedFileName);
            obj.put("scene",selectedScene);
            obj.put("title",titleInput==null?"":titleInput.getText().toString());
            obj.put("subtitle",subtitleInput==null?"":subtitleInput.getText().toString());
            obj.put("srt",srtText);
            obj.put("start",startSecondsInput==null?"":startSecondsInput.getText().toString());
            obj.put("end",endSecondsInput==null?"":endSecondsInput.getText().toString());
            obj.put("ratio",ratioSpinner==null?0:ratioSpinner.getSelectedItemPosition());
            obj.put("crop",cropCheck!=null&&cropCheck.isChecked());
            File dir=new File(getFilesDir(),"saved-projects");if(!dir.exists())dir.mkdirs();
            File item=new File(dir,"KP-Project-"+System.currentTimeMillis()+".json");
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(item)){
                out.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            status("Project saved locally. Reopen from Projects tab.");
        }catch(Exception e){status("Project save failed: "+e.getMessage());}
    }
    private void loadEditableProject(File item){
        try{
            byte[] bytes=java.nio.file.Files.readAllBytes(item.toPath());
            if(bytes.length>4_000_000)throw new IOException("Project too large");
            org.json.JSONObject obj=new org.json.JSONObject(new String(bytes,StandardCharsets.UTF_8));
            String media=obj.getString("media");
            selectedMedia=Uri.parse(media);
            String image=obj.optString("image","");
            selectedImage=image.isEmpty()?null:Uri.parse(image);
            selectedFileName=obj.optString("name","");
            mode=obj.optInt("mode",VIDEO);
            if(mode<VIDEO||mode>DHAMMA)mode=VIDEO;
            selectedScene=obj.optInt("scene",0);
            srtText=obj.optString("srt","");
            screen=1;root();
            if(titleInput!=null)titleInput.setText(obj.optString("title",""));
            if(subtitleInput!=null)subtitleInput.setText(obj.optString("subtitle",""));
            if(startSecondsInput!=null)startSecondsInput.setText(obj.optString("start",""));
            if(endSecondsInput!=null)endSecondsInput.setText(obj.optString("end",""));
            if(ratioSpinner!=null)ratioSpinner.setSelection(obj.optInt("ratio",0));
            if(cropCheck!=null)cropCheck.setChecked(obj.optBoolean("crop",false));
            if(srtStatus!=null)srtStatus.setText("Restored "+MotionOverlay.parseSrt(srtText).size()+" timed captions");
            status("Project restored. Confirm file is still accessible before rendering.");
        }catch(Exception e){toast("Cannot open project: "+e.getMessage());}
    }
    private void pick(int request,String mime){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        if(request==PICK_SRT) intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","application/x-subrip","application/octet-stream"});
        startActivityForResult(intent,request);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null) return;
        if(request==VOICE_DICTATION){
            ArrayList<String> values=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(values!=null&&!values.isEmpty()&&subtitleInput!=null)subtitleInput.setText(values.get(0));
            return;
        }
        Uri uri=data.getData();if(uri==null)return;
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        if(request==PICK_WHISPER_MODEL){
            status("Importing Whisper model...");
            SpeechModelStore.installFromFile(this,uri,new SpeechModelStore.Progress(){
                public void update(String value){runOnUiThread(()->status(value));}
                public void completed(File file){runOnUiThread(()->status("Offline Whisper ready: "+(file.length()/1048576)+" MB"));}
                public void failed(String error){runOnUiThread(()->status("Model import failed: "+error));}
            });
            return;
        }
        if(request==SAVE_SRT){
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IOException("Cannot write SRT");
                out.write(srtText.getBytes(StandardCharsets.UTF_8));
                status("SRT saved.");
            }catch(Exception e){status("SRT export error: "+e.getMessage());}
            return;
        }
        if(request==PICK_MEDIA){
            selectedMedia=uri;selectedFileName=uri.getLastPathSegment();
            if(fileText!=null)fileText.setText("Selected: "+selectedFileName);
            refreshPreview();
            status("Input selected. You can render now.");
        }else if(request==PICK_IMAGE){
            selectedImage=uri;status("Custom background selected.");refreshPreview();
        }else if(request==PICK_SRT){
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IOException("Unable to read SRT");
                byte[] buf=new byte[8192];int n;int total=0;
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                while((n=in.read(buf))>0){total+=n;if(total>2_000_000)throw new IOException("SRT exceeds 2 MB");out.write(buf,0,n);}
                srtText=out.toString("UTF-8");
                int count=MotionOverlay.parseSrt(srtText).size();
                if(srtStatus!=null)srtStatus.setText("SRT loaded: "+count+" timed captions");
                status("Imported "+count+" captions.");
            }catch(Exception e){status("SRT import failed: "+e.getMessage());}
        }
    }
    private void previewInput(){
        if(selectedMedia==null){toast("Select a file first");return;}
        try{
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(selectedMedia,mode==VIDEO?"video/*":"audio/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        }catch(Exception e){status("No player available: "+e.getMessage());}
    }
    private void dictate(){
        try{
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"my-MM");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak text for manual captions");
            startActivityForResult(intent,VOICE_DICTATION);
        }catch(Exception e){status("Speech recognition app not available");}
    }
    private void exportSrtFile(){
        if(srtText.trim().isEmpty()){status("Use Auto Transcribe or import SRT first.");return;}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE,"KP_Recap_Subtitles.srt");
        startActivityForResult(intent,SAVE_SRT);
    }
    private void oneClickMyanmarVideo(){
        if(selectedMedia==null){status("Choose a video first.");return;}
        if(autoMyanmarFlow||exporting||ttsWorking){status("Finish the current job first.");return;}
        if(!SpeechModelStore.ready(this)){status("Install the offline speech model first (~75 MB)");return;}
        if(MyanmarTranslationClient.endpoint(this).isEmpty()){
            status("Translation Worker is not connected. Configure HTTPS Worker URL in Tools.");return;
        }
        if(tts==null){status("Android TTS not ready.");return;}
        int voice=tts.setLanguage(new Locale("my","MM"));
        if(voice==TextToSpeech.LANG_NOT_SUPPORTED||voice==TextToSpeech.LANG_MISSING_DATA){
            status("No Burmese voice available. Install/select Myanmar TTS first.");return;
        }
        autoMyanmarFlow=true;
        status("One-click started: Offline ASR → Myanmar translation → TTS → MP4");
        transcribeVideo();
    }
    private void translateWhisperSrt(){
        if(srtText.trim().isEmpty()){
            status("Transcribe video with Whisper or import a timed SRT first.");return;
        }
        String lang=langInput==null?"auto":langInput.getText().toString().trim();
        status("Online Myanmar subtitle translation starting (internet needed)...");
        MyanmarTranslationClient.translateSrt(this,srtText,lang,new MyanmarTranslationClient.Callback(){
            @Override public void update(String message){status(message);}
            @Override public void finished(String translated,String newSrt){
                srtText=newSrt;
                if(subtitleInput!=null)subtitleInput.setText(translated);
                if(srtStatus!=null)srtStatus.setText("Myanmar translated: "+MotionOverlay.parseSrt(srtText).size()+" timed captions");
                if(autoMyanmarFlow){
                    status("Myanmar translation complete; generating voice...");
                    synthesizeVoice();
                }else status("Myanmar translated SRT ready. Review captions then Render MP4.");
            }
            @Override public void error(String error){autoMyanmarFlow=false;status("Translation failed: "+error);}
        });
    }
    private void confirmSpeechModelDownload(){
        if(SpeechModelStore.ready(this)){status("Whisper model already installed.");return;}
        new android.app.AlertDialog.Builder(this)
            .setTitle("Offline speech model download")
            .setMessage("Download approximately 75 MB of multilingual Whisper data? Mobile data may be charged. Download once, then speech recognition can run offline.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Download", (dialog,which)->SpeechModelStore.download(this,new SpeechModelStore.Progress(){
                public void update(String value){runOnUiThread(()->status(value));}
                public void completed(File file){runOnUiThread(()->status("Offline Whisper model ready: "+(file.length()/1048576)+" MB"));}
                public void failed(String error){runOnUiThread(()->status("Speech model download failed: "+error));}
            })).show();
    }
    private void transcribeVideo(){
        if(selectedMedia==null){status("Select a video first.");return;}
        if(exporting){status("Finish MP4 rendering first.");return;}
        if(!SpeechModelStore.ready(this)){status("Install Whisper model first using Download/Import button.");return;}
        final Uri media=selectedMedia;
        status("Decoding video audio for Whisper...");
        new Thread(()->{
            try{
                File wav=MediaAudioDecoder.decode(this,media,pct->{
                    if(pct%10==0)runOnUiThread(()->status("Extracting audio "+pct+"%"));
                });
                OfflineWhisper.transcribe(this,SpeechModelStore.model(this),wav,new OfflineWhisper.Callback(){
                    @Override public void onMessage(String msg){runOnUiThread(()->status(msg));}
                    @Override public void onResult(String transcript,String generatedSrt){
                        runOnUiThread(()->{
                            if(!media.equals(selectedMedia)){status("Input changed; previous ASR result ignored.");return;}
                            srtText=generatedSrt;
                            if(subtitleInput!=null)subtitleInput.setText(transcript);
                            if(srtStatus!=null)srtStatus.setText("Whisper recognized "+MotionOverlay.parseSrt(srtText).size()+" subtitle segments");
                            if(autoMyanmarFlow){
                                status("Speech recognized; translating to Myanmar...");
                                translateWhisperSrt();
                            }else status("Offline transcription done; review SRT timings before MP4 export.");
                        });
                    }
                    @Override public void onError(String error){runOnUiThread(()->{autoMyanmarFlow=false;status("Whisper error: "+error);});}
                });
            }catch(Exception e){runOnUiThread(()->{autoMyanmarFlow=false;status("Could not decode video audio: "+e.getMessage());});}
        },"kp-asr-decode").start();
    }
    private static List<String> splitSpeech(String raw,int max){
        ArrayList<String> segments=new ArrayList<>();
        for(int start=0;start<raw.length();){
            int end=Math.min(raw.length(),start+max);
            if(end<raw.length()){
                int boundary=Math.max(raw.lastIndexOf(' ',end),raw.lastIndexOf('\n',end));
                if(boundary>start+max/2)end=boundary+1;
            }
            String part=raw.substring(start,end).trim();
            if(!part.isEmpty())segments.add(part);
            start=end;
        }
        return segments;
    }
    private void synthesizeVoice(){
        if(ttsWorking){status("Voice synthesis already running.");return;}
        if(tts==null){autoMyanmarFlow=false;toast("TTS not ready");return;}
        String content=subtitleInput.getText().toString().trim();
        if(content.isEmpty()){autoMyanmarFlow=false;toast("Type a script first");return;}
        int language=tts.setLanguage(new Locale("my","MM"));
        if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){
            autoMyanmarFlow=false;
            status("Burmese TTS not installed. Select a Myanmar voice in Android TTS settings.");return;
        }
        speechChunks=splitSpeech(content,2200);
        if(speechChunks.isEmpty()||speechChunks.size()>200){
            autoMyanmarFlow=false;status("Voice text too long or empty (max 200 chunks).");return;
        }
        speechParts=new ArrayList<>();
        speechIndex=0;ttsWorking=true;narrationFile=null;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){
                runOnUiThread(()->status("Voice chunk "+speechIndex+"/"+speechChunks.size()));
            }
            @Override public void onDone(String id){runOnUiThread(()->nextVoiceChunk());}
            @Override public void onError(String id){
                runOnUiThread(()->{ttsWorking=false;autoMyanmarFlow=false;status("Burmese voice synthesis failed");});
            }
        });
        nextVoiceChunk();
    }
    private void nextVoiceChunk(){
        if(!ttsWorking)return;
        if(speechIndex>=speechChunks.size()){
            status("Combining voice chunks into narration WAV...");
            final List<File> parts=new ArrayList<>(speechParts);
            new Thread(()->{
                try{
                    File home=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                    if(home==null)throw new IOException("Music directory unavailable");
                    File merged=new File(home,"kp-dub-"+System.currentTimeMillis()+".wav");
                    WavAssembler.merge(parts,merged);
                    runOnUiThread(()->{
                        narrationFile=merged;ttsWorking=false;
                        if(autoMyanmarFlow){
                            if(useVoiceoverCheck!=null)useVoiceoverCheck.setChecked(true);
                            status("Narration ready; rendering MP4...");
                            export();
                        }else status("Narration ready. Enable voice replacement and render MP4.");
                    });
                }catch(Exception e){
                    runOnUiThread(()->{ttsWorking=false;autoMyanmarFlow=false;status("Voice merge failed: "+e.getMessage());});
                }finally{for(File f:parts)f.delete();}
            },"kp-merge-voice").start();
            return;
        }
        File home=getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if(home==null){autoMyanmarFlow=false;ttsWorking=false;status("No voice output directory");return;}
        File part=new File(home,"kp-dub-part-"+System.nanoTime()+"-"+speechIndex+".wav");
        speechParts.add(part);
        String data=speechChunks.get(speechIndex);
        int index=speechIndex++;
        int result=tts.synthesizeToFile(data,new Bundle(),part,"KPVoice"+index);
        if(result!=TextToSpeech.SUCCESS){
            ttsWorking=false;autoMyanmarFlow=false;status("TTS rejected chunk "+(index+1));
        }
    }

    private void export(){
        if(exporting){toast("A render is already running");return;}
        if(selectedMedia==null){toast("Select a video or audio file first");return;}
        try{
            String title=titleInput.getText().toString().trim();
            String subtitle=subtitleInput.getText().toString().trim();
            String outName="KP_Recap_"+System.currentTimeMillis()+".mp4";
            File outDir=getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if(outDir==null)throw new IOException("No Movies storage directory");
            exportFile=new File(outDir,outName);
            transformer=new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build();
            transformer.addListener(new Transformer.Listener(){
                @Override public void onCompleted(Composition composition,ExportResult exportResult){
                    exporting=false;
                    status("Render complete; saving to Gallery...");
                    new Thread(()->{
                        try{
                            Uri published=publishVideo(exportFile);
                            runOnUiThread(()->{lastSavedUri=published;progress.setProgress(100);exportButton.setEnabled(true);autoMyanmarFlow=false;status("SUCCESS! MP4 saved to Movies/KP Recap Studio");});
                        }catch(Exception e){runOnUiThread(()->{exportButton.setEnabled(true);status("MP4 created in app storage, but Gallery copy failed: "+e.getMessage());});}
                    }).start();
                }
                @Override public void onError(Composition composition,ExportResult exportResult,ExportException exportException){
                    exporting=false;exportButton.setEnabled(true);
                    autoMyanmarFlow=false;status("Render failed: "+exportException.getMessage());
                }
            });
            exporting=true;exportButton.setEnabled(false);progress.setProgress(0);status("Starting render...");
            if(mode==VIDEO){
                MediaItem.Builder builder=new MediaItem.Builder().setUri(selectedMedia);
                long start=Math.max(0,(long)(parseFloat(startSecondsInput.getText().toString())*1000));
                long end=(long)(parseFloat(endSecondsInput.getText().toString())*1000);
                if(start>0||end>0){
                    MediaItem.ClippingConfiguration.Builder clip=new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start);
                    if(end>start)clip.setEndPositionMs(end);
                    builder.setClippingConfiguration(clip.build());
                }
                List<MotionOverlay.Cue> captions=MotionOverlay.parseSrt(srtText);
                MotionOverlay overlay=new MotionOverlay(true,subtitle,captions);
                List<Effect> videoEffects=new ArrayList<>();
                if(ratioSpinner!=null){
                    int selectedRatio=ratioSpinner.getSelectedItemPosition();
                    float[] ar={0f,9f/16f,3f/4f,16f/9f,1f,4f/5f};
                    if(selectedRatio>0 && selectedRatio<ar.length)
                        videoEffects.add(Presentation.createForAspectRatio(ar[selectedRatio],
                            cropCheck.isChecked()?Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP:Presentation.LAYOUT_SCALE_TO_FIT));
                }
                videoEffects.add(new OverlayEffect(Collections.singletonList(overlay)));
                boolean dubbing=useVoiceoverCheck!=null&&useVoiceoverCheck.isChecked();
                if(dubbing && (narrationFile==null||!narrationFile.isFile()))
                    throw new IOException("Generate TTS voice before enabling audio replacement.");
                EditedMediaItem.Builder media=new EditedMediaItem.Builder(builder.build())
                    .setEffects(new Effects(Collections.emptyList(),videoEffects));
                if(dubbing)media.setRemoveAudio(true);
                EditedMediaItem edited=media.build();
                if(dubbing){
                    EditedMediaItem narration=new EditedMediaItem.Builder(
                        MediaItem.fromUri(Uri.fromFile(narrationFile))).build();
                    Composition dubbed=new Composition.Builder(Arrays.asList(
                        EditedMediaItemSequence.withVideoFrom(Collections.singletonList(edited)),
                        EditedMediaItemSequence.withAudioFrom(Collections.singletonList(narration))))
                        .build();
                    transformer.start(dubbed,exportFile.getAbsolutePath());
                }else transformer.start(edited,exportFile.getAbsolutePath());
            }else{
                long duration=readDurationMs(selectedMedia);
                if(duration<=0)throw new IOException("Audio duration unavailable");
                File image=StageArt.create(this,mode==DHAMMA,selectedImage,title,subtitle,selectedScene);
                MediaItem visual=new MediaItem.Builder().setUri(Uri.fromFile(image)).setImageDurationMs(duration).build();
                List<TextureOverlay> animationLayers=new ArrayList<>();
                animationLayers.add(new MotionOverlay(false,"",null));
                if(mode==DHAMMA)animationLayers.add(new DhammaAudienceOverlay());
                EditedMediaItem editedImage=new EditedMediaItem.Builder(visual)
                    .setFrameRate(24)
                    .setEffects(new Effects(Collections.emptyList(),
                        Collections.singletonList(new OverlayEffect(animationLayers))))
                    .build();
                EditedMediaItem audio=new EditedMediaItem.Builder(MediaItem.fromUri(selectedMedia)).build();
                Composition composition=new Composition.Builder(Arrays.asList(
                    EditedMediaItemSequence.withVideoFrom(Collections.singletonList(editedImage)),
                    EditedMediaItemSequence.withAudioFrom(Collections.singletonList(audio))))
                    .build();
                transformer.start(composition,exportFile.getAbsolutePath());
            }
            pollProgress();
        }catch(Exception e){
            exporting=false;exportButton.setEnabled(true);
            status("Could not start render: "+e.getMessage());
        }
    }
    private void pollProgress(){
        handler.postDelayed(()->{
            if(!exporting||transformer==null)return;
            ProgressHolder holder=new ProgressHolder();
            try{
                if(transformer.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE){
                    progress.setProgress(holder.progress);
                    status("Rendering... "+holder.progress+"%");
                }
            }catch(Exception ignored){}
            if(exporting)pollProgress();
        },550);
    }
    private long readDurationMs(Uri uri) throws Exception {
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{retriever.setDataSource(this,uri);String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(duration==null)throw new IOException("No duration metadata");return Long.parseLong(duration);
        }finally{retriever.release();}
    }
    private Uri publishVideo(File file) throws Exception {
        if(android.os.Build.VERSION.SDK_INT>=29){
            ContentValues values=new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,file.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/KP Recap Studio");
            values.put(MediaStore.Video.Media.IS_PENDING,1);
            Uri uri=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new IOException("MediaStore insertion failed");
            try(OutputStream out=getContentResolver().openOutputStream(uri);FileInputStream in=new FileInputStream(file)){
                if(out==null)throw new IOException("MediaStore output stream failed");
                byte[] data=new byte[131072];int n;
                while((n=in.read(data))!=-1)out.write(data,0,n);
            }catch(Exception e){getContentResolver().delete(uri,null,null);throw e;}
            values.clear();values.put(MediaStore.Video.Media.IS_PENDING,0);
            getContentResolver().update(uri,values,null,null);
            return uri;
        }else{
            File movies=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File folder=new File(movies,"KP Recap Studio");folder.mkdirs();
            File copy=new File(folder,file.getName());
            try(InputStream in=new FileInputStream(file);OutputStream out=new java.io.FileOutputStream(copy)){
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);
            }
            MediaScannerConnection.scanFile(this,new String[]{copy.getAbsolutePath()},new String[]{"video/mp4"},null);
            return Uri.fromFile(copy);
        }
    }
    private void openOutput(){
        Uri uri=lastSavedUri;
        if(uri==null){toast("No export is ready yet");return;}
        try{Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4");view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(view);}catch(Exception e){status("Unable to open video: "+e.getMessage());}
    }
    private float parseFloat(String input){try{return Float.parseFloat(input.trim());}catch(Exception e){return 0;}}
    private void status(String s){if(statusText!=null)statusText.setText(s);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private int dp(int i){return (int)(i*getResources().getDisplayMetrics().density+.5f);}
    private static GradientDrawable background(int color,int stroke){
        GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(20);
        bg.setStroke(1,stroke);return bg;
    }
    private TextView label(String text,int sp,int color,boolean bold){
        TextView v=new TextView(this);v.setText(text);v.setTextColor(color);v.setTextSize(sp);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setGravity(Gravity.START);return v;
    }
    private EditText edit(String hint,boolean multiline){
        EditText e=new EditText(this);e.setHint(hint);e.setTextColor(WHITE);e.setHintTextColor(MUTED);
        e.setTextSize(14);e.setBackground(background(PANEL,0xFF325579));e.setPadding(dp(14),dp(9),dp(14),dp(9));
        if(multiline){e.setMinLines(2);e.setMaxLines(5);e.setGravity(Gravity.TOP|Gravity.START);}
        return e;
    }
    private Button addButton(LinearLayout parent,String text,Runnable action){
        Button b=new Button(this);b.setText(text);b.setTextColor(WHITE);b.setAllCaps(false);
        b.setTextSize(14);b.setBackgroundTintList(ColorStateList.valueOf(0xFF20496A));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(51));lp.setMargins(0,dp(4),0,dp(4));
        parent.addView(b,lp);b.setOnClickListener(v->action.run());return b;
    }
    private void gap(LinearLayout parent,View v,int bottom){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(bottom);parent.addView(v,lp);
    }
    @Override public void onBackPressed(){if(screen!=0){go(0);return;}super.onBackPressed();}
    @Override protected void onDestroy(){
        if(transformer!=null && exporting)transformer.cancel();
        if(tts!=null){tts.stop();tts.shutdown();}
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
