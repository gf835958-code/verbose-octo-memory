package com.kp.recapstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** All art is programmatically generated; no third-party copyrighted assets are shipped. */
final class StageArt {
    private static final int W = 1280, H = 720;
    private StageArt() { }

    static File create(Context context, boolean dhamma, Uri customBackground, String title, String subtitle) throws Exception {
        return create(context,dhamma,customBackground,title,subtitle,0);
    }
    static File create(Context context, boolean dhamma, Uri customBackground, String title, String subtitle, int sceneStyle) throws Exception {
        return create(context,dhamma,customBackground,title,subtitle,sceneStyle,null);
    }
    static File create(Context context, boolean dhamma, Uri customBackground, String title, String subtitle, int sceneStyle, Uri presenter) throws Exception {
        int style=Math.max(0,Math.min(3,sceneStyle));
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (customBackground != null) {
            Bitmap source = null;
            try (InputStream stream = context.getContentResolver().openInputStream(customBackground)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                source = BitmapFactory.decodeStream(stream, null, options);
            }
            if (source == null) throw new Exception("Background image cannot be decoded");
            c.drawBitmap(source, null, new RectF(0,0,W,H), p);
            source.recycle();
            p.setColor(0x77000000);
            c.drawRect(0,0,W,H,p);
        } else if (dhamma) {
            drawDhamma(c,p,style);
        } else {
            drawStudio(c,p,style);
        }
        if(presenter!=null) drawPresenter(context,c,p,presenter,dhamma);
        if(style>0){
            int[] wash={0,0x3F6755EA,0x3828DAD1,0x43B08A3B};
            p.setColor(wash[style]);c.drawRect(0,0,W,H,p);
        }
        p.setColor(0xB3000A18);
        c.drawRoundRect(new RectF(42,26,1238,154),24,24,p);
        drawText(c, title.isEmpty() ? "KP RECAP STUDIO" : title, 56, 54, 1168, 52, Color.WHITE, Layout.Alignment.ALIGN_CENTER);
        drawText(c, subtitle, 60, 124, 1160, 25, 0xFF79F6E9, Layout.Alignment.ALIGN_CENTER);
        File file = new File(context.getCacheDir(), "kp-stage-" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new Exception("Background PNG failed");
        }
        bitmap.recycle();
        return file;
    }

