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
    // About 60% of the reading region remains available for overlap matching.
    private static final float SWIPE_START=.80f,SWIPE_END=.40f;
    private static final long SWIPE_MS=450,HOLD_MS=180,SETTLE_MS=140;
    private static final int MATCH_IGNORE_TOP_PERCENT=10;
    private static final int MAX_FRAME_RETRIES=2,MAX_STATIONARY_CHECKS=3;
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
    private Rect targetBounds,scrollBounds;
    private float[] crop={0f,.075f,1f,.94f};

    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;windows=getSystemService(WindowManager.class);Log.i("LongCapture","Service connected");
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)main.post(debugCommands);
    }
    // Debug APK only. Commands are private files writable through the authorized ADB run-as user.
    private final Runnable debugCommands=new Runnable(){public void run(){
        if(destroyed)return;
        for(String name:new String[]{"show","start","stop","pause","resume","export","inspect","select","hide"}){
            java.io.File command=new java.io.File(getFilesDir(),"debug-"+name);
            if(command.exists()&&command.delete()){
                Log.i("LongCapture","Debug command: "+name);
                if(name.equals("show"))showControls();
                if(name.equals("start"))begin();
                if(name.equals("stop"))stopping=true;
                if(name.equals("pause")&&running)pauseCapture("已暂停，点继续恢复");
                if(name.equals("resume")&&running){paused=false;setMessage("继续采集");buildControls();}
                if(name.equals("inspect"))inspectWindow();
                if(name.equals("select")&&!running)selectRegion();
                if(name.equals("hide")&&!running){remove(controls);remove(selection);controls=null;selection=null;}
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
    @Override public void onInterrupt(){if(running)pauseCapture("服务中断，已暂停");}
    @Override public void onDestroy(){destroyed=true;stopping=true;running=false;if(instance==this)instance=null;main.removeCallbacksAndMessages(null);remove(controls);remove(selection);controls=null;selection=null;worker.shutdownNow();super.onDestroy();}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){
        super.onConfigurationChanged(configuration);if(destroyed)return;
        if(controls!=null)buildControls();
        if(selection instanceof RegionView){((RegionView)selection).appearance=new AppAppearance(this);selection.invalidate();}
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    public boolean isRunning(){return running;}
    public String getStatus(){return message;}
    private void setMessage(String text){message=text;main.post(()->{if(!destroyed&&status!=null)status.setText(text);});Log.i("LongCapture",text);}
    private void remove(View view){if(view!=null)try{windows.removeView(view);}catch(Exception ignored){}}
    private void pauseCapture(String text){
        if(stopping||destroyed)return;paused=true;setMessage(text);
        main.post(()->{if(!destroyed)buildControls();});
    }
    private void checkControl()throws CaptureStopped,CapturePaused {
        if(stopping||destroyed||Thread.currentThread().isInterrupted())throw new CaptureStopped();
        if(paused)throw new CapturePaused();
    }
    private void delay(long milliseconds)throws Exception {
        long end=SystemClock.elapsedRealtime()+milliseconds;
        do{checkControl();long left=end-SystemClock.elapsedRealtime();if(left<=0)return;Thread.sleep(Math.min(left,100));}while(true);
    }
    private void awaitResume()throws Exception {
        long nextTargetCheck=0;int missing=0;
        while(true){
            if(stopping||destroyed||Thread.currentThread().isInterrupted())throw new CaptureStopped();
            if(!paused)return;
            long now=SystemClock.elapsedRealtime();
            if(now>=nextTargetCheck){
                if(!targetStillActive()){if(++missing>=2)throw new IOExceptionWithMessage("已离开截图页面，任务已保留");}else missing=0;
                nextTargetCheck=now+500;
            }
            Thread.sleep(100);
        }
    }
    private <T> T await(CompletableFuture<T> future,long timeout,String failure)throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(true){
            checkControl();long left=end-SystemClock.elapsedRealtime();if(left<=0)throw new IOExceptionWithMessage(failure);
            try{return future.get(Math.min(left,100),TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}
        }
    }

    public void showControls(){main.post(()->{if(!destroyed)buildControls();});}
    private void buildControls(){
        if(destroyed)return;
        remove(controls);
        AppAppearance appearance=new AppAppearance(this);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(8),dp(10),dp(8));
        box.setBackground(appearance.shape(appearance.surface,18,true));box.setElevation(dp(6));
        status=new TextView(this);status.setTextColor(appearance.secondary);status.setTextSize(12);status.setMaxWidth(dp(260));status.setPadding(dp(3),0,dp(3),dp(4));status.setText(message);box.addView(status);
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.END);box.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
        if(!running){
            add(buttons,"选区",()->selectRegion(),false);add(buttons,"开始",()->begin(),true);add(buttons,"隐藏",()->{remove(controls);controls=null;},false);
        }else{
            add(buttons,paused?"继续":"暂停",()->{paused=!paused;setMessage(paused?"已暂停，点继续恢复":"继续采集");buildControls();},true);
            add(buttons,"结束",()->{stopping=true;setMessage("正在结束并保存任务…");},false);
        }
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|(running?WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON:0),PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.END;p.y=dp(55);p.x=dp(6);controls=box;
        try{windows.addView(box,p);}catch(Exception e){controls=null;Log.e("LongCapture","Overlay failed",e);}
    }
    private void add(LinearLayout parent,String label,Runnable action,boolean primary){
        AppAppearance appearance=new AppAppearance(this);
        Button button=new Button(this);button.setText(label);button.setTextSize(12);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);
        button.setBackgroundTintList(null);button.setBackground(appearance.button(primary));button.setTextColor(primary?appearance.onAccent:appearance.text);
        button.setPadding(dp(9),0,dp(9),0);LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(dp(64),dp(40));layout.leftMargin=dp(4);parent.addView(button,layout);button.setOnClickListener(v->action.run());
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
        Rect selected=selectedBounds();scrollBounds=findScrollBounds(root,selected);
        boolean savedRegion=prefs.contains(targetPackage+".left")||prefs.contains(targetPackage+".top")||prefs.contains(targetPackage+".right")||prefs.contains(targetPackage+".bottom");
        // For a first capture, conservatively trim the default to a nearly full-size scrolling viewport.
        // This is temporary: an explicit saved selection always wins and no preference is written here.
        if(!savedRegion&&scrollBounds!=null&&scrollBounds.width()>=selected.width()*.9f&&scrollBounds.height()>=selected.height()*.75f){
            crop=new float[]{(scrollBounds.left-targetBounds.left)/(float)targetBounds.width(),(scrollBounds.top-targetBounds.top)/(float)targetBounds.height(),
                    (scrollBounds.right-targetBounds.left)/(float)targetBounds.width(),(scrollBounds.bottom-targetBounds.top)/(float)targetBounds.height()};
            Log.i("LongCapture","Default region fitted to scrolling viewport: "+scrollBounds);
        }
        return true;
    }
    private boolean targetStillActive(){
        AccessibilityWindowInfo window=applicationWindow();if(window==null||window.getId()!=targetId)return false;
        Rect rect=new Rect();window.getBoundsInScreen(rect);if(!targetBounds.equals(rect))return false;
        AccessibilityNodeInfo root=window.getRoot();
        return root==null||root.getPackageName()==null||targetPackage.contentEquals(root.getPackageName());
    }
    private void requireTarget()throws Exception {
        // A transient unavailable accessibility window is not an app switch.
        for(int attempt=0;attempt<3;attempt++){checkControl();if(targetStillActive())return;if(attempt<2)delay(120);}
        throw new IOExceptionWithMessage("窗口已切换或尺寸改变，任务已停止");
    }
    private Rect selectedBounds(){
        return new Rect(targetBounds.left+Math.round(crop[0]*targetBounds.width()),targetBounds.top+Math.round(crop[1]*targetBounds.height()),
                targetBounds.left+Math.round(crop[2]*targetBounds.width()),targetBounds.top+Math.round(crop[3]*targetBounds.height()));
    }
    private Rect findScrollBounds(AccessibilityNodeInfo root,Rect selected){
        if(root==null)return null;
        Rect best=null;long bestArea=0;ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(root);int inspected=0;
        while(!nodes.isEmpty()&&inspected<400){
            inspected++;
            AccessibilityNodeInfo node=nodes.removeFirst();
            if(node.isVisibleToUser()&&node.isScrollable()){
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                if(bounds.intersect(selected)&&bounds.width()>=selected.width()/3&&bounds.height()>=selected.height()/3){
                    long area=(long)bounds.width()*bounds.height();if(area>bestArea){bestArea=area;best=new Rect(bounds);}
                }
            }
            for(int i=0;i<node.getChildCount()&&nodes.size()<400;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)nodes.addLast(child);}
        }
        // The intersection constrains gestures. Saved image selections remain unchanged.
        return best;
    }
    private void inspectWindow(){
        StringBuilder report=new StringBuilder();AccessibilityWindowInfo window=applicationWindow();
        if(window==null)report.append("No application window\n");
        else{
            Rect bounds=new Rect();window.getBoundsInScreen(bounds);report.append("windowId=").append(window.getId()).append(" bounds=").append(bounds).append('\n');
            AccessibilityNodeInfo root=window.getRoot();
            if(root==null)report.append("No accessibility root\n");
            else{
                report.append("rootClass=").append(root.getClassName()).append('\n');
                if(root.getPackageName()!=null){
                    String name=root.getPackageName().toString();android.content.SharedPreferences prefs=getSharedPreferences("config",0);
                    report.append("savedRegion=").append(prefs.contains(name+".top")).append(" crop=").append(Arrays.toString(new float[]{prefs.getFloat(name+".left",0),prefs.getFloat(name+".top",.075f),prefs.getFloat(name+".right",1),prefs.getFloat(name+".bottom",.94f)})).append('\n');
                }
                ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(root);int inspected=0,found=0;
                while(!nodes.isEmpty()&&inspected<400){
                    inspected++;
                    AccessibilityNodeInfo node=nodes.removeFirst();
                    if(node.isScrollable()){
                        Rect nodeBounds=new Rect();node.getBoundsInScreen(nodeBounds);
                        report.append("scrollable class=").append(node.getClassName()).append(" bounds=").append(nodeBounds).append(" visible=").append(node.isVisibleToUser()).append('\n');
                        if(++found>=32)break;
                    }
                    for(int i=0;i<node.getChildCount()&&nodes.size()<400;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)nodes.addLast(child);}
                }
                report.append("nodesInspected=").append(inspected).append(" scrollables=").append(found).append('\n');
            }
        }
        Log.i("LongCapture","Window inspect:\n"+report);
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"debug-window.txt"))){output.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(Exception e){Log.e("LongCapture","Window inspect write failed",e);}
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
        AppAppearance appearance=new AppAppearance(CaptureService.this);
        RegionView(){super(CaptureService.this);}
        protected void onSizeChanged(int w,int h,int oldW,int oldH){region.set(targetBounds.left+crop[0]*targetBounds.width(),targetBounds.top+crop[1]*targetBounds.height(),targetBounds.left+crop[2]*targetBounds.width(),targetBounds.top+crop[3]*targetBounds.height());}
        protected void onDraw(Canvas c){
            c.drawColor(0x55000000);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(appearance.accent);c.drawRect(region,paint);
            android.graphics.Insets insets=getRootWindowInsets()==null?android.graphics.Insets.NONE:getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            paint.setStyle(Paint.Style.FILL);paint.setColor(appearance.surface);c.drawRect(0,0,getWidth(),insets.top+dp(50),paint);c.drawRect(0,getHeight()-dp(86)-insets.bottom,getWidth(),getHeight(),paint);
            paint.setColor(appearance.text);paint.setTextSize(dp(14));c.drawText("框选正文，避开固定标题和底栏",dp(16),insets.top+dp(30),paint);
            c.drawText("取消",dp(35),getHeight()-insets.bottom-dp(35),paint);c.drawText("使用此区域",getWidth()-dp(125),getHeight()-insets.bottom-dp(35),paint);
        }
        public boolean onTouchEvent(MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                int bottomInset=getRootWindowInsets()==null?0:getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()).bottom;
                if(e.getY()>getHeight()-dp(86)-bottomInset){
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
        ScreenshotResult screenshot=null;
        for(int attempt=0;attempt<2;attempt++){
            long wait=430-(SystemClock.elapsedRealtime()-lastScreenshot);if(wait>0)delay(wait);
            requireTarget();CompletableFuture<ScreenshotResult> result=new CompletableFuture<>();lastScreenshot=SystemClock.elapsedRealtime();
            takeScreenshotOfWindow(targetId,getMainExecutor(),new TakeScreenshotCallback(){
                public void onSuccess(ScreenshotResult value){if(!result.complete(value))value.getHardwareBuffer().close();}
                public void onFailure(int code){result.completeExceptionally(new ScreenshotFailure(code));}
            });
            try{screenshot=await(result,8000,"截图超时，请检查服务或目标窗口");break;}
            catch(Exception e){
                result.cancel(false);
                // A callback can win the race with pause/cancel; release its unconsumed buffer too.
                try{ScreenshotResult orphan=result.getNow(null);if(orphan!=null)orphan.getHardwareBuffer().close();}catch(CancellationException|CompletionException ignored){}
                Throwable cause=e instanceof ExecutionException?e.getCause():e;
                if(attempt==0&&cause instanceof ScreenshotFailure&&((ScreenshotFailure)cause).code==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT){delay(600);continue;}
                throw e;
            }
        }
        if(screenshot==null)throw new IOExceptionWithMessage("未取得截图");
        Bitmap full=null,hardware=null;
        try{
            hardware=Bitmap.wrapHardwareBuffer(screenshot.getHardwareBuffer(),screenshot.getColorSpace());
            if(hardware==null)throw new IOExceptionWithMessage("截图为空");full=hardware.copy(Bitmap.Config.ARGB_8888,false);
            if(full==null)throw new IOExceptionWithMessage("无法读取截图");
            int left=Math.round(crop[0]*full.getWidth()),top=Math.round(crop[1]*full.getHeight());
            int right=Math.min(full.getWidth(),Math.round(crop[2]*full.getWidth())),bottom=Math.min(full.getHeight(),Math.round(crop[3]*full.getHeight()));
            Bitmap content=Bitmap.createBitmap(full,left,top,right-left,bottom-top);if(content!=full)full.recycle();full=null;return content;
        }finally{if(full!=null)full.recycle();if(hardware!=null)hardware.recycle();screenshot.getHardwareBuffer().close();}
    }
    private void swipe(boolean gentle)throws Exception {
        requireTarget();Rect bounds=selectedBounds();
        if(gentle){AccessibilityWindowInfo window=applicationWindow();if(window!=null)scrollBounds=findScrollBounds(window.getRoot(),bounds);}
        if(scrollBounds!=null){Rect inside=new Rect(bounds);if(inside.intersect(scrollBounds))bounds=inside;}
        float x=bounds.exactCenterX(),top=bounds.top,bottom=bounds.bottom;
        float endY=top+(bottom-top)*(gentle?.34f:SWIPE_END);
        Path path=new Path();path.moveTo(x,top+(bottom-top)*(gentle?.72f:SWIPE_START));path.lineTo(x,endY);
        GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(path,0,SWIPE_MS,true);
        CompletableFuture<Boolean> done=new CompletableFuture<>();
        main.post(()->{
            if(stopping||destroyed||paused){done.complete(false);return;}
            boolean accepted=dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),new GestureResultCallback(){
                public void onCompleted(GestureDescription g){
                    if(destroyed){done.complete(false);return;}
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
        // Finish the short release tail before reacting to pause/stop; otherwise its callback could
        // continue a held finger after the result activity has already opened.
        boolean completed;
        try{completed=done.get(5,TimeUnit.SECONDS);}catch(TimeoutException e){throw new IOExceptionWithMessage("滚动超时，已保留当前任务");}
        checkControl();
        if(!completed)throw new IOExceptionWithMessage("滚动被取消，已保留当前任务");
    }
    static int[] gray(Bitmap bitmap,int width,int height){
        Bitmap small=Bitmap.createScaledBitmap(bitmap,width,height,true);int[] pixels=new int[width*height];small.getPixels(pixels,0,width,0,0,width,height);
        if(small!=bitmap)small.recycle();for(int i=0;i<pixels.length;i++){int p=pixels[i];pixels[i]=(((p>>16)&255)*77+((p>>8)&255)*150+(p&255)*29)>>8;}return pixels;
    }
    // Exclude changing navigation chrome from comparisons without cropping saved pixels.
    private static int ignoredTopRows(int height){return (height*MATCH_IGNORE_TOP_PERCENT+99)/100;}
    private Bitmap stableFrame(long settleMs)throws Exception {
        Bitmap previous=null;
        try{
            delay(settleMs);previous=capture();
            for(int i=0;i<5;i++){
                delay(440);Bitmap next=capture();
                double difference=OverlapMatcher.robustDifference(gray(previous,80,144),gray(next,80,144),80,144,ignoredTopRows(144));
                if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)Log.i("LongCapture","Stability sample="+(i+1)+" diff="+difference);
                previous.recycle();previous=next;checkControl();
                if(difference<1.35){Bitmap stable=previous;previous=null;return stable;}
            }
            return null;
        }finally{if(previous!=null&&!previous.isRecycled())previous.recycle();}
    }
    private void captureLoop(){
        Bitmap previous=null;String reason="已结束";session=null;
        try{
            int initialRetries=0;
            while(previous==null){
                awaitResume();
                try{
                    previous=stableFrame(450);
                    if(previous==null){
                        if(++initialRetries>=MAX_FRAME_RETRIES){pauseCapture("画面持续变化，已暂停；请等待加载或重选正文区域");initialRetries=0;}
                        else{setMessage("等待首帧稳定…");delay(300);}
                    }
                }catch(CapturePaused ignored){}
            }
            if(stopping||destroyed)throw new CaptureStopped();
            session=new SessionStore(this,targetPackage);session.append(previous,0);
            setMessage(paused?"已暂停，点继续恢复":"已采 1 帧 · "+session.height+" px");
            int frames=1,stationaryChecks=0,unstableRetries=0,uncertainRetries=0;
            boolean needSwipe=true,gentleSwipe=false,confirmStationary=false;
            while(!stopping&&!destroyed&&frames<500){
                awaitResume();Bitmap current=null;
                try{
                    long frameStart=SystemClock.elapsedRealtime();
                    if(needSwipe){checkControl();needSwipe=false;swipe(gentleSwipe);gentleSwipe=false;}
                    long gestureEnd=SystemClock.elapsedRealtime();
                    current=stableFrame(SETTLE_MS);
                    long stableEnd=SystemClock.elapsedRealtime();
                    if(current==null){
                        if(++unstableRetries>=MAX_FRAME_RETRIES){pauseCapture("画面仍在变化，已暂停；请等待加载或重选正文区域");unstableRetries=0;}
                        else{setMessage("画面仍在加载，原地等待重试…");delay(300);}
                        continue;
                    }
                    unstableRetries=0;
                    int sampleH=Math.min(560,current.getHeight()),sampleW=160;
                    if(previous.getWidth()!=current.getWidth()||previous.getHeight()!=current.getHeight())throw new IOExceptionWithMessage("阅读区域尺寸改变");
                    OverlapMatcher.Result match=OverlapMatcher.match(gray(previous,sampleW,sampleH),gray(current,sampleW,sampleH),sampleW,sampleH,ignoredTopRows(sampleH));
                    if(!match.trusted&&!match.stationary&&current.getHeight()>sampleH){
                        // Fine text can disappear in the coarse sample. Retry here with more
                        // detail and the same evidence rules; never advance an uncertain seam.
                        sampleH=Math.min(1120,current.getHeight());checkControl();
                        match=OverlapMatcher.match(gray(previous,sampleW,sampleH),gray(current,sampleW,sampleH),sampleW,sampleH,ignoredTopRows(sampleH));
                        Log.i("LongCapture","Detailed match height="+sampleH+" shift="+match.shift+" error="+match.error+" trusted="+match.trusted);
                    }
                    Log.i("LongCapture","Match shift="+match.shift+" error="+match.error+" trusted="+match.trusted+" stationary="+match.stationary);
                    if(match.stationary){
                        uncertainRetries=0;
                        if(!confirmStationary){confirmStationary=true;setMessage("画面未移动，正在确认…");delay(350);continue;}
                        confirmStationary=false;
                        if(++stationaryChecks>=MAX_STATIONARY_CHECKS){reason="页面未继续移动，已停止；可能到底或滚动未响应";break;}
                        // Retry a smaller gesture within the actual scrolling container only after confirming no movement.
                        gentleSwipe=true;needSwipe=true;setMessage("尝试轻滑确认（"+stationaryChecks+"/"+(MAX_STATIONARY_CHECKS-1)+"）");continue;
                    }
                    confirmStationary=false;
                    if(!match.trusted){
                        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(session.directory,"last-candidate.png"))){current.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception ignored){}
                        if(++uncertainRetries<=MAX_FRAME_RETRIES){setMessage("正在重新确认接缝（"+uncertainRetries+"/"+MAX_FRAME_RETRIES+"）");delay(250);}
                        else{pauseCapture("接缝不确定，已暂停；请确认选区只包含滚动正文");uncertainRetries=0;}
                        // Keep the last valid frame and recapture here; never scroll past an unconfirmed seam.
                        continue;
                    }
                    int shift=Math.max(1,Math.round(match.shift*current.getHeight()/(float)sampleH));
                    shift=refine(previous,current,shift,Math.max(3,current.getHeight()/sampleH+2));
                    long matchEnd=SystemClock.elapsedRealtime();checkControl();requireTarget();
                    session.append(current,current.getHeight()-shift);previous.recycle();previous=current;current=null;
                    long saveEnd=SystemClock.elapsedRealtime();
                    Log.i("LongCapture","Perf frame_ms="+(saveEnd-frameStart)+" gesture_ms="+(gestureEnd-frameStart)+" stable_ms="+(stableEnd-gestureEnd)+" match_ms="+(matchEnd-stableEnd)+" save_ms="+(saveEnd-matchEnd)+" advance_px="+shift);
                    stationaryChecks=0;uncertainRetries=0;needSwipe=true;gentleSwipe=false;frames++;
                    setMessage(paused?"已暂停，点继续恢复":"已采 "+frames+" 帧 · "+session.height+" px");
                }catch(CapturePaused ignored){confirmStationary=false;}
                finally{if(current!=null&&!current.isRecycled())current.recycle();}
            }
            if(frames>=500)reason="已达到单次 500 帧上限";
        }catch(CaptureStopped ignored){reason=destroyed?"服务已关闭，任务已保留":"已结束";}
        catch(InterruptedException e){Thread.currentThread().interrupt();reason="服务已中断，任务已保留";}
        catch(Exception e){Throwable root=e;while(root.getCause()!=null)root=root.getCause();reason=root.getMessage()==null?root.getClass().getSimpleName():root.getMessage();Log.e("LongCapture","Capture stopped",e);}
        finally{
            if(previous!=null&&!previous.isRecycled())previous.recycle();
            if(session!=null)try{session.reason=reason;session.save();}catch(Exception e){Log.e("LongCapture","Save state",e);}
            running=false;paused=false;setMessage(reason);
            boolean showResult=!destroyed&&targetStillActive();
            main.post(()->{if(destroyed)return;buildControls();if(showResult){Intent i=new Intent(this,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);}});
        }
    }
    private int refine(Bitmap a,Bitmap b,int estimate,int radius){
        int w=a.getWidth(),h=a.getHeight(),top=ignoredTopRows(h),best=estimate;double bestScore=Double.MAX_VALUE;
        int[] rowA=new int[w],rowB=new int[w];
        for(int shift=Math.max(1,estimate-radius);shift<=Math.min(h-top-1,estimate+radius);shift++){
            double sum=0;int count=0;
            for(int n=1;n<=22;n++){
                int y=top+(h-shift-top)*n/23;a.getPixels(rowA,0,w,0,y+shift,w,1);b.getPixels(rowB,0,w,0,y,w,1);
                for(int x=w/12;x<w-w/12;x+=13){int p=rowA[x],q=rowB[x];sum+=Math.abs(((p>>16)&255)-((q>>16)&255))+Math.abs(((p>>8)&255)-((q>>8)&255))+Math.abs((p&255)-(q&255));count++;}
            }
            double score=sum/count;if(score<bestScore){bestScore=score;best=shift;}
        }return best;
    }
    private static final class IOExceptionWithMessage extends Exception {IOExceptionWithMessage(String s){super(s);}}
    private static final class CaptureStopped extends Exception {}
    private static final class CapturePaused extends Exception {}
    private static final class ScreenshotFailure extends Exception {
        final int code;ScreenshotFailure(int code){super("截图失败（"+code+"），请检查服务或目标窗口");this.code=code;}
    }
}
