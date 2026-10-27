package com.qpoll.lite;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Draws a borrowed raster; never recycles an image owned by a prepared job. */
public final class PixelPreview extends View {
 private Bitmap image;private final Paint paint=new Paint();
 public PixelPreview(Context context){super(context);paint.setFilterBitmap(false);paint.setDither(false);}
 public void setImage(Bitmap image){this.image=image;invalidate();}
 @Override protected void onDraw(Canvas canvas){
  canvas.drawColor(0xffe3e9e5);if(image==null||getWidth()==0||getHeight()==0)return;
  float scale=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());float w=image.getWidth()*scale,h=image.getHeight()*scale;
  canvas.drawBitmap(image,null,new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2),paint);
 }
}
