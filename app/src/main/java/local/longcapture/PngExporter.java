package local.longcapture;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Bounded-memory truecolor PNG encoder. No final full-height Bitmap allocation. */
public final class PngExporter {
    public interface Progress {void update(int percent);}
    public static Uri export(Context context,SessionStore session,Progress progress) throws Exception {
        if(session.height<=0)throw new IOException("没有可导出的图片");
        ContentValues values=new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,"LongCapture-"+session.directory.getName()+".png");
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LongCapture");
        values.put(MediaStore.Images.Media.IS_PENDING,1);
        ContentResolver resolver=context.getContentResolver();
        Uri uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IOException("无法创建相册文件");
        try(OutputStream target=resolver.openOutputStream(uri)) {
            if(target==null)throw new IOException("无法写入相册");
            write(session,target,progress);
        } catch(Exception e){resolver.delete(uri,null,null);throw e;}
        values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
        if(resolver.update(uri,values,null,null)!=1){resolver.delete(uri,null,null);throw new IOException("相册发布失败");}
        return uri;
    }
    static void write(SessionStore s,OutputStream target,Progress progress) throws IOException {
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(target,65536));
        out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(header);
        h.writeInt(s.width);h.writeInt(s.height);h.write(new byte[]{8,2,0,0,0});chunk(out,"IHDR",header.toByteArray());
        IdatStream idat=new IdatStream(out); Deflater deflater=new Deflater(3);
        int rowCount=0; int[] pixels=new int[s.width];byte[] row=new byte[1+3*s.width];
        try {
            DeflaterOutputStream compressed=new DeflaterOutputStream(idat,deflater,32768);
            for(String name:s.strips) {
                Bitmap bitmap=s.decode(name);if(bitmap==null)throw new IOException("任务分块丢失："+name);
                try {
                    if(bitmap.getWidth()!=s.width)throw new IOException("分块宽度错误");
                    for(int y=0;y<bitmap.getHeight();y++) {
                        bitmap.getPixels(pixels,0,s.width,0,y,s.width,1);int p=1;row[0]=0;
                        for(int color:pixels){row[p++]=(byte)(color>>16);row[p++]=(byte)(color>>8);row[p++]=(byte)color;}
                        compressed.write(row);rowCount++;
                        if(rowCount%128==0)progress.update((int)((long)rowCount*100/s.height));
                    }
                } finally {bitmap.recycle();}
            }
            if(rowCount!=s.height)throw new IOException("任务行数不一致");
            compressed.finish();idat.flush();chunk(out,"IEND",new byte[0]);out.flush();progress.update(100);
        } finally {deflater.end();}
    }
    private static void chunk(DataOutputStream out,String type,byte[] data)throws IOException {
        byte[] t=type.getBytes(StandardCharsets.US_ASCII);CRC32 crc=new CRC32();crc.update(t);crc.update(data);
        out.writeInt(data.length);out.write(t);out.write(data);out.writeInt((int)crc.getValue());
    }
    private static final class IdatStream extends OutputStream {
        final DataOutputStream out;final byte[] buffer=new byte[65536];int length;
        IdatStream(DataOutputStream out){this.out=out;}
        public void write(int b)throws IOException {buffer[length++]=(byte)b;if(length==buffer.length)flush();}
        public void write(byte[] b,int offset,int count)throws IOException {
            while(count>0){int n=Math.min(count,buffer.length-length);System.arraycopy(b,offset,buffer,length,n);length+=n;offset+=n;count-=n;if(length==buffer.length)flush();}
        }
        public void flush()throws IOException {if(length>0){byte[] data=java.util.Arrays.copyOf(buffer,length);chunk(out,"IDAT",data);length=0;}}
    }
}
