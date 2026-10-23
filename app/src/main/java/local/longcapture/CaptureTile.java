package local.longcapture;
import android.service.quicksettings.TileService;
import android.app.PendingIntent;
import android.content.Intent;
public final class CaptureTile extends TileService {
    @Override public void onClick(){super.onClick();if(CaptureService.instance!=null){CaptureService.instance.showControls();}else{startActivityAndCollapse(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));}}
}
