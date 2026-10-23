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
        if (width<16 || height<64 || previous.length!=width*height || current.length!=previous.length)
            return new Result(0,999,false,false);
        double unchanged=score(previous,current,width,height,0);
        if (unchanged<0.65) return new Result(0,unchanged,true,true);
        int min=Math.max(2,height/80), max=(int)(height*0.76);
        int best=min; double bestScore=Double.MAX_VALUE;
        double[] scores=new double[max+1]; Arrays.fill(scores,Double.MAX_VALUE);
        for(int shift=min;shift<=max;shift++) {
            double s=score(previous,current,width,height,shift); scores[shift]=s;
            if(s<bestScore) { bestScore=s; best=shift; }
        }
        double second=Double.MAX_VALUE;
        int exclusion=Math.max(5,height/60);
        for(int shift=min;shift<=max;shift++) if(Math.abs(shift-best)>exclusion)
            second=Math.min(second,scores[shift]);
        boolean reliable=bestScore<10 && (second-bestScore>0.7 || second>bestScore*1.8+0.2);
        return new Result(best,bestScore,reliable,false);
    }
    private static double score(int[] a,int[] b,int w,int h,int shift) {
        double[] tiles=new double[24]; int index=0;
        int overlap=h-shift;
        for(int gy=0;gy<8;gy++) for(int gx=0;gx<3;gx++) {
            double sum=0; int count=0;
            for(int ry=0;ry<5;ry++) {
                int y=Math.min(overlap-2,(int)((gy+(ry+0.5)/5)*overlap/8));
                for(int rx=0;rx<12;rx++) {
                    int x=Math.min(w-2,(int)((gx+(rx+0.5)/12)*w/3));
                    int p=(y+shift)*w+x,q=y*w+x;
                    int d=Math.abs(a[p]-b[q]);
                    int edge=Math.abs((a[p+w]-a[p])-(b[q+w]-b[q]));
                    sum+=d+edge*0.35; count++;
                }
            }
            tiles[index++]=sum/count;
        }
        Arrays.sort(tiles); double sum=0;
        // Ignore a bounded minority of dynamic blocks, but retain spatially distributed evidence.
        for(int i=0;i<20;i++) sum+=tiles[i];
        return sum/20;
    }
    public static double difference(int[] a,int[] b) {
        if(a.length!=b.length || a.length==0) return 999;
        double sum=0; for(int i=0;i<a.length;i++) sum+=Math.abs(a[i]-b[i]);
        return sum/a.length;
    }
}
