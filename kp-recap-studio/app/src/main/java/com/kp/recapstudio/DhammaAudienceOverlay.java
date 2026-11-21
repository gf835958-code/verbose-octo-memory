package com.kp.recapstudio;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

/** Time-animated fifty-person audience overlay on original illustrated Dhamma stages. */
@UnstableApi
final class DhammaAudienceOverlay extends CanvasOverlay {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] skin={0xFFBEA781,0xFFD6B299,0xFF936E5E,0xFFE6BE9B};
    private final int[] robes={0xFF4C7B78,0xFF5B5485,0xFF93664B,0xFF5F6A85};
    DhammaAudienceOverlay(){super(true);}
    @Override public void onDraw(Canvas canvas,long presentationTimeUs){
        float sx=canvas.getWidth()/1280f,sy=canvas.getHeight()/720f;
        if(sx<=0||sy<=0)return;
        float sec=presentationTimeUs/1_000_000f;
        canvas.save();
        canvas.scale(sx,sy);
        for(int row=0;row<5;row++){
            for(int col=0;col<10;col++){
                float x=100+col*119+(row%2)*15;
                float y=520+row*34;
                float r=10+row*1.1f;
                float movement=(float)(Math.sin(sec*1.1f+row*.65f+col*.23f)*1.65f);
                float expansion=(float)(Math.sin(sec*.65f+row*.3f+col*.45f)*1.5f);
                // Opaque body repaints base figure and moves subtly, not a fake video overlay.
                paint.setColor(robes[((row*7)+col)%4]);
                canvas.drawRoundRect(new RectF(x-r*1.5f,y+r*.7f+movement,
                        x+r*1.5f,y+r*2.6f+movement+expansion),r,r,paint);
                paint.setColor(skin[((row*11)+col)%4]);
                canvas.drawCircle(x,y+movement,r,paint);
            }
        }
        canvas.restore();
    }
}
