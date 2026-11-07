package local.longcapture;

import java.util.Arrays;
import java.util.Random;

public class MatcherTest {
    private static int checks;
    static void require(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}

    static int[] noise(int w,int h,Random random){
        int[] pixels=new int[w*h];
        for(int i=0;i<pixels.length;i++)pixels[i]=random.nextInt(256);
        return pixels;
    }
    static int[] frame(int[] scene,int w,int h,int top){
        return Arrays.copyOfRange(scene,top*w,(top+h)*w);
    }
    static void rectangle(int[] image,int w,int left,int top,int right,int bottom,int value){
        for(int y=top;y<bottom;y++)Arrays.fill(image,y*w+left,y*w+right,value);
    }
    static int[] headedFrame(int[] scene,int w,int h,int scroll,int headerRows,int headerValue){
        int[] image=new int[w*h];Arrays.fill(image,headerValue);
        System.arraycopy(scene,scroll*w,image,headerRows*w,(h-headerRows)*w);
        return image;
    }
    // Distinct paragraph-like strokes on a mostly plain background, rather than only noise.
    static int[] article(int w,int h){
        return article(w,h,137,0);
    }
    static int[] article(int w,int h,int seed,int phase){
        int[] image=new int[w*h];Arrays.fill(image,245);
        Random random=new Random(seed);
        for(int y=8+phase;y+6<h;y+=19){
            int end=w-8-random.nextInt(w/4);
            for(int x=8;x+3<end;x+=5+random.nextInt(4)){
                int gray=25+random.nextInt(100);
                rectangle(image,w,x,y,x+3,y+5,gray);
            }
        }
        return image;
    }

