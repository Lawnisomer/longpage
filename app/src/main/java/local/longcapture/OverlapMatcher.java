package local.longcapture;

import java.util.Arrays;

/** Pure Java vertical translation matcher. UI and Android bitmap ownership stay outside. */
public final class OverlapMatcher {
    public static final class Result {
        public final int shift;
        public final double error;
        public final boolean trusted, stationary;
        Result(int shift, double error, boolean trusted, boolean stationary) {
            this.shift=shift; this.error=error; this.trusted=trusted; this.stationary=stationary;
        }
    }
    public static Result match(int[] previous, int[] current, int width, int height) {
        return match(previous,current,width,height,0);
    }
    /** Ignore top rows as evidence, while keeping displacements in the original frame coordinates. */
    public static Result match(int[] previous, int[] current, int width, int height,int ignoredTopRows) {
        if (previous==null || current==null || width<16 || height<64 ||
                (long)width*height!=previous.length || current.length!=previous.length ||
                ignoredTopRows<0 || ignoredTopRows>height-64)
            return new Result(0,999,false,false);
        // A trimmed translation score can discard the only moving part of a mostly
        // fixed screen. Require actual near-identity before declaring no movement.
        int start=ignoredTopRows*width,bodyPixels=previous.length-start,changed=0;
        double sum=0;
        for(int i=start;i<previous.length;i++){
            int difference=Math.abs(previous[i]-current[i]);sum+=difference;
            if(difference>3)changed++;
        }
        double unchanged=sum/bodyPixels;
        if (unchanged<0.65 && changed<=bodyPixels/2000)
            return new Result(0,unchanged,true,true);
        // Preserve the original minimum overlap fraction after excluding the toolbar.
        int min=Math.max(2,height/80), max=(int)((height-ignoredTopRows)*0.76);
        int best=min; double bestScore=Double.MAX_VALUE;
        double[] scores=new double[max+1]; Arrays.fill(scores,Double.MAX_VALUE);
        for(int shift=min;shift<=max;shift++) {
            double s=score(previous,current,width,height,shift,ignoredTopRows); scores[shift]=s;
            if(s<bestScore) { bestScore=s; best=shift; }
        }
        double second=Double.MAX_VALUE;
        int exclusion=Math.max(5,height/60);
        for(int shift=min;shift<=max;shift++) if(Math.abs(shift-best)>exclusion)
            second=Math.min(second,scores[shift]);
        boolean reliable=bestScore<10 && (second-bestScore>0.7 || second>bestScore*1.8+0.2)
                && !fixedBottom(previous,current,width,height,best,ignoredTopRows);
        return new Result(best,bestScore,reliable,false);
    }
    private static double score(int[] a,int[] b,int w,int h,int shift,int ignoredTopRows) {
        double[] tiles=new double[24]; int[] bands=new int[24],columns=new int[24]; int index=0;
        int overlapEnd=h-shift,overlap=overlapEnd-ignoredTopRows;
        if(overlap<32)return 999;
        // Keep vertical sampling density when a higher-resolution retry is used.
        // A fixed sparse grid can land between text strokes and invent a low error.
        int verticalSamples=Math.max(12,((h+559)/560)*12);
        for(int gy=0;gy<8;gy++) for(int gx=0;gx<3;gx++) {
            double sum=0,activity=0; int count=0;
            for(int ry=0;ry<verticalSamples;ry++) {
                int y=Math.min(overlapEnd-2,ignoredTopRows+(int)((gy+(ry+0.5)/verticalSamples)*overlap/8));
                for(int rx=0;rx<12;rx++) {
                    int x=Math.min(w-2,(int)((gx+(rx+0.5)/12)*w/3));
                    int p=(y+shift)*w+x,q=y*w+x;
                    int d=Math.abs(a[p]-b[q]);
                    int edge=Math.abs((a[p+w]-a[p])-(b[q+w]-b[q]));
                    activity+=Math.abs(a[p+w]-a[p])+Math.abs(b[q+w]-b[q])+
                            Math.abs(a[p+1]-a[p])+Math.abs(b[q+1]-b[q]);
                    sum+=d+edge*0.35; count++;
                }
            }
            // Blank padding alone cannot establish a seam or its uniqueness.
            if(activity/count>=1){tiles[index]=sum/count;bands[index]=gy;columns[index]=gx;index++;}
        }
        if(index<6)return 999;
        // Sorting includes the coordinates: a low residual on blank or one local
        // patch cannot support a whole-frame displacement. Independent textured
        // bands must agree, even if a large embedded animation changes its frame.
        for(int i=1;i<index;i++)for(int j=i;j>0&&tiles[j]<tiles[j-1];j--){
            double value=tiles[j];tiles[j]=tiles[j-1];tiles[j-1]=value;
            int band=bands[j];bands[j]=bands[j-1];bands[j-1]=band;
            int column=columns[j];columns[j]=columns[j-1];columns[j-1]=column;
        }
        int keep=Math.max(6,(index+1)/2),support=0,bandMask=0,columnMask=0;
        double sum=0;
        for(int i=0;i<keep;i++){
            sum+=tiles[i];
            if(tiles[i]<6){support++;bandMask|=1<<bands[i];columnMask|=1<<columns[i];}
        }
        if(support<6||Integer.bitCount(bandMask)<3||Integer.bitCount(columnMask)<2)return 999;
        return sum/keep;
    }

