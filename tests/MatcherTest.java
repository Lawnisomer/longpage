package local.longcapture;
import java.util.Random;
public class MatcherTest {
    static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        int w=160,h=400,shift=173;int[] scene=new int[w*(h+shift)];Random random=new Random(42);for(int i=0;i<scene.length;i++)scene[i]=random.nextInt(256);
        int[] first=java.util.Arrays.copyOfRange(scene,0,w*h),next=java.util.Arrays.copyOfRange(scene,w*shift,w*(shift+h));
        OverlapMatcher.Result aligned=OverlapMatcher.match(first,next,w,h);require(aligned.trusted&&aligned.shift==shift,"Known displacement");
        int fastShift=240;int[] longScene=new int[w*(h+fastShift)];for(int i=0;i<longScene.length;i++)longScene[i]=random.nextInt(256);
        OverlapMatcher.Result fast=OverlapMatcher.match(java.util.Arrays.copyOfRange(longScene,0,w*h),java.util.Arrays.copyOfRange(longScene,w*fastShift,w*(fastShift+h)),w,h);
        require(fast.trusted&&fast.shift==fastShift,"60 percent displacement retains enough overlap");
        OverlapMatcher.Result same=OverlapMatcher.match(first,first,w,h);require(same.stationary,"Stationary frame");
        int[] unrelated=new int[w*h];for(int i=0;i<unrelated.length;i++)unrelated[i]=random.nextInt(256);
        require(!OverlapMatcher.match(first,unrelated,w,h).trusted,"Unrelated frames rejected");
        require(!OverlapMatcher.match(first,new int[8],w,h).trusted,"Invalid dimensions rejected");
        int[] noisy=next.clone();for(int y=0;y<h/9;y++)for(int x=0;x<w;x++)noisy[y*w+x]=random.nextInt(256);
        OverlapMatcher.Result partial=OverlapMatcher.match(first,noisy,w,h);require(partial.trusted&&partial.shift==shift,"Limited dynamic region");
        int[] blank=new int[w*h];require(OverlapMatcher.match(blank,blank,w,h).stationary,"Blank page stops instead of appending");
        System.out.println("PASS: displacement, 60 percent displacement, stationary, unrelated, dimensions, dynamic region, blank");
    }
}