    public static void main(String[] args){
        int w=160,h=400,shift=173;
        Random random=new Random(42);
        int[] scene=noise(w,h+shift,random);
        int[] first=frame(scene,w,h,0),next=frame(scene,w,h,shift);
        OverlapMatcher.Result aligned=OverlapMatcher.match(first,next,w,h);
        require(aligned.trusted&&aligned.shift==shift,"Known displacement");

        int fastShift=240;int[] longScene=noise(w,h+fastShift,random);
        OverlapMatcher.Result fast=OverlapMatcher.match(frame(longScene,w,h,0),frame(longScene,w,h,fastShift),w,h);
        require(fast.trusted&&fast.shift==fastShift,"60 percent displacement retains enough overlap");
        require(OverlapMatcher.match(first,first,w,h).stationary,"Stationary frame");
        require(!OverlapMatcher.match(first,noise(w,h,random),w,h).trusted,"Unrelated frames rejected");
        require(!OverlapMatcher.match(first,new int[8],w,h).trusted,"Invalid dimensions rejected");

        int[] noisy=next.clone();
        for(int y=0;y<h/9;y++)for(int x=0;x<w;x++)noisy[y*w+x]=random.nextInt(256);
        OverlapMatcher.Result partial=OverlapMatcher.match(first,noisy,w,h);
        require(partial.trusted&&partial.shift==shift,"Limited dynamic region");
        int[] blank=new int[w*h];
        require(OverlapMatcher.match(blank,blank,w,h).stationary,"Blank page stops instead of appending");

        int[] localMovement=first.clone();
        for(int y=180;y<200;y++)for(int x=0;x<w;x++)localMovement[y*w+x]=random.nextInt(256);
        OverlapMatcher.Result local=OverlapMatcher.match(first,localMovement,w,h);
        require(!local.stationary&&!local.trusted,"Local motion is not a stationary full-screen frame");

        int[] paragraphs=article(w,h+shift);
        OverlapMatcher.Result text=OverlapMatcher.match(frame(paragraphs,w,h,0),frame(paragraphs,w,h,shift),w,h);
        require(text.trusted&&text.shift==shift,"Sparse text on a plain background scrolls");

        int[] fixedFirst=first.clone(),fixedNext=next.clone(),chrome=noise(w,h,random);
        // Fixed title and bottom navigation are not a whole-frame translation.
        for(int y=0;y<30;y++){System.arraycopy(chrome,y*w,fixedFirst,y*w,w);System.arraycopy(chrome,y*w,fixedNext,y*w,w);}
        for(int y=h-30;y<h;y++){System.arraycopy(chrome,y*w,fixedFirst,y*w,w);System.arraycopy(chrome,y*w,fixedNext,y*w,w);}
        OverlapMatcher.Result fixed=OverlapMatcher.match(fixedFirst,fixedNext,w,h);
        require(!fixed.stationary&&!fixed.trusted,"Fixed title and bottom bar require a proper crop");

        int sw=80,sh=144;
        int[] settled=article(sw,sh),animated=settled.clone();
        rectangle(animated,sw,60,0,80,36,0);
        require(OverlapMatcher.difference(settled,animated)>1.35,"Animated corner defeats global mean threshold");
        require(OverlapMatcher.robustDifference(settled,animated,sw,sh)<1.35,"Bounded animated corner does not prevent stability");

        int[] stabilityScene=article(sw,sh+47);
        require(OverlapMatcher.robustDifference(frame(stabilityScene,sw,sh,0),frame(stabilityScene,sw,sh,47),sw,sh)>1.35,
                "Distributed text scrolling is not called stable");
        require(OverlapMatcher.robustDifference(noise(sw,sh,random),noise(sw,sh,random),sw,sh)>1.35,
                "Whole frame change is not called stable");
        int[] dimmed=settled.clone();for(int i=0;i<dimmed.length;i++)dimmed[i]=Math.max(0,dimmed[i]-20);
        require(OverlapMatcher.robustDifference(settled,dimmed,sw,sh)>1.35,"Whole frame brightness change is not stable");
        require(OverlapMatcher.robustDifference(settled,new int[8],sw,sh)==999,"Malformed stability frame rejected");

        int[] periodic=new int[w*(h+20)];
        for(int y=0;y<h+20;y++)for(int x=0;x<w;x++)periodic[y*w+x]=(y%11<3?20:240);
        OverlapMatcher.Result ambiguous=OverlapMatcher.match(frame(periodic,w,h,0),frame(periodic,w,h,3),w,h);
        require(!ambiguous.trusted&&!ambiguous.stationary,"Repeated stripes do not establish a unique seam");

        int highH=1120,highShift=661;
        int[] highParagraphs=article(w,highH+highShift);
        OverlapMatcher.Result highText=OverlapMatcher.match(frame(highParagraphs,w,highH,0),frame(highParagraphs,w,highH,highShift),w,highH);
        require(highText.trusted&&highText.shift==highShift,"Higher-resolution retry preserves sparse-text displacement");

        // Same line spacing and margins do not imply shared content. At this
        // height a fixed sparse sampling grid can alias with the 19px line pitch.
        boolean unrelatedParagraphsRejected=true;
        for(int i=0;i<100;i++){
            OverlapMatcher.Result unrelated=OverlapMatcher.match(article(w,highH,50+i,0),article(w,highH,1000+i,i%19),w,highH);
            if(unrelated.trusted||unrelated.stationary){unrelatedParagraphsRejected=false;break;}
        }
        require(unrelatedParagraphsRejected,"Higher-resolution retry rejects 100 unrelated similarly typeset paragraphs");

        int[] highPeriodic=new int[w*(highH+20)];
        for(int y=0;y<highH+20;y++)for(int x=0;x<w;x++)highPeriodic[y*w+x]=(y%11<3?20:240);
        OverlapMatcher.Result highAmbiguous=OverlapMatcher.match(frame(highPeriodic,w,highH,0),frame(highPeriodic,w,highH,3),w,highH);
        require(!highAmbiguous.trusted&&!highAmbiguous.stationary,"Higher-resolution retry rejects repeated stripes");

        int[] highScene=noise(w,highH+highShift,random);
        int[] highFirst=frame(highScene,w,highH,0),highNext=frame(highScene,w,highH,highShift);
        for(int y=highH-40;y<highH;y++)for(int x=0;x<w;x++)highFirst[y*w+x]=highNext[y*w+x]=245;
        for(int y=highH-28;y<highH-12;y++)for(int x=12;x<w-12;x++)if(x%21<6)highFirst[y*w+x]=highNext[y*w+x]=25;
        OverlapMatcher.Result highFixed=OverlapMatcher.match(highFirst,highNext,w,highH);
        require(!highFixed.trusted&&!highFixed.stationary,"Higher-resolution retry rejects a fixed bottom bar with blank padding");

        int mask=h/10;
        int[] toolbarFirst=first.clone(),toolbarNext=next.clone();
        rectangle(toolbarFirst,w,0,0,w,mask,245);rectangle(toolbarNext,w,0,0,w,mask,20);
        OverlapMatcher.Result toolbarScroll=OverlapMatcher.match(toolbarFirst,toolbarNext,w,h,mask);
        require(toolbarScroll.trusted&&toolbarScroll.shift==shift,"Changed toolbar is excluded from scroll evidence");

        int contentScroll=160;
        int[] toolbarScene=noise(w,h+contentScroll,random);
        OverlapMatcher.Result collapsed=OverlapMatcher.match(
                headedFrame(toolbarScene,w,h,0,40,245),headedFrame(toolbarScene,w,h,contentScroll,10,25),w,h,mask);
        require(collapsed.trusted&&collapsed.shift==contentScroll+30,"Collapsed toolbar preserves original-frame displacement");
        OverlapMatcher.Result expanded=OverlapMatcher.match(
                headedFrame(toolbarScene,w,h,0,10,245),headedFrame(toolbarScene,w,h,contentScroll,40,25),w,h,mask);
        require(expanded.trusted&&expanded.shift==contentScroll-30,"Expanded toolbar preserves original-frame displacement");

        int[] onlyToolbarChanged=first.clone();rectangle(onlyToolbarChanged,w,0,0,w,mask,0);
        require(!OverlapMatcher.match(first,onlyToolbarChanged,w,h).stationary&&
                OverlapMatcher.match(first,onlyToolbarChanged,w,h,mask).stationary,
                "Toolbar-only changes do not prevent body stationary detection");
        int[] commonHeaderA=noise(w,h,random),commonHeaderB=noise(w,h,random);
        System.arraycopy(commonHeaderA,0,commonHeaderB,0,w*mask);
        require(!OverlapMatcher.match(commonHeaderA,commonHeaderB,w,h,mask).trusted,
                "A shared toolbar cannot establish a seam between unrelated bodies");
        require(!OverlapMatcher.match(frame(periodic,w,h,0),frame(periodic,w,h,3),w,h,mask).trusted,
                "Masked repeated stripes still lack a unique seam");
        require(!OverlapMatcher.match(fixedFirst,fixedNext,w,h,mask).trusted,
                "Excluding the top does not disable fixed bottom bar protection");
        require(!OverlapMatcher.match(first,next,w,h,-1).trusted&&
                !OverlapMatcher.match(first,next,w,h,h-63).trusted,
                "Invalid top masks or fewer than 64 body rows reject safely");
        OverlapMatcher.Result maskedFast=OverlapMatcher.match(frame(longScene,w,h,0),frame(longScene,w,h,fastShift),w,h,mask);
        require(maskedFast.trusted&&maskedFast.shift==fastShift,
                "Top exclusion retains enough evidence for a 60 percent displacement");
        int thinOverlapShift=280;int[] thinOverlapScene=noise(w,h+thinOverlapShift,random);
        require(!OverlapMatcher.match(frame(thinOverlapScene,w,h,0),frame(thinOverlapScene,w,h,thinOverlapShift),w,h,mask).trusted,
                "Top exclusion cannot shrink overlap below 24 percent of the usable body");

        int[] toolbarStable=settled.clone();int stableMask=(sh+9)/10;
        rectangle(toolbarStable,sw,0,0,sw,stableMask,0);
        require(OverlapMatcher.robustDifference(settled,toolbarStable,sw,sh,stableMask)==0,
                "Toolbar changes do not block body stability");
        require(OverlapMatcher.robustDifference(frame(stabilityScene,sw,sh,0),frame(stabilityScene,sw,sh,47),sw,sh,stableMask)>1.35,
                "Excluding the toolbar does not call scrolling body stable");
        require(OverlapMatcher.robustDifference(settled,toolbarStable,sw,sh,-1)==999&&
                OverlapMatcher.robustDifference(settled,toolbarStable,sw,sh,sh-11)==999,
                "Invalid stability masks reject safely");

        int highMask=highH/10;
        OverlapMatcher.Result highToolbarScroll=OverlapMatcher.match(
                headedFrame(highScene,w,highH,0,highMask,245),headedFrame(highScene,w,highH,highShift,highMask/3,25),w,highH,highMask);
        require(highToolbarScroll.trusted&&highToolbarScroll.shift==highShift+highMask-highMask/3,
                "Higher-resolution masked matching preserves collapsed-header displacement");
        boolean maskedParagraphsRejected=true;
        for(int i=0;i<100;i++){
            OverlapMatcher.Result unrelated=OverlapMatcher.match(article(w,highH,50+i,0),article(w,highH,1000+i,i%19),w,highH,highMask);
            if(unrelated.trusted||unrelated.stationary){maskedParagraphsRejected=false;break;}
        }
        require(maskedParagraphsRejected,"Top exclusion rejects 100 unrelated similarly typeset bodies");

        System.out.println("PASS: "+checks+" matcher and stability checks");
    }
}
