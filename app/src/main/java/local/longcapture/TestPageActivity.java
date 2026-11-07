package local.longcapture;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.widget.*;

/** Synthetic non-private reading fixture; drawing is clipped to visible panels. */
public final class TestPageActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);
        boolean dynamicTopbar=(getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&getIntent().getBooleanExtra("fixture_topbar",false);
        if(dynamicTopbar){showDynamicTopbarFixture();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.WHITE);
        TextView title=new TextView(this);title.setText("长页测试 · 80 个编号面板");title.setTextColor(Color.BLACK);title.setTextSize(18);title.setPadding(20,16,20,16);root.addView(title);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.addView(new TestContent(),new ScrollView.LayoutParams(-1,80*720));setContentView(root);
    }
    /** Debug only: keep the scrollable window full-size so viewport fitting cannot crop the header away. */
    private void showDynamicTopbarFixture(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.WHITE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
        // Use physical pixels: default crop begins at 203px on the 1220 x 2712 test phone.
        // The remaining 97px (then 27px) of header stays inside its 235px matching exclusion band.
        scroll.setPadding(0,300,0,0);scroll.setClipToPadding(false);
        root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        scroll.addView(new TestContent(),new ScrollView.LayoutParams(-1,80*720));
        TextView title=new TextView(this);title.setTextSize(18);title.setPadding(20,16,20,16);title.setGravity(Gravity.BOTTOM);
        title.setText("动态顶栏测试 · 展开");title.setTextColor(0xFF172027);title.setBackgroundColor(0xFFE8F3EE);
        root.addView(title,new FrameLayout.LayoutParams(-1,300,Gravity.TOP));
        boolean[] collapsed={false};int[] lastStyle={0};
        scroll.setOnScrollChangeListener((view,x,y,oldX,oldY)->{
            if(y<=0)return;
            if(!collapsed[0]){
                collapsed[0]=true;scroll.setPadding(0,230,0,0);
                FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)title.getLayoutParams();params.height=230;title.setLayoutParams(params);
                android.util.Log.i("LongCapture","Fixture topbar collapsed: 300 -> 230px");
            }
            // Only change color/title after collapse; repeated height changes near a scroll
            // threshold could create layout oscillation instead of a useful test fixture.
            int style=1+(y/720)%2;
            if(style!=lastStyle[0]){
                lastStyle[0]=style;title.setText(style==1?"动态顶栏测试 · 已收起":"动态顶栏测试 · 吸顶标题");
                title.setTextColor(Color.WHITE);title.setBackgroundColor(style==1?0xFF172027:0xFF36556B);
                android.util.Log.i("LongCapture","Fixture topbar style="+style+" height=230px scroll="+y);
            }
        });
        setContentView(root);
        android.util.Log.i("LongCapture","Fixture topbar enabled: height=300px, panels=80 x 720px");
    }
    final class TestContent extends View {
        Paint paint=new Paint(3);TestContent(){super(TestPageActivity.this);}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),80*720);}
        protected void onDraw(Canvas canvas){Rect clip=canvas.getClipBounds();int start=Math.max(0,clip.top/720),end=Math.min(79,clip.bottom/720);
            for(int n=start;n<=end;n++){
                int y=n*720;paint.setColor(Color.rgb(220+(n*7)%30,220+(n*11)%30,220+(n*13)%30));canvas.drawRect(0,y,getWidth(),y+720,paint);
                paint.setColor(0xFF14232D);paint.setTextSize(64);canvas.drawText(String.format(java.util.Locale.US,"PANEL %03d",n+1),60,y+110,paint);
                for(int k=0;k<15;k++){paint.setColor(Color.rgb((n*41+k*17)%220,(n*67+k*23)%220,(n*83+k*37)%220));float x=35+(n*31+k*71)%Math.max(1,getWidth()-100);canvas.drawRect(x,y+160+k*31,x+50+(k*13)%150,y+178+k*31,paint);}
                paint.setColor(0xFF536B7A);paint.setTextSize(30);canvas.drawText("Synthetic fixture / no personal content",50,y+675,paint);
            }
        }
    }
}
