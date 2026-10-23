package local.longcapture;

import android.accessibilityservice.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class CaptureService extends AccessibilityService {
    // About 40% of the reading region remains available for overlap matching.
    private static final float SWIPE_START=.84f,SWIPE_END=.24f;
    private static final long SWIPE_MS=450,HOLD_MS=180,SETTLE_MS=140;
    public static volatile CaptureService instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private View controls,selection;
    private TextView status;
    private volatile boolean running,paused,stopping,destroyed;
    private volatile String message="打开纵向阅读页，再选区或开始";
    private SessionStore session;
    private long lastScreenshot;
    private String targetPackage="";
    private int targetId=-1;
    private Rect targetBounds;
    private float[] crop={0f,.075f,1f,.94f};

    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;windows=getSystemService(WindowManager.class);Log.i("LongCapture","Service connected");
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)main.post(debugCommands);
    }
    // Debug APK only. Commands are private files writable through the authorized ADB run-as user.
    private final Runnable debugCommands=new Runnable(){public void run(){
        if(destroyed)return;
        for(String name:new String[]{"show","start","stop","pause","resume","export"}){
            java.io.File command=new java.io.File(getFilesDir(),"debug-"+name);
            if(command.exists()&&command.delete()){
                Log.i("LongCapture","Debug command: "+name);
                if(name.equals("show"))showControls();
                if(name.equals("start"))begin();
                if(name.equals("stop"))stopping=true;
                if(name.equals("pause")){paused=true;buildControls();}
                if(name.equals("resume")){paused=false;buildControls();}
                if(name.equals("export")&&!running)worker.execute(()->{
                    try{SessionStore s=SessionStore.latest(CaptureService.this);android.net.Uri uri=PngExporter.export(CaptureService.this,s,p->{});
                        Log.i("LongCapture","Debug export complete: "+uri);
                    }catch(Exception e){Log.e("LongCapture","Debug export failed",e);}
                });
            }
        }
        main.postDelayed(this,500);
    }};
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){paused=true;setMessage("服务中断，已暂停");}
    @Override public void onDestroy(){destroyed=true;stopping=true;running=false;instance=null;remove(controls);remove(selection);worker.shutdownNow();super.onDestroy();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    public boolean isRunning(){return running;}
    public String getStatus(){return message;}
    private void setMessage(String text){message=text;main.post(()->{if(status!=null)status.setText(text);});Log.i("LongCapture",text);}
    private void remove(View view){if(view!=null)try{windows.removeView(view);}catch(Exception ignored){}}

    public void showControls(){main.post(this::buildControls);}
    private void buildControls(){
        remove(controls);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(8),dp(5),dp(8),dp(5));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xED14232D);bg.setCornerRadius(dp(14));box.setBackground(bg);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(12);status.setMaxWidth(dp(280));status.setText(message);box.addView(status);
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.END);box.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
        if(!running){
            add(buttons,"选区",()->selectRegion());add(buttons,"开始",()->begin());add(buttons,"隐藏",()->{remove(controls);controls=null;});
        }else{
            add(buttons,paused?"继续":"暂停",()->{paused=!paused;setMessage(paused?"已暂停，点继续恢复":"继续采集");buildControls();});
            add(buttons,"结束",()->{stopping=true;setMessage("正在结束并保存任务…");});
        }
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|(running?WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON:0),PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.END;p.y=dp(55);p.x=dp(6);controls=box;
        try{windows.addView(box,p);}catch(Exception e){controls=null;Log.e("LongCapture","Overlay failed",e);}
    }
    private void add(LinearLayout parent,String label,Runnable action){
        Button button=new Button(this);button.setText(label);button.setTextSize(12);button.setMinWidth(0);button.setMinimumWidth(0);
        button.setPadding(dp(9),0,dp(9),0);parent.addView(button,new LinearLayout.LayoutParams(dp(68),dp(40)));button.setOnClickListener(v->action.run());
    }
    private AccessibilityWindowInfo applicationWindow(){
        AccessibilityWindowInfo best=null;
        for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){
            if(w.isActive()||w.isFocused())return w;
            if(best==null||w.getLayer()>best.getLayer())best=w;
        }
        return best;
    }
    private boolean lockTarget(){
        AccessibilityWindowInfo window=applicationWindow();if(window==null){setMessage("未找到前台应用窗口");return false;}
        AccessibilityNodeInfo root=window.getRoot();if(root==null||root.getPackageName()==null){setMessage("前台窗口暂不可用");return false;}
        targetPackage=root.getPackageName().toString();targetId=window.getId();targetBounds=new Rect();window.getBoundsInScreen(targetBounds);
        android.content.SharedPreferences prefs=getSharedPreferences("config",0);
        crop=new float[]{prefs.getFloat(targetPackage+".left",0),prefs.getFloat(targetPackage+".top",.075f),prefs.getFloat(targetPackage+".right",1),prefs.getFloat(targetPackage+".bottom",.94f)};
        return true;
    }
    private boolean targetStillActive(){
        AccessibilityWindowInfo window=applicationWindow();if(window==null||window.getId()!=targetId)return false;
        Rect rect=new Rect();window.getBoundsInScreen(rect);return targetBounds.equals(rect);
    }
    private void selectRegion(){
        if(running||!lockTarget())return;remove(controls);controls=null;
        RegionView view=new RegionView();selection=view;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;windows.addView(view,p);
    }
    private final class RegionView extends View {
        final Paint paint=new Paint(3);final RectF region=new RectF();float startX,startY;boolean drawing;
        RegionView(){super(CaptureService.this);}
        protected void onSizeChanged(int w,int h,int oldW,int oldH){region.set(targetBounds.left+crop[0]*targetBounds.width(),targetBounds.top+crop[1]*targetBounds.height(),targetBounds.left+crop[2]*targetBounds.width(),targetBounds.top+crop[3]*targetBounds.height());}
        protected void onDraw(Canvas c){
            c.drawColor(0x55000000);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(0xFF4EE0C0);c.drawRect(region,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xEE14232D);c.drawRect(0,getHeight()-dp(86),getWidth(),getHeight(),paint);
            paint.setColor(Color.WHITE);paint.setTextSize(dp(15));c.drawText("拖动框选阅读内容，避开标题、页码和底栏",dp(16),dp(35),paint);
            c.drawText("取消",dp(35),getHeight()-dp(35),paint);c.drawText("使用此区域",getWidth()-dp(125),getHeight()-dp(35),paint);
        }
        public boolean onTouchEvent(MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                if(e.getY()>getHeight()-dp(86)){
                    if(e.getX()>getWidth()/2 && region.width()>targetBounds.width()*.25 && region.height()>targetBounds.height()*.25){
                        crop=new float[]{clamp((region.left-targetBounds.left)/targetBounds.width()),clamp((region.top-targetBounds.top)/targetBounds.height()),clamp((region.right-targetBounds.left)/targetBounds.width()),clamp((region.bottom-targetBounds.top)/targetBounds.height())};
                        getSharedPreferences("config",0).edit().putFloat(targetPackage+".left",crop[0]).putFloat(targetPackage+".top",crop[1]).putFloat(targetPackage+".right",crop[2]).putFloat(targetPackage+".bottom",crop[3]).apply();
                        setMessage("选区已记住，点开始向下采集");
                    }
                    remove(selection);selection=null;buildControls();return true;
                }
                drawing=true;startX=e.getX();startY=e.getY();
            }else if(drawing){region.set(Math.min(startX,e.getX()),Math.min(startY,e.getY()),Math.max(startX,e.getX()),Math.max(startY,e.getY()));if(e.getAction()==MotionEvent.ACTION_UP)drawing=false;invalidate();}
            return true;
        }
    }
    private float clamp(float x){return Math.max(0,Math.min(1,x));}
    private void begin(){
        if(running||!lockTarget())return;
        running=true;paused=false;stopping=false;buildControls();setMessage("准备采集，保持竖向连续阅读");worker.execute(this::captureLoop);
    }
    private Bitmap capture() throws Exception {
        long wait=430-(SystemClock.elapsedRealtime()-lastScreenshot);if(wait>0)Thread.sleep(wait);
        if(!targetStillActive())throw new IOExceptionWithMessage("窗口已切换或尺寸改变，任务已停止");
        CompletableFuture<ScreenshotResult> result=new CompletableFuture<>();
        takeScreenshotOfWindow(targetId,getMainExecutor(),new TakeScreenshotCallback(){
            public void onSuccess(ScreenshotResult value){if(!result.complete(value))value.getHardwareBuffer().close();}
            public void onFailure(int code){result.completeExceptionally(new IOExceptionWithMessage("截图失败（"+code+"），请检查服务或目标窗口"));}
        });
        ScreenshotResult screenshot;
        try{screenshot=result.get(8,TimeUnit.SECONDS);}catch(Exception e){result.cancel(false);throw e;}
        lastScreenshot=SystemClock.elapsedRealtime();Bitmap full=null,hardware=null;
        try{
            hardware=Bitmap.wrapHardwareBuffer(screenshot.getHardwareBuffer(),screenshot.getColorSpace());
            if(hardware==null)throw new IOExceptionWithMessage("截图为空");full=hardware.copy(Bitmap.Config.ARGB_8888,false);
            if(full==null)throw new IOExceptionWithMessage("无法读取截图");
            int left=Math.round(crop[0]*full.getWidth()),top=Math.round(crop[1]*full.getHeight());
            int right=Math.min(full.getWidth(),Math.round(crop[2]*full.getWidth())),bottom=Math.min(full.getHeight(),Math.round(crop[3]*full.getHeight()));
            Bitmap content=Bitmap.createBitmap(full,left,top,right-left,bottom-top);if(content!=full)full.recycle();full=null;return content;
        }finally{if(full!=null)full.recycle();if(hardware!=null)hardware.recycle();screenshot.getHardwareBuffer().close();}
    }
    private void swipe()throws Exception {
        if(!targetStillActive())throw new IOExceptionWithMessage("目标应用已切换");
        float x=targetBounds.left+(crop[0]+crop[2])*.5f*targetBounds.width();
        float top=targetBounds.top+crop[1]*targetBounds.height(),bottom=targetBounds.top+crop[3]*targetBounds.height();
        float endY=top+(bottom-top)*SWIPE_END;
        Path path=new Path();path.moveTo(x,top+(bottom-top)*SWIPE_START);path.lineTo(x,endY);
        GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(path,0,SWIPE_MS,true);
        CompletableFuture<Boolean> done=new CompletableFuture<>();
        main.post(()->{
            boolean accepted=dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),new GestureResultCallback(){
                public void onCompleted(GestureDescription g){
                    // A tiny slow tail emits MOVE events, letting the reader's velocity tracker
                    // observe deceleration. A zero-length hold can retain the fast drag velocity.
                    Path hold=new Path();hold.moveTo(x,endY);hold.lineTo(x,endY-1f);
                    boolean held=dispatchGesture(new GestureDescription.Builder().addStroke(stroke.continueStroke(hold,0,HOLD_MS,false)).build(),new GestureResultCallback(){
                        public void onCompleted(GestureDescription value){done.complete(true);}public void onCancelled(GestureDescription value){done.complete(false);}
                    },main);if(!held)done.complete(false);
                }
                public void onCancelled(GestureDescription g){done.complete(false);}
            },main);if(!accepted)done.complete(false);
        });
        if(!done.get(5,TimeUnit.SECONDS))throw new IOExceptionWithMessage("滚动被取消，已保留当前任务");
    }
    static int[] gray(Bitmap bitmap,int width,int height){
        Bitmap small=Bitmap.createScaledBitmap(bitmap,width,height,true);int[] pixels=new int[width*height];small.getPixels(pixels,0,width,0,0,width,height);
        if(small!=bitmap)small.recycle();for(int i=0;i<pixels.length;i++){int p=pixels[i];pixels[i]=(((p>>16)&255)*77+((p>>8)&255)*150+(p&255)*29)>>8;}return pixels;
    }
    private Bitmap stableFrame(long settleMs)throws Exception {
        Thread.sleep(settleMs);Bitmap previous=capture();
        for(int i=0;i<5;i++){
            Thread.sleep(440);Bitmap next=capture();
            double difference=OverlapMatcher.difference(gray(previous,80,144),gray(next,80,144));
            if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)Log.i("LongCapture","Stability sample="+(i+1)+" diff="+difference);
            boolean stable=difference<1.35;
            previous.recycle();previous=next;if(stable)return next;
        }
        previous.recycle();return null;
    }
    private void captureLoop(){
        Bitmap previous=null;String reason="已结束";
        try{
            session=new SessionStore(this,targetPackage);previous=stableFrame(450);
            if(previous==null)throw new IOExceptionWithMessage("画面持续变化，请等待图片加载后再开始");
            session.append(previous,0);setMessage("已采 1 帧 · "+session.height+" px");
            int frames=1,stationary=0;boolean needSwipe=true;
            while(!stopping&&!destroyed&&frames<500){
                while(paused&&!stopping&&!destroyed)Thread.sleep(120);if(stopping||destroyed)break;
                long frameStart=SystemClock.elapsedRealtime();
                if(needSwipe)swipe();
                long gestureEnd=SystemClock.elapsedRealtime();
                Bitmap current=stableFrame(SETTLE_MS);
                long stableEnd=SystemClock.elapsedRealtime();
                if(current==null){paused=true;needSwipe=false;setMessage("画面仍在加载，已暂停；稳定后继续");main.post(this::buildControls);continue;}
                int sampleH=Math.min(560,current.getHeight()),sampleW=160;
                if(previous.getWidth()!=current.getWidth()||previous.getHeight()!=current.getHeight()){current.recycle();throw new IOExceptionWithMessage("阅读区域尺寸改变");}
                OverlapMatcher.Result match=OverlapMatcher.match(gray(previous,sampleW,sampleH),gray(current,sampleW,sampleH),sampleW,sampleH);
                Log.i("LongCapture","Match shift="+match.shift+" error="+match.error+" trusted="+match.trusted+" stationary="+match.stationary);
                if(match.stationary){current.recycle();if(++stationary>=2){reason="页面未继续移动，已停止";break;}needSwipe=true;continue;}
                if(!match.trusted){
                    if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(session.directory,"last-candidate.png"))){current.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception ignored){}
                    current.recycle();paused=true;needSwipe=false;setMessage("接缝不确定，已暂停。可结束保存有效部分");main.post(this::buildControls);continue;
                }
                int shift=Math.max(1,Math.round(match.shift*current.getHeight()/(float)sampleH));
                // Refine around the downsampled estimate at native Y resolution.
                shift=refine(previous,current,shift,Math.max(3,current.getHeight()/sampleH+2));
                long matchEnd=SystemClock.elapsedRealtime();
                session.append(current,current.getHeight()-shift);previous.recycle();previous=current;
                long saveEnd=SystemClock.elapsedRealtime();
                Log.i("LongCapture","Perf frame_ms="+(saveEnd-frameStart)+" gesture_ms="+(gestureEnd-frameStart)+" stable_ms="+(stableEnd-gestureEnd)+" match_ms="+(matchEnd-stableEnd)+" save_ms="+(saveEnd-matchEnd)+" advance_px="+shift);
                stationary=0;needSwipe=true;frames++;setMessage("已采 "+frames+" 帧 · "+session.height+" px");
            }
            if(frames>=500)reason="已达到单次 500 帧上限";
        }catch(Exception e){Throwable root=e;while(root.getCause()!=null)root=root.getCause();reason=root.getMessage()==null?root.getClass().getSimpleName():root.getMessage();Log.e("LongCapture","Capture stopped",e);}
        finally{
            if(previous!=null&&!previous.isRecycled())previous.recycle();
            if(session!=null)try{session.reason=reason;session.save();}catch(Exception e){Log.e("LongCapture","Save state",e);}
            running=false;paused=false;setMessage(reason);
            main.post(()->{buildControls();if(!destroyed){Intent i=new Intent(this,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);}});
        }
    }
    private int refine(Bitmap a,Bitmap b,int estimate,int radius){
        int w=a.getWidth(),h=a.getHeight(),best=estimate;double bestScore=Double.MAX_VALUE;
        int[] rowA=new int[w],rowB=new int[w];
        for(int shift=Math.max(1,estimate-radius);shift<=Math.min(h-1,estimate+radius);shift++){
            double sum=0;int count=0;
            for(int n=1;n<=22;n++){
                int y=(h-shift)*n/23;a.getPixels(rowA,0,w,0,y+shift,w,1);b.getPixels(rowB,0,w,0,y,w,1);
                for(int x=w/12;x<w-w/12;x+=13){int p=rowA[x],q=rowB[x];sum+=Math.abs(((p>>16)&255)-((q>>16)&255))+Math.abs(((p>>8)&255)-((q>>8)&255))+Math.abs((p&255)-(q&255));count++;}
            }
            double score=sum/count;if(score<bestScore){bestScore=score;best=shift;}
        }return best;
    }
    private static final class IOExceptionWithMessage extends Exception {IOExceptionWithMessage(String s){super(s);}}
}
