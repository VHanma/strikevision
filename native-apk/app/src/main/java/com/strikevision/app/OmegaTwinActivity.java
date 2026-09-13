package com.strikevision.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OmegaTwinActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);build();}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(6,8,12));LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,36,24,36);scroll.addView(root);setContentView(scroll);
        root.addView(tv("Ω DIGITAL FIGHTER TWIN",24,Color.WHITE,Typeface.BOLD));root.addView(tv("Your baseline is built from your own high-confidence strikes, technique by technique, side by side, stance by stance.",12,Color.LTGRAY,Typeface.NORMAL));
        Map<String,Group> groups=new HashMap<>();int sessions=0,total=0;
        File dir=new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"StrikeVisionOmega/sessions");File[] files=dir.listFiles((d,n)->n.endsWith(".json"));
        if(files!=null){java.util.Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File f:files){try{JSONObject r=new JSONObject(Files.readString(f.toPath(), StandardCharsets.UTF_8));String stance=r.optJSONObject("profile")==null?"ORTHODOX":r.optJSONObject("profile").optString("stance","ORTHODOX");JSONArray a=r.optJSONArray("strikes");if(a==null)continue;sessions++;for(int i=0;i<a.length();i++){JSONObject s=a.getJSONObject(i);double conf=s.optDouble("confidence",0);if(conf<.45)continue;String key=stance+" • "+s.optString("side")+" • "+s.optString("technique","Strike");Group g=groups.get(key);if(g==null){g=new Group(key);groups.put(key,g);}Sample x=new Sample();x.mph=s.optDouble("peakMph");x.chain=s.optDouble("chainScore");x.conf=conf;x.recoil=s.optDouble("recoilMph");x.ttp=s.optDouble("timeToPeakMs");x.eff=s.optDouble("pathEfficiency");x.time=f.lastModified();g.a.add(x);total++;}}catch(Throwable ignored){}}}
        root.addView(tv("Twin memory: "+sessions+" analyzed sessions • "+total+" accepted high-confidence strikes",13,Color.rgb(188,255,50),Typeface.BOLD));
        if(groups.isEmpty()){root.addView(tv("Twin is dormant. Run High-Speed Lab and analyze a few clean strikes to grow the model.",14,Color.WHITE,Typeface.NORMAL));return;}
        List<Group> list=new ArrayList<>(groups.values());list.sort((a,b)->Integer.compare(b.a.size(),a.a.size()));
        for(Group g:list){Stats s=stats(g);String stage=s.n>=15?"STABLE":s.n>=5?"FORMING":"SEED";String delta=s.sdMph>0?String.format(Locale.US,"%+.1f SD",(s.lastMph-s.avgMph)/s.sdMph):"baseline forming";String body=String.format(Locale.US,"%s  [%s]\nSamples: %d\nBaseline peak: %.1f ± %.1f mph\nChain: %.0f/100 • recoil ratio %.0f%% • path efficiency %.0f%%\nTime-to-peak: %.0f ms\nBest-template signature: %.1f mph • chain %.0f • confidence %.0f%%\nLatest vs twin: %s",g.key,stage,s.n,s.avgMph,s.sdMph,s.avgChain,s.avgRecoilRatio*100,s.avgEff,s.avgTtp,s.best.mph,s.best.chain,s.best.conf*100,delta);TextView card=tv(body,12,Color.WHITE,Typeface.NORMAL);card.setPadding(18,18,18,18);card.setBackgroundColor(Color.rgb(17,22,29));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,7,0,7);root.addView(card,lp);}
        root.addView(tv("Twin logic: each technique has its own distribution instead of one global record. The best template favors speed only when chain quality, path efficiency and confidence agree with it.",11,Color.GRAY,Typeface.NORMAL));
    }
    private Stats stats(Group g){Stats s=new Stats();s.n=g.a.size();double m=0,c=0,r=0,t=0,e=0;for(Sample x:g.a){m+=x.mph;c+=x.chain;r+=x.mph>0?x.recoil/x.mph:0;t+=x.ttp;e+=x.eff;if(s.best==null||quality(x)>quality(s.best))s.best=x;}s.avgMph=m/s.n;s.avgChain=c/s.n;s.avgRecoilRatio=r/s.n;s.avgTtp=t/s.n;s.avgEff=e/s.n;double v=0;for(Sample x:g.a)v+=(x.mph-s.avgMph)*(x.mph-s.avgMph);s.sdMph=Math.sqrt(v/Math.max(1,s.n-1));s.lastMph=g.a.get(g.a.size()-1).mph;return s;}
    private double quality(Sample x){return x.mph*Math.max(.3,x.chain/100.0)*Math.max(.3,x.conf)*Math.max(.4,x.eff/100.0);}
    private TextView tv(String s,int z,int c,int st){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);t.setTypeface(Typeface.DEFAULT,st);t.setPadding(0,8,0,8);return t;}
    static class Group{String key;List<Sample>a=new ArrayList<>();Group(String k){key=k;}}
    static class Sample{double mph,chain,conf,recoil,ttp,eff;long time;}
    static class Stats{int n;double avgMph,sdMph,avgChain,avgRecoilRatio,avgTtp,avgEff,lastMph;Sample best;}
}
