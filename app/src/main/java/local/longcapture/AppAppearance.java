package local.longcapture;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Native day/night palette shared by the activity and accessibility controls. */
final class AppAppearance {
    final boolean dark;
    final int background,surface,text,secondary,muted,accent,onAccent,outline,accentSurface;
    private final Context context;

    AppAppearance(Context context){
        this.context=context;
        dark=(context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        background=context.getColor(R.color.app_background);surface=context.getColor(R.color.app_surface);
        text=context.getColor(R.color.app_text);secondary=context.getColor(R.color.app_secondary);
        muted=context.getColor(R.color.app_muted);accent=context.getColor(R.color.app_accent);
        onAccent=context.getColor(R.color.app_on_accent);outline=context.getColor(R.color.app_outline);
        accentSurface=context.getColor(R.color.app_accent_surface);
    }
    private int dp(float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    GradientDrawable shape(int color,float radiusDp,boolean border){
        GradientDrawable result=new GradientDrawable();result.setColor(color);result.setCornerRadius(dp(radiusDp));
        if(border)result.setStroke(Math.max(1,dp(1)),outline);return result;
    }
    RippleDrawable button(boolean primary){
        StateListDrawable fill=new StateListDrawable();
        fill.addState(new int[]{-android.R.attr.state_enabled},shape(context.getColor(R.color.app_disabled_surface),14,false));
        fill.addState(new int[]{},shape(primary?accent:accentSurface,14,false));
        return new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.app_ripple)),fill,null);
    }
    void applyBars(Activity activity){
        Window window=activity.getWindow();window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);window.setNavigationBarColor(Color.TRANSPARENT);window.setNavigationBarContrastEnforced(false);
        WindowInsetsController controller=window.getInsetsController();
        if(controller!=null){int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(dark?0:mask,mask);}
    }
    void applyInsets(View root){
        root.setOnApplyWindowInsetsListener((view,insets)->{Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
        root.requestApplyInsets();
    }
}
