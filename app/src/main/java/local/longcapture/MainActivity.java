package local.longcapture;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private LinearLayout body;
    private TextView state,task;
    private Button export,open;
    private SessionStore session;
    private static volatile boolean exporting;
    private Uri exported;
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(0xFFF5F7F7);getWindow().setNavigationBarColor(0xFFF5F7F7);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(true);scroller.setBackgroundColor(0xFFF5F7F7);
        body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(24),dp(40),dp(24),dp(24));scroller.addView(body);setContentView(scroller);
        text("长页",32,0xFF14232D);text("一件事，把阅读页接成长图。",16,0xFF52636D);space(20);
        state=text("",14,0xFF167C68);space(12);
        button("① 启用截图与滚动服务",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("② 显示悬浮控制",()->{if(CaptureService.instance==null){Toast.makeText(this,"请先启用「长页 · 截图与滚动」",Toast.LENGTH_LONG).show();return;}CaptureService.instance.showControls();Toast.makeText(this,"现在打开阅读页，再点选区 / 开始",Toast.LENGTH_LONG).show();});
        space(12);text("使用方式",18,0xFF14232D);
        text(getString(R.string.usage_steps),14,0xFF52636D);
        text("只在按下开始后截图；图片保存在本机。第一版仅向下采集，不支持横向翻页。",13,0xFF52636D);
        space(20);text("最近一次任务",18,0xFF14232D);task=text("尚无任务",14,0xFF52636D);
        export=button("导出完整 PNG 到相册",this::exportLatest);open=button("打开已导出的图片",()->{if(exported!=null)try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(exported,"image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){Toast.makeText(this,"没有可用的图片查看器",0).show();}});open.setVisibility(View.GONE);
        String savedUri=getPreferences(0).getString("exported",null);if(savedUri!=null){exported=Uri.parse(savedUri);open.setVisibility(View.VISIBLE);}
        space(16);TextView version=text("系统要求：Android 15 及以上\n导出位置：Pictures/LongCapture\n测试版 0.1.4 · 无网络权限、无广告、无统计",12,0xFF75848B);
        version.setOnLongClickListener(v->{startActivity(new Intent(this,TestPageActivity.class));return true;});
        handleDebugIntent(getIntent());
    }
    private TextView text(String content,int size,int color){TextView view=new TextView(this);view.setText(content);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(6),0,dp(7));view.setLineSpacing(dp(3),1);body.addView(view);return view;}
    private void space(int height){View v=new View(this);body.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(0xFF14232D);b.setTextSize(15);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.bottomMargin=dp(7);body.addView(b,p);b.setOnClickListener(v->action.run());return b;}
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleDebugIntent(intent);}
    private void handleDebugIntent(Intent intent){
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)return;
        if(intent.getBooleanExtra("show_controls",false)&&CaptureService.instance!=null)CaptureService.instance.showControls();
        if(intent.getBooleanExtra("fixture",false))startActivity(new Intent(this,TestPageActivity.class));
    }
    private void refresh(){
        CaptureService service=CaptureService.instance;state.setText(service==null?"服务未启用":service.isRunning()?service.getStatus():"服务已就绪");
        session=SessionStore.latest(this);if(session==null)task.setText("尚无任务");else task.setText(session.strips.size()+" 个图像分块 · "+session.width+" × "+session.height+"\n"+session.reason);
        export.setEnabled(!exporting&&session!=null&&(service==null||!service.isRunning()));
    }
    private void exportLatest(){
        if(exporting||session==null)return;exporting=true;export.setEnabled(false);SessionStore snapshot=session;
        new Thread(()->{
            try{
                Uri uri=PngExporter.export(getApplicationContext(),snapshot,p->runOnUiThread(()->export.setText("正在导出 "+p+"%")));
                exported=uri;getPreferences(0).edit().putString("exported",uri.toString()).apply();
                runOnUiThread(()->{open.setVisibility(View.VISIBLE);Toast.makeText(this,"已保存到 Pictures/LongCapture",Toast.LENGTH_LONG).show();});
            }catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setTitle("导出未完成").setMessage("任务分块已保留，可以重试。\n"+e.getMessage()).setPositiveButton("知道了",null).show());}
            finally{exporting=false;runOnUiThread(()->{export.setText("导出完整 PNG 到相册");refresh();});}
        },"png-export").start();
    }
}
