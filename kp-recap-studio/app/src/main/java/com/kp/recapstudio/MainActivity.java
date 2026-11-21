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
    private static final int PICK_MEDIA=101, PICK_IMAGE=102, PICK_SRT=103, VOICE_DICTATION=104;
    private static final int VIDEO=0, AUDIOBOOK=1, DHAMMA=2;
    private static final int BG=0xFF080E21, PANEL=0xFF172242, CYAN=0xFF45E5D4, WHITE=Color.WHITE, MUTED=0xFFABBDDB;
    private int mode=VIDEO;
    private Uri selectedMedia, selectedImage, lastSavedUri;
    private String selectedFileName="", srtText="";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private TextToSpeech tts;
    private EditText titleInput, subtitleInput, startSecondsInput, endSecondsInput;
    private TextView statusText, fileText, srtStatus;
    private ProgressBar progress;
    private Button exportButton;
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
        tts=new TextToSpeech(this, result -> {
            if(result!=TextToSpeech.SUCCESS) runOnUiThread(()->toast("System TTS unavailable on this phone"));
        });
    }

    private void buildScreen(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16),dp(16),dp(16),dp(36));scroll.addView(body);setContentView(scroll);
        TextView heading=label("KP RECAP STUDIO",26,WHITE,true);body.addView(heading);
        TextView tag=label("AI-ready Media Creation  •  Original Android Project",12,CYAN,false);body.addView(tag);
        TextView tip=label("Free • Android 8+ • No account • Local file picker",12,MUTED,false);gap(body,tip,14);
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.VERTICAL);body.addView(tabs);
        addButton(tabs,"①  VIDEO RECAP + MYANMAR SUBTITLES",()->{mode=VIDEO;selectedMedia=null;selectedFileName="";buildScreen();});
        addButton(tabs,"②  AUDIOBOOK STUDIO",()->{mode=AUDIOBOOK;selectedMedia=null;selectedFileName="";buildScreen();});
        addButton(tabs,"③  2D DHAMMA STAGE",()->{mode=DHAMMA;selectedMedia=null;selectedFileName="";buildScreen();});
        TextView section=label(mode==VIDEO ? "Video Recap Editor" : (mode==AUDIOBOOK ? "Audiobook Studio" : "2D Dhamma Stage"),21,WHITE,true);gap(body,section,16);
        TextView explain=label(mode==VIDEO ?
            "Import your video. Add Myanmar captions directly or import a timed .srt file, then export a real MP4. Automatic translation is not in this alpha." :
            (mode==AUDIOBOOK ? "Import an audio recording and render a new MP4 with an original studio scene and animated decorative visualizer." :
            "Import an audio recording and render a video with an original illustrated Dhamma stage and 50 audience figures."),13,MUTED,false);
        gap(body,explain,10);
        addButton(body,mode==VIDEO?"📁  SELECT VIDEO":"📁  SELECT AUDIO",()->pick(PICK_MEDIA,mode==VIDEO?"video/*":"audio/*"));
        fileText=label(selectedMedia==null?"No input selected":selectedFileName,13,CYAN,false);gap(body,fileText,10);
        addButton(body,"▶  PREVIEW INPUT",this::previewInput);
        if(mode!=VIDEO) addButton(body,"🖼  CHOOSE OPTIONAL BACKGROUND IMAGE",()->pick(PICK_IMAGE,"image/*"));
        titleInput=edit("Video title / ခေါင်းစဉ်",false);gap(body,titleInput,8);
        subtitleInput=edit(mode==VIDEO?"Myanmar subtitle (shown if no SRT)":"Subtitle / Speaker name",true);gap(body,subtitleInput,8);
        if(mode==VIDEO){
            TextView ratioLabel=label("Video Aspect Ratio",14,WHITE,true);gap(body,ratioLabel,8);
            ratioSpinner=new Spinner(this);
            ratioSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Original","9:16 Portrait","3:4 Portrait","16:9 Landscape","1:1 Square","4:5 Portrait"}));
            gap(body,ratioSpinner,6);
            cropCheck=new CheckBox(this);
            cropCheck.setText("Fill / Crop video to chosen ratio (unchecked = Fit)");
            cropCheck.setTextColor(MUTED);
            gap(body,cropCheck,12);
            addButton(body,"📝  IMPORT TIMED SUBTITLES (.SRT)",()->pick(PICK_SRT,"*/*"));
            srtStatus=label(srtText.isEmpty()?"No SRT: text will appear for the full video":"SRT loaded — timed caption export enabled",12,MUTED,false);
            gap(body,srtStatus,8);
            addButton(body,"🎙  DICTATE SCRIPT USING MICROPHONE",this::dictate);
            TextView note=label("Voice dictation uses the phone microphone; it does NOT transcribe the imported video.",12,MUTED,false);gap(body,note,10);
            addButton(body,"🔊  GENERATE SYSTEM TTS VOICE FILE",this::synthesizeVoice);
            TextView voiceNote=label("A Myanmar voice must be installed in Android TTS. WAV is exported separately; voice replacement is not yet implemented.",12,MUTED,false);gap(body,voiceNote,10);
            TextView trim=label("Optional trim range (seconds)",14,WHITE,true);gap(body,trim,8);
            startSecondsInput=edit("Start: 0",false);startSecondsInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
            gap(body,startSecondsInput,6);
            endSecondsInput=edit("End: leave blank for full video",false);endSecondsInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
            gap(body,endSecondsInput,8);
        }
        TextView exportInfo=label("Output: MP4 (H.264 video + AAC audio). Saved to Movies/KP Recap Studio.",12,MUTED,false);gap(body,exportInfo,10);
        exportButton=addButton(body,"🚀  RENDER & SAVE VIDEO",this::export);
        addButton(body,"⛔  CANCEL RENDER",()->{if(transformer!=null&&exporting){transformer.cancel();exporting=false;status("Render cancelled");exportButton.setEnabled(true);}});
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);gap(body,progress,12);
        statusText=label("အသုံးပြုရန် Video သို့မဟုတ် Audio ဖိုင်တစ်ခုရွေးပါ။",13,CYAN,false);gap(body,statusText,10);
        addButton(body,"📺  OPEN LAST EXPORTED VIDEO",this::openOutput);
        TextView caveat=label("ALPHA • Automatic Video Speech Recognition, Gemini Translation, Smart TTS Dubbing, Chroma Key and real audio-reactive waveforms are not implemented yet. No credit checks, ads, logins or hidden API keys.",12,MUTED,false);
        gap(body,caveat,16);
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
            if(values!=null&&!values.isEmpty())subtitleInput.setText(values.get(0));
            return;
        }
        Uri uri=data.getData();if(uri==null)return;
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        if(request==PICK_MEDIA){
            selectedMedia=uri;selectedFileName=uri.getLastPathSegment();
            fileText.setText("Selected: "+selectedFileName);
            status("Input selected. You can render now.");
        }else if(request==PICK_IMAGE){
            selectedImage=uri;status("Custom background selected.");
        }else if(request==PICK_SRT){
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IOException("Unable to read SRT");
                byte[] buf=new byte[8192];int n;int total=0;
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                while((n=in.read(buf))>0){total+=n;if(total>2_000_000)throw new IOException("SRT exceeds 2 MB");out.write(buf,0,n);}
                srtText=out.toString("UTF-8");
                int count=MotionOverlay.parseSrt(srtText).size();
                srtStatus.setText("SRT loaded: "+count+" timed captions");
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
    private void synthesizeVoice(){
        if(tts==null){toast("TTS is not ready");return;}
        String content=subtitleInput.getText().toString().trim();
        if(content.isEmpty()){toast("Type the script first");return;}
        int language=tts.setLanguage(new Locale("my","MM"));
        if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){
            status("Myanmar TTS voice is not installed. Install it in your device's TTS settings.");return;
        }
        File output=new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC),"kp-voice-"+System.currentTimeMillis()+".wav");
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String utteranceId){runOnUiThread(()->status("Synthesizing voice..."));}
            @Override public void onDone(String utteranceId){runOnUiThread(()->status("Voice created: "+output.getAbsolutePath()));}
            @Override public void onError(String utteranceId){runOnUiThread(()->status("System TTS synthesis failed"));}
        });
        int result=tts.synthesizeToFile(content,new Bundle(),output,"KPVoice");
        if(result!=TextToSpeech.SUCCESS)status("TTS engine rejected request.");
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
                            runOnUiThread(()->{lastSavedUri=published;progress.setProgress(100);exportButton.setEnabled(true);status("SUCCESS! MP4 saved to Movies/KP Recap Studio");});
                        }catch(Exception e){runOnUiThread(()->{exportButton.setEnabled(true);status("MP4 created in app storage, but Gallery copy failed: "+e.getMessage());});}
                    }).start();
                }
                @Override public void onError(Composition composition,ExportResult exportResult,ExportException exportException){
                    exporting=false;exportButton.setEnabled(true);
                    status("Render failed: "+exportException.getMessage());
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
                EditedMediaItem edited=new EditedMediaItem.Builder(builder.build())
                    .setEffects(new Effects(Collections.emptyList(),videoEffects))
                    .build();
                transformer.start(edited,exportFile.getAbsolutePath());
            }else{
                long duration=readDurationMs(selectedMedia);
                if(duration<=0)throw new IOException("Audio duration unavailable");
                File image=StageArt.create(this,mode==DHAMMA,selectedImage,title,subtitle);
                MediaItem visual=new MediaItem.Builder().setUri(Uri.fromFile(image)).setImageDurationMs(duration).build();
                EditedMediaItem editedImage=new EditedMediaItem.Builder(visual)
                    .setFrameRate(24)
                    .setEffects(new Effects(Collections.emptyList(),Collections.singletonList(new OverlayEffect(Collections.singletonList(new MotionOverlay(false,"",null))))))
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
    @Override protected void onDestroy(){
        if(transformer!=null && exporting)transformer.cancel();
        if(tts!=null){tts.stop();tts.shutdown();}
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