    private static void drawPresenter(Context context,Canvas canvas,Paint pen,Uri presenter,boolean dhamma)throws Exception{
        Bitmap original;
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(presenter)){
            BitmapFactory.decodeStream(in,null,bounds);
        }
        int sample=1;
        while(bounds.outWidth/sample>900||bounds.outHeight/sample>900)sample*=2;
        BitmapFactory.Options cfg=new BitmapFactory.Options();
        cfg.inSampleSize=sample;cfg.inPreferredConfig=Bitmap.Config.ARGB_8888;
        try(InputStream in=context.getContentResolver().openInputStream(presenter)){
            original=BitmapFactory.decodeStream(in,null,cfg);
        }
        if(original==null)throw new Exception("Presenter image cannot be decoded");
        int w=original.getWidth(),h=original.getHeight();
        if((long)w*h>3_000_000)throw new Exception("Presenter image too large");
        Bitmap keyed=original.copy(Bitmap.Config.ARGB_8888,true);
        int[] colors=new int[w*h];
        keyed.getPixels(colors,0,w,0,0,w,h);
        for(int i=0;i<colors.length;i++){
            int argb=colors[i],red=Color.red(argb),green=Color.green(argb),blue=Color.blue(argb);
            if(green>70 && green>red*1.30f && green>blue*1.26f){
                int strength=Math.min(255,Math.round((green-Math.max(red,blue)*1.20f)*4.2f));
                int alpha=Color.alpha(argb)*(255-strength)/255;
                colors[i]=(argb&0x00FFFFFF)|(alpha<<24);
            }
        }
        keyed.setPixels(colors,0,w,0,0,w,h);
        RectF dest=dhamma?new RectF(492,246,748,506):new RectF(445,232,775,640);
        pen.setColor(Color.WHITE);
        canvas.drawBitmap(keyed,null,dest,pen);
        keyed.recycle();original.recycle();
    }

    private static void drawStudio(Canvas c, Paint p, int style) {
        int[][] schemes={{0xFF11162C,0xFF211944,0xFF09182F},{0xFF15112D,0xFF301A54,0xFF0B0C23},{0xFF0F2F45,0xFF22435D,0xFF07172F},{0xFF38253D,0xFF513457,0xFF161C35}};
        p.setShader(new LinearGradient(0,0,0,H, schemes[style], null, Shader.TileMode.CLAMP));
        c.drawRect(0,0,W,H,p); p.setShader(null);
        p.setColor(0xFF243B58); c.drawRoundRect(new RectF(100,160,1180,640),35,35,p);
        p.setColor(0xFF172337); c.drawRoundRect(new RectF(127,183,1150,615),25,25,p);
        p.setColor(0xFF3EBCB8);
        for(int i=0;i<8;i++) c.drawRoundRect(new RectF(152+i*125,197,238+i*125,218),8,8,p);
        p.setColor(0xFF0B1424); c.drawRoundRect(new RectF(280,565,1050,705),28,28,p);
        // Original geometric presenter silhouette with a desk microphone.
        p.setColor(0xFFCE9B84); c.drawCircle(655,358,67,p);
        p.setColor(0xFF192A55); c.drawRoundRect(new RectF(545,422,764,630),70,70,p);
        p.setColor(0xFF0E1322); c.drawRoundRect(new RectF(430,556,899,681),22,22,p);
        p.setColor(0xFFBCC9E7); c.drawRoundRect(new RectF(780,366,801,555),10,10,p);
        p.setColor(0xFF262F48); c.drawRoundRect(new RectF(757,310,825,416),28,28,p);
        p.setColor(0xFF67E6E2); c.drawRoundRect(new RectF(775,328,807,391),13,13,p);
    }

    private static void drawDhamma(Canvas c, Paint p, int style) {
        int[][] schemes={{0xFF22364F,0xFF638B7B,0xFF244C38},{0xFF544454,0xFFCE9B6C,0xFF3A563C},{0xFF172A47,0xFF62698F,0xFF253D51},{0xFF6F532A,0xFFDCBC73,0xFF4A6A4E}};
        p.setShader(new LinearGradient(0,0,0,H,schemes[style],null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,W,H,p); p.setShader(null);
        p.setColor(0xFF385C3E); c.drawOval(new RectF(-100,390,1380,850),p);
        // Tree trunk and leaves.
        p.setColor(0xFF66513C); c.drawRoundRect(new RectF(980,36,1041,543),23,23,p);
        p.setColor(0xFF2B6747);
        c.drawCircle(976,118,157,p); c.drawCircle(1115,100,147,p); c.drawCircle(908,150,102,p);
        p.setColor(0xFF8A663D); c.drawRoundRect(new RectF(380,310,860,503),18,18,p);
        p.setColor(0xFFCBA57B); c.drawRoundRect(new RectF(356,288,884,324),18,18,p);
        p.setColor(0xFFDCA87C); c.drawCircle(620,350,43,p);
        p.setColor(0xFFBA742A); c.drawRoundRect(new RectF(550,387,695,495),45,45,p);
        p.setColor(0xFFF2CB5B); c.drawRoundRect(new RectF(542,470,710,490),5,5,p);
        // Fifty original simplified audience figures, arranged on five rows.
        for(int row=0;row<5;row++) for(int col=0;col<10;col++) {
            float x = 100+col*119 + (row%2)*15, y = 520+row*34;
            float radius = 10+row*1.1f;
            p.setColor(new int[]{0xFFBEA781,0xFFD6B299,0xFF936E5E,0xFFE6BE9B}[((row*11)+col)%4]);
            c.drawCircle(x,y,radius,p);
            p.setColor(new int[]{0xFF4C7B78,0xFF5B5485,0xFF93664B,0xFF5F6A85}[((row*7)+col)%4]);
            c.drawRoundRect(new RectF(x-radius*1.5f,y+radius*0.7f,x+radius*1.5f,y+radius*2.6f),radius,radius,p);
        }
    }

    private static void drawText(Canvas c, String value, int x, int top, int width, int sp, int color, Layout.Alignment align) {
        if (value == null || value.isEmpty()) return;
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setTextSize(sp); p.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        StaticLayout lay = StaticLayout.Builder.obtain(value,0,value.length(),p,width)
            .setAlignment(align).setIncludePad(false).setMaxLines(2).build();
        c.save(); c.translate(x,top); lay.draw(c); c.restore();
    }
}
