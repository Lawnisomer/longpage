package local.longcapture;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private AppAppearance appearance;
    private LinearLayout body;
    private TextView state,serviceHint,task,taskDetail;
    private Button capture,manageService,export,open;
    private SessionStore session;
    private Uri exported;
    private long lastSessionRefresh;
    private boolean wasCapturing;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private static volatile boolean exporting;
    private static volatile int exportProgress;
    private static volatile String exportError;
    private static volatile long exportResult,reportedExportResult;
    private final Runnable refreshVisible=new Runnable(){public void run(){refresh(false);ui.postDelayed(this,750);}};

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);appearance=new AppAppearance(this);
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(true);scroller.setClipToPadding(true);
        scroller.setBackgroundColor(appearance.background);scroller.setVerticalScrollBarEnabled(false);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(24),dp(20),dp(24));
        scroller.addView(body,new ScrollView.LayoutParams(-1,-2));setContentView(scroller);
        appearance.applyBars(this);appearance.applyInsets(scroller);
        scroller.post(()->{appearance.applyBars(this);scroller.requestApplyInsets();});

        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);body.addView(brand);
        ImageView icon=new ImageView(this);icon.setImageResource(R.mipmap.ic_launcher);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(48),dp(48));iconParams.setMarginEnd(dp(12));brand.addView(icon,iconParams);
        LinearLayout name=new LinearLayout(this);name.setOrientation(LinearLayout.VERTICAL);brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        text(name,"长页",29,appearance.text,true);text(name,"打开想要留存的竖向长页。",13,appearance.secondary,false);
        space(body,24);

        LinearLayout captureCard=card();
        LinearLayout captureHeading=new LinearLayout(this);captureHeading.setGravity(Gravity.CENTER_VERTICAL);captureCard.addView(captureHeading);
        TextView captureTitle=text(captureHeading,"开始长截图",18,appearance.text,true);
        captureTitle.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        state=text(captureHeading,"",12,appearance.accent,true);state.setPadding(dp(10),dp(5),dp(10),dp(5));
        state.setBackground(appearance.shape(appearance.accentSurface,20,false));
        space(captureCard,10);serviceHint=text(captureCard,"",14,appearance.secondary,false);
        space(captureCard,16);capture=button(captureCard,"",true,this::showCapture);
        manageService=button(captureCard,"管理截图服务",false,()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        space(body,16);

        LinearLayout taskCard=card();text(taskCard,"最近任务",18,appearance.text,true);space(taskCard,10);
        task=text(taskCard,"还没有截图",23,appearance.text,true);
        taskDetail=text(taskCard,"完成采集后，在这里导出完整 PNG。",13,appearance.secondary,false);
        space(taskCard,12);export=button(taskCard,"导出完整 PNG 到相册",false,this::exportLatest);
        open=button(taskCard,"查看已导出的图片",false,this::openExported);
        space(body,16);

        LinearLayout guide=card();text(guide,"使用方式",16,appearance.text,true);space(guide,8);
        text(guide,getString(R.string.usage_steps),13,appearance.secondary,false);
        space(guide,8);text(guide,"仅向下采集 · 图片保存在本机",12,appearance.muted,false);
        space(body,18);
        TextView version=text(body,"Android 15 及以上 · 0.1.6\n保存至 Pictures/LongCapture\n无网络权限 · 无广告 · 无统计",12,appearance.muted,false);
        version.setOnLongClickListener(view->{startActivity(new Intent(this,TestPageActivity.class));return true;});
        handleDebugIntent(getIntent());
    }

    private LinearLayout card(){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(18),dp(18),dp(10));
        card.setBackground(appearance.shape(appearance.surface,20,true));body.addView(card,new LinearLayout.LayoutParams(-1,-2));return card;
    }

    private TextView text(LinearLayout parent,String value,int size,int color,boolean medium){
        TextView label=new TextView(this);label.setText(value);label.setTextSize(size);label.setTextColor(color);label.setLineSpacing(dp(3),1);
        label.setIncludeFontPadding(false);label.setGravity(Gravity.START);
        if(medium)label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        label.setPadding(0,dp(3),0,dp(4));parent.addView(label);return label;
    }

    private void space(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}

    private Button button(LinearLayout parent,String label,boolean primary,Runnable action){
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));button.setStateListAnimator(null);
        button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setMinWidth(0);button.setMinimumWidth(0);
        button.setPadding(dp(14),dp(10),dp(14),dp(10));button.setBackground(appearance.button(primary));button.setBackgroundTintList(null);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{getColor(R.color.app_disabled_text),primary?appearance.onAccent:appearance.text}));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(8);parent.addView(button,params);
        button.setOnClickListener(view->action.run());return button;
    }

    private void showCapture(){
        CaptureService service=CaptureService.instance;
        if(service==null){startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));return;}
        service.showControls();Toast.makeText(this,"打开要截取的页面，再点「选区」和「开始」",Toast.LENGTH_LONG).show();
    }

    private void openExported(){
        if(exported==null)return;
        try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(exported,"image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
        catch(Exception error){Toast.makeText(this,"没有可用的图片查看器",Toast.LENGTH_LONG).show();}
    }

    @Override protected void onResume(){super.onResume();refresh(true);ui.removeCallbacks(refreshVisible);ui.postDelayed(refreshVisible,750);}
    @Override protected void onPause(){ui.removeCallbacks(refreshVisible);super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacks(refreshVisible);super.onDestroy();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&appearance!=null)appearance.applyBars(this);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleDebugIntent(intent);}

    private void handleDebugIntent(Intent intent){
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)return;
        if(intent.getBooleanExtra("show_controls",false)&&CaptureService.instance!=null)CaptureService.instance.showControls();
        if(intent.getBooleanExtra("fixture",false))startActivity(new Intent(this,TestPageActivity.class)
                .putExtra("fixture_topbar",intent.getBooleanExtra("fixture_topbar",false)));
    }

    private void refresh(boolean force){
        CaptureService service=CaptureService.instance;boolean capturing=service!=null&&service.isRunning();
        state.setText(service==null?"未启用":capturing?"任务进行中":"已就绪");
        state.setTextColor(service==null?appearance.secondary:appearance.accent);
        serviceHint.setText(service==null?"首次使用需启用无障碍服务，用于截图与自动滚动。":
                capturing?service.getStatus():"切到要截取的页面，再选区并开始。可随时暂停或结束。");
        capture.setText(service==null?"启用截图服务":"显示悬浮控制");manageService.setVisibility(service==null?View.GONE:View.VISIBLE);
        long now=SystemClock.elapsedRealtime();
        if(force||(!capturing&&wasCapturing)||now-lastSessionRefresh>=2500){session=SessionStore.latest(this);lastSessionRefresh=now;}
        wasCapturing=capturing;
        if(session==null){task.setText("还没有截图");taskDetail.setText("完成采集后，在这里导出完整 PNG。");}
        else{task.setText(session.width+" × "+session.height+" px");taskDetail.setText(session.strips.size()+" 个图像分块"+(session.reason.isEmpty()?"":" · "+session.reason));}
        boolean busy=exporting;export.setEnabled(!busy&&session!=null&&!capturing);
        export.setText(busy?"正在导出 "+exportProgress+"%":"导出完整 PNG 到相册");
        String savedUri=getPreferences(0).getString("exported",null);exported=savedUri==null?null:Uri.parse(savedUri);
        open.setVisibility(exported==null?View.GONE:View.VISIBLE);
        if(!busy&&exportResult!=reportedExportResult){
            reportedExportResult=exportResult;
            if(exportError==null)Toast.makeText(this,"已保存到 Pictures/LongCapture",Toast.LENGTH_LONG).show();
            else new AlertDialog.Builder(this).setTitle("导出未完成").setMessage("任务分块已保留，可以重试。\n"+exportError).setPositiveButton("知道了",null).show();
        }
    }

    private void exportLatest(){
        CaptureService service=CaptureService.instance;
        if(exporting||session==null||(service!=null&&service.isRunning()))return;
        exporting=true;exportProgress=0;exportError=null;SessionStore snapshot=session;
        Context app=getApplicationContext();SharedPreferences preferences=getPreferences(0);refresh(false);
        // Keep the job independent of this Activity: a system theme change recreates the screen.
        new Thread(()->{
            try{Uri uri=PngExporter.export(app,snapshot,progress->exportProgress=progress);preferences.edit().putString("exported",uri.toString()).apply();}
            catch(Exception error){exportError=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
            finally{exportResult++;exporting=false;}
        },"png-export").start();
    }
}