    // A fixed textured bottom bar would be appended into every strip. Reject it
    // rather than masking it in the score and silently producing repeated chrome.
    private static boolean fixedBottom(int[] a,int[] b,int w,int h,int shift,int ignoredTopRows){
        int rows=0,count=0;double activity=0,moved=0;
        int firstRow=Math.max(h-Math.max(8,h/6),ignoredTopRows+shift);
        for(int y=h-1;y>=firstRow;y--){
            double same=0;for(int x=1;x<w;x+=2)same+=Math.abs(a[y*w+x]-b[y*w+x]);
            if(same/((w+1)/2)>0.65)break;
            rows++;
            for(int x=1;x<w;x+=2){
                int p=y*w+x;
                activity+=Math.abs(a[p]-a[p-1])+Math.abs(a[p]-a[p-w]);
                moved+=Math.abs(a[p]-b[(y-shift)*w+x]);count++;
            }
        }
        return rows>=4&&count>0&&activity/count>=3&&moved/count>10;
    }
    public static double difference(int[] a,int[] b) {
        if(a==null || b==null || a.length!=b.length || a.length==0) return 999;
        double sum=0; for(int i=0;i<a.length;i++) sum+=Math.abs(a[i]-b[i]);
        return sum/a.length;
    }

    /** Stability only: ignore a bounded animated corner, not distributed scrolling. */
    public static double robustDifference(int[] a,int[] b,int width,int height) {
        return robustDifference(a,b,width,height,0);
    }
    /** The toolbar exclusion uses the same frame coordinates as match(). */
    public static double robustDifference(int[] a,int[] b,int width,int height,int ignoredTopRows) {
        if(a==null || b==null || width<4 || height<12 ||
                (long)width*height!=a.length || a.length!=b.length ||
                ignoredTopRows<0 || ignoredTopRows>height-12) return 999;
        double[] blocks=new double[48]; int index=0;
        int bodyHeight=height-ignoredTopRows;
        for(int gy=0;gy<12;gy++) for(int gx=0;gx<4;gx++) {
            int left=gx*width/4,right=(gx+1)*width/4;
            int top=ignoredTopRows+gy*bodyHeight/12,bottom=ignoredTopRows+(gy+1)*bodyHeight/12;
            double sum=0;
            for(int y=top;y<bottom;y++) for(int x=left;x<right;x++)
                sum+=Math.abs(a[y*width+x]-b[y*width+x]);
            blocks[index++]=sum/((right-left)*(bottom-top));
        }
        Arrays.sort(blocks);
        double sum=0;
        // At most one eighth of the equally sized spatial blocks may be ignored.
        for(int i=0;i<42;i++) sum+=blocks[i];
        return sum/42;
    }
}
