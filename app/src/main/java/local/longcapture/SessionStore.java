package local.longcapture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Disk strips plus atomic manifest; one decoded strip at a time is enough to export. */
public final class SessionStore {
    public final File directory;
    public final List<String> strips=new ArrayList<>();
    public int width,height;
    public long bytes;
    public String source="", reason="";
    public SessionStore(Context context,String source) throws IOException {
        directory=new File(context.getFilesDir(),"sessions/"+new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date()));
        if(!directory.mkdirs()) throw new IOException("无法创建任务目录");
        this.source=source;
    }
    private SessionStore(File directory){this.directory=directory;}
    public void append(Bitmap frame,int top) throws IOException {
        if(top<0 || top>=frame.getHeight()) throw new IOException("裁剪范围无效");
        if(width!=0 && width!=frame.getWidth()) throw new IOException("画面宽度改变，已停止");
        if((long)height+frame.getHeight()-top>1_000_000) throw new IOException("已达到单次高度上限，可导出后继续");
        if(bytes>512L*1024*1024 || directory.getUsableSpace()<100L*1024*1024) throw new IOException("存储空间预算已满");
        String name=String.format(Locale.US,"strip-%05d.png",strips.size());
        File file=new File(directory,name);
        Bitmap part=Bitmap.createBitmap(frame,0,top,frame.getWidth(),frame.getHeight()-top);
        try(FileOutputStream out=new FileOutputStream(file)) {
            if(!part.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("图片写入失败");
        } finally { if(part!=frame) part.recycle(); }
        width=frame.getWidth(); height+=frame.getHeight()-top; bytes+=file.length(); strips.add(name); save();
    }
    public void save() throws IOException {
        try {
            JSONObject root=new JSONObject().put("width",width).put("height",height).put("bytes",bytes)
                    .put("source",source).put("reason",reason).put("strips",new JSONArray(strips));
            File temp=new File(directory,"manifest.tmp"), dest=new File(directory,"manifest.json");
            try(FileOutputStream out=new FileOutputStream(temp)) { out.write(root.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            android.system.Os.rename(temp.getAbsolutePath(),dest.getAbsolutePath());
        } catch(Exception e){throw new IOException(e);}
    }
    public static SessionStore latest(Context context) {
        File[] dirs=new File(context.getFilesDir(),"sessions").listFiles(File::isDirectory);
        if(dirs==null) return null;
        Arrays.sort(dirs,Comparator.comparing(File::getName).reversed());
        for(File dir:dirs) try {
            File manifest=new File(dir,"manifest.json");
            JSONObject root=new JSONObject(new String(java.nio.file.Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));
            SessionStore store=new SessionStore(dir); store.width=root.getInt("width"); store.height=root.getInt("height");
            store.bytes=root.getLong("bytes"); store.source=root.optString("source"); store.reason=root.optString("reason");
            JSONArray names=root.getJSONArray("strips");
            for(int i=0;i<names.length();i++){String name=names.getString(i);if(!name.matches("strip-[0-9]{5}\\.png"))throw new IOException("Invalid strip");store.strips.add(name);}
            if(!store.strips.isEmpty())return store;
        } catch(Exception ignored) {}
        return null;
    }
    public Bitmap decode(String name){return BitmapFactory.decodeFile(new File(directory,name).getAbsolutePath());}
}
