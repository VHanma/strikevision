package com.strikevision.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OmegaReplayActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView video;
    private Overlay overlay;
    private LinearLayout evidenceBox;
    private TextView status;
    private MediaPlayer player;
    private final List<FrameLite> frames = new ArrayList<>();
    private final List<StrikeLite> strikes = new ArrayList<>();
    private int sourceW=360,sourceH=640;

    @Override public void onCreate(Bundle state){super.onCreate(state);String path=getIntent().getStringExtra(OmegaOfflineAnalyzerActivity.EXTRA_SESSION_PATH);if(path==null)showLibrary();else openSession(path);}
    @Override protected void onDestroy(){super.onDestroy();ui.removeCallbacksAndMessages(null);}

    private void showLibrary(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(6,8,12));LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,36,24,36);scroll.addView(root);setContentView(scroll);
        root.addView(tv("Ω REPLAY LIBRARY",24,Color.WHITE,Typeface.BOLD));
        File dir=new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"StrikeVisionOmega/sessions");File[] files=dir.listFiles((d,n)->n.endsWith(".json"));
        if(files==null||files.length==0){root.addView(tv("No analyzed sessions yet. Record one in High-Speed Lab.",14,Color.LTGRAY,Typeface.NORMAL));return;}
        Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
        for(File f:files){Button b=button(f.getName().replace("session_","").replace(".json",""));b.setOnClickListener(v->{Intent i=new Intent(this,OmegaReplayActivity.class);i.putExtra(OmegaOfflineAnalyzerActivity.EXTRA_SESSION_PATH,f.getAbsolutePath());startActivity(i);});root.addView(b,new LinearLayout.LayoutParams(-1,88));}
    }

    private void openSession(String sessionPath){
        try{
            JSONObject root=new JSONObject(OmegaIo.read(new File(sessionPath)));
            String videoPath=root.optString("videoPath","");sourceW=root.optInt("frameWidth",360);sourceH=root.optInt("frameHeight",640);
            JSONArray jf=root.getJSONArray("frames");for(int i=0;i<jf.length();i++){JSONObject o=jf.getJSONObject(i);FrameLite f=new FrameLite();f.t=o.optLong("t");f.c=o.optDouble("c");JSONArray p=o.optJSONArray("p");if(p!=null)for(int k=0;k+2<p.length();k+=3)f.p.put(p.getInt(k),new PointF((float)p.getDouble(k+1),(float)p.getDouble(k+2)));frames.add(f);}
            JSONArray js=root.optJSONArray("strikes");if(js!=null)for(int i=0;i<js.length();i++){JSONObject o=js.getJSONObject(i);StrikeLite s=new StrikeLite();s.name=o.optString("technique","Strike");s.side=o.optString("side","");s.peak=o.optLong("peakMs");s.mph=o.optDouble("peakMph");s.chain=o.optDouble("chainScore");s.conf=o.optDouble("confidence");s.recoil=o.optDouble("recoilMph");s.eff=o.optDouble("pathEfficiency");JSONArray e=o.optJSONArray("evidence");if(e!=null)for(int k=0;k<e.length();k++)s.evidence.add(e.optString(k));strikes.add(s);}
            buildReplay(videoPath,root.optJSONObject("summary"));
        }catch(Throwable t){TextView x=tv("Replay could not open: "+t.getClass().getSimpleName()+"\n"+t.getMessage(),14,Color.WHITE,Typeface.NORMAL);x.setBackgroundColor(Color.BLACK);setContentView(x);}
    }

    private void buildReplay(String videoPath,JSONObject summary){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(5,7,10));LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,24,16,30);scroll.addView(root);setContentView(scroll);
        TextView title=tv("Ω EVIDENCE REPLAY",22,Color.WHITE,Typeface.BOLD);title.setGravity(Gravity.CENTER);root.addView(title);
        status=tv(summary==null?"Synchronized reconstruction":String.format(Locale.US,"%d strikes | %.1f mph fastest | %.0f%% retention | %.0f%% avg confidence",summary.optInt("count"),summary.optDouble("fastestMph"),summary.optDouble("retentionPct"),summary.optDouble("avgConfidence")*100),12,Color.rgb(188,255,50),Typeface.BOLD);status.setGravity(Gravity.CENTER);root.addView(status);
        FrameLayout stage=new FrameLayout(this);stage.setBackgroundColor(Color.BLACK);video=new VideoView(this);video.setVideoPath(videoPath);overlay=new Overlay();stage.addView(video,new FrameLayout.LayoutParams(-1,-1));stage.addView(overlay,new FrameLayout.LayoutParams(-1,-1));root.addView(stage,new LinearLayout.LayoutParams(-1,720));
        video.setOnPreparedListener(mp->{player=mp;mp.setLooping(true);video.start();startSync();});
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);Button play=small("Play/Pause");play.setOnClickListener(v->{if(video.isPlaying())video.pause();else video.start();});controls.addView(play);for(float sp:new float[]{.25f,.5f,1f}){Button b=small(sp+"x");b.setOnClickListener(v->setSpeed(sp));controls.addView(b);}root.addView(controls);
        root.addView(tv("Tap a strike below to jump directly to the evidence window. Skeleton + weapon trails follow the analyzed frames, not decorative animation.",11,Color.LTGRAY,Typeface.NORMAL));
        evidenceBox=new LinearLayout(this);evidenceBox.setOrientation(LinearLayout.VERTICAL);root.addView(evidenceBox);populateEvidence();
    }

    private void populateEvidence(){for(StrikeLite s:strikes){StringBuilder b=new StringBuilder();b.append(s.side).append(' ').append(s.name).append("  •  ").append(String.format(Locale.US,"%.1f mph | chain %.0f | conf %.0f%%\n",s.mph,s.chain,s.conf*100));for(String e:s.evidence)b.append("• ").append(e).append('\n');Button x=button(b.toString().trim());x.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);x.setOnClickListener(v->{int seek=(int)Math.max(0,s.peak-700);video.seekTo(seek);video.start();status.setText("Evidence jump: "+s.side+" "+s.name+" @ "+s.peak+" ms");});evidenceBox.addView(x,new LinearLayout.LayoutParams(-1,-2));}}
    private void setSpeed(float s){try{if(player!=null){PlaybackParams p=player.getPlaybackParams();p.setSpeed(s);player.setPlaybackParams(p);status.setText("Replay speed "+s+"x");}}catch(Throwable ignored){}}
    private void startSync(){ui.post(new Runnable(){@Override public void run(){if(video!=null&&overlay!=null){overlay.timeMs=video.getCurrentPosition();overlay.invalidate();}ui.postDelayed(this,16);}});}

    private class Overlay extends View{
        Paint bone=new Paint(3),joint=new Paint(3),trail=new Paint(3),txt=new Paint(3);long timeMs;
        final int[][] edges={{11,13},{13,15},{12,14},{14,16},{11,12},{11,23},{12,24},{23,24},{23,25},{25,27},{24,26},{26,28}};
        Overlay(){super(OmegaReplayActivity.this);bone.setColor(Color.argb(220,180,255,45));bone.setStrokeWidth(5);joint.setColor(Color.WHITE);trail.setColor(Color.argb(190,70,170,255));trail.setStrokeWidth(4);txt.setColor(Color.WHITE);txt.setTextSize(36);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(frames.isEmpty())return;int idx=nearest(timeMs);FrameLite f=frames.get(idx);RectMap r=mapRect(getWidth(),getHeight());for(int[] e:edges){PointF a=f.p.get(e[0]),b=f.p.get(e[1]);if(a!=null&&b!=null)c.drawLine(r.x(a.x),r.y(a.y),r.x(b.x),r.y(b.y),bone);}for(PointF p:f.p.values())c.drawCircle(r.x(p.x),r.y(p.y),5,joint);for(int id:new int[]{15,16,27,28}){PointF prev=null;for(int j=Math.max(0,idx-14);j<=idx;j++){PointF p=frames.get(j).p.get(id);if(p!=null){if(prev!=null)c.drawLine(r.x(prev.x),r.y(prev.y),r.x(p.x),r.y(p.y),trail);prev=p;}}}StrikeLite near=nearestStrike(timeMs);if(near!=null&&Math.abs(near.peak-timeMs)<650)c.drawText(near.side+" "+near.name+"  "+String.format(Locale.US,"%.1f mph",near.mph),20,48,txt);}
    }

    private int nearest(long t){int lo=0,hi=frames.size()-1;while(lo<hi){int m=(lo+hi)/2;if(frames.get(m).t<t)lo=m+1;else hi=m;}if(lo>0&&Math.abs(frames.get(lo-1).t-t)<Math.abs(frames.get(lo).t-t))lo--;return lo;}
    private StrikeLite nearestStrike(long t){StrikeLite best=null;long d=Long.MAX_VALUE;for(StrikeLite s:strikes){long x=Math.abs(s.peak-t);if(x<d){d=x;best=s;}}return best;}
    private RectMap mapRect(int vw,int vh){float src=sourceW/(float)Math.max(1,sourceH),dst=vw/(float)Math.max(1,vh),w,h,x,y;if(dst>src){h=vh;w=h*src;x=(vw-w)/2;y=0;}else{w=vw;h=w/src;x=0;y=(vh-h)/2;}return new RectMap(x,y,w,h);}
    private TextView tv(String s,int z,int c,int st){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);t.setTypeface(Typeface.DEFAULT,st);t.setPadding(8,8,8,8);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setBackground(round(Color.rgb(18,23,31)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(3,5,3,5);b.setLayoutParams(lp);return b;}
    private Button small(String s){Button b=button(s);b.setGravity(Gravity.CENTER);b.setLayoutParams(new LinearLayout.LayoutParams(0,80,1));return b;}
    private GradientDrawable round(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(20);g.setStroke(1,Color.rgb(52,60,72));return g;}
    static class FrameLite{long t;double c;Map<Integer,PointF> p=new HashMap<>();}
    static class StrikeLite{String name,side;long peak;double mph,chain,conf,recoil,eff;List<String> evidence=new ArrayList<>();}
    static class RectMap{float x,y,w,h;RectMap(float x,float y,float w,float h){this.x=x;this.y=y;this.w=w;this.h=h;}float x(float n){return x+n*w;}float y(float n){return y+n*h;}}
}
