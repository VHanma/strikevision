package com.strikevision.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public class OmegaResearchActivity extends Activity {
    private String raw="";
    @Override public void onCreate(Bundle state){super.onCreate(state);build();}
    private void build(){
        ScrollView sc=new ScrollView(this);sc.setBackgroundColor(Color.rgb(5,7,10));LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,36,24,36);sc.addView(root);setContentView(sc);
        root.addView(tv("Ω RESEARCH MODE",24,Color.WHITE,Typeface.BOLD));
        File dir=new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"StrikeVisionOmega/sessions");File[] fs=dir.listFiles((d,n)->n.endsWith(".json"));if(fs==null||fs.length==0){root.addView(tv("No reconstructed session exists yet.",14,Color.LTGRAY,Typeface.NORMAL));return;}Arrays.sort(fs,Comparator.comparingLong(File::lastModified).reversed());
        try{raw=OmegaIo.read(fs[0]);JSONObject r=new JSONObject(raw);JSONObject a=r.optJSONObject("algorithm"),c=r.optJSONObject("calibration"),s=r.optJSONObject("summary"),p=r.optJSONObject("profile");JSONArray strikes=r.optJSONArray("strikes"),frames=r.optJSONArray("frames");
            String head=String.format(Locale.US,"LATEST SESSION\nCapture: %d fps • reconstruction: %d fps • high-speed: %s\nFrames retained: %d • strikes: %d\nScale: %.1f px/m • calibration quality: %.0f%%\nFastest: %.1f mph • avg chain: %.0f • retention: %.0f%%\n\nALGORITHM\nPose: %s\nInterpolation: %s\nPose-anchor rate: %s Hz\nSmoothing: %s\n\nMEASUREMENT CLASSES\nMeasured: %s\nDerived: %s\nEstimated: %s",r.optInt("captureFps"),r.optInt("analysisFps"),r.optBoolean("highSpeed"),frames==null?0:frames.length(),strikes==null?0:strikes.length(),c==null?0:c.optDouble("pxPerMeter"),c==null?0:c.optDouble("quality")*100,s==null?0:s.optDouble("fastestMph"),s==null?0:s.optDouble("avgChain"),s==null?0:s.optDouble("retentionPct"),a==null?"":a.optString("poseEngine"),a==null?"":a.optString("interpolation"),a==null?"":a.opt("poseAnchorRateHz"),a==null?"":a.optString("velocitySmoothing"),a==null?"":a.optString("measured"),a==null?"":a.optString("derived"),a==null?"":a.optString("estimated"));root.addView(card(head));
            if(strikes!=null&&strikes.length()>0){JSONObject best=strikes.getJSONObject(0);for(int i=1;i<strikes.length();i++)if(strikes.getJSONObject(i).optDouble("peakMph")>best.optDouble("peakMph"))best=strikes.getJSONObject(i);double mph=best.optDouble("peakMph"),mps=mph/2.2369362921,kg=(p==null?150:p.optDouble("weightLb",150))/2.2046226218;boolean hand="Hand".equals(best.optString("limb"));double lowFrac=hand?.045:.11,highFrac=hand?.09:.22,measured=best.optDouble("effectiveMassLb")/2.2046226218;double low=.5*(kg*lowFrac)*mps*mps*.7375621493,omega=.5*measured*mps*mps*.7375621493,high=.5*(kg*highFrac)*mps*mps*.7375621493;root.addView(card(String.format(Locale.US,"IMPACT MODEL SANDBOX • fastest strike\n%s %s • %.1f mph\nConservative effective-mass model: %.1f ft·lbf\nΩ chain-adjusted model: %.1f ft·lbf\nHigh-coupling model: %.1f ft·lbf\n\nThese are modeled energy potentials, not directly measured target impact.",best.optString("side"),best.optString("technique"),mph,low,omega,high)));}
            Button copy=new Button(this);copy.setText("Copy full raw session JSON");copy.setAllCaps(false);copy.setOnClickListener(v->{ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("StrikeVision Omega session",raw));Toast.makeText(this,"Raw session copied",Toast.LENGTH_SHORT).show();});root.addView(copy);
            String preview=raw.length()>5000?raw.substring(0,5000)+"\n... [raw JSON truncated on screen; copy button contains full session]":raw;TextView rawView=tv(preview,9,Color.GRAY,Typeface.MONOSPACE.getStyle());root.addView(rawView);
        }catch(Throwable t){root.addView(tv("Research view error: "+t.getMessage(),13,Color.WHITE,Typeface.NORMAL));}
    }
    private TextView card(String s){TextView t=tv(s,12,Color.WHITE,Typeface.NORMAL);t.setPadding(18,18,18,18);t.setBackgroundColor(Color.rgb(17,22,29));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,8,0,8);t.setLayoutParams(lp);return t;}
    private TextView tv(String s,int z,int c,int st){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);t.setTypeface(Typeface.DEFAULT,st);t.setPadding(0,8,0,8);return t;}
}
