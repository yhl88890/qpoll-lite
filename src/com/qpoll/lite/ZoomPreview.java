package com.qpoll.lite;
import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Full viewport preview of an immutable prepared raster, without nested scrolling. */
public final class ZoomPreview extends View {
 private final Bitmap image;private final Paint paint=new Paint();
 private final ScaleGestureDetector scaling;private final GestureDetector gestures;
 private float scale=1,left,top,lastX,lastY;
 public ZoomPreview(Context context,Bitmap image){super(context);this.image=image;paint.setFilterBitmap(false);paint.setDither(false);setContentDescription("全屏像素预览，双指缩放，单指拖动，双击切换缩放");
  scaling=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoom(scale*d.getScaleFactor(),d.getFocusX(),d.getFocusY());return true;}});
  gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onDoubleTap(MotionEvent e){if(scale>fitScale()*1.2f)fit();else zoom(fitScale()*2,e.getX(),e.getY());return true;}});
 }
 private float fitScale(){return Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());}
 public void fit(){scale=Math.max(0.01f,fitScale());center();}
 public void actual(){scale=1;center();}
 private void center(){left=(getWidth()-image.getWidth()*scale)/2;top=(getHeight()-image.getHeight()*scale)/2;clamp();invalidate();}
 private void clamp(){float w=image.getWidth()*scale,h=image.getHeight()*scale;left=w<=getWidth()?(getWidth()-w)/2:Math.min(0,Math.max(getWidth()-w,left));top=h<=getHeight()?(getHeight()-h)/2:Math.min(0,Math.max(getHeight()-h,top));}
 private void zoom(float target,float x,float y){float next=Math.max(Math.min(1,fitScale()),Math.min(Math.max(8,fitScale()*8),target));if(scale<=0)return;left=x-(x-left)*next/scale;top=y-(y-top)*next/scale;scale=next;clamp();invalidate();}
 @Override protected void onSizeChanged(int w,int h,int ow,int oh){if(w>0&&h>0)fit();}
 @Override protected void onDraw(Canvas canvas){canvas.drawColor(0xff202923);canvas.save();canvas.translate(left,top);canvas.scale(scale,scale);canvas.drawBitmap(image,0,0,paint);canvas.restore();}
 @Override public boolean onTouchEvent(MotionEvent e){getParent().requestDisallowInterceptTouchEvent(true);boolean wasScaling=scaling.isInProgress();scaling.onTouchEvent(e);gestures.onTouchEvent(e);
  if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&!wasScaling&&!scaling.isInProgress()){left+=e.getX()-lastX;top+=e.getY()-lastY;clamp();invalidate();}
  lastX=e.getX();lastY=e.getY();if(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP&&e.getPointerCount()>1){int index=e.getActionIndex()==0?1:0;lastX=e.getX(index);lastY=e.getY(index);}if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;
 }
 @Override public boolean performClick(){super.performClick();return true;}
}
