package com.strikevision.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OmegaOfflineAnalyzerActivity extends Activity {
    public static final String EXTRA_VIDEO_PATH = "omega_video_path";
    public static final String EXTRA_CAPTURE_FPS = "omega_capture_fps";
    public static final String EXTRA_HIGH_SPEED = "omega_high_speed";
    public static final String EXTRA_SESSION_PATH = "omega_session_path";

    private static final double MPS_TO_MPH = 2.2369362921;
    private static final double KG_TO_LB = 2.2046226218;
    private static final double J_TO_FTLB = 0.7375621493;
    private static final double KGMPS_TO_LBMFTPS = 7.233013851;
    private static final double N_TO_LBF = 0.2248089431;
    private static final int[] IDS = { PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE };
    private static final int[] WEAPONS = { PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status, detail;
    private ProgressBar progress;
    private String videoPath;
    private int requestedFps;
    private boolean highSpeed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        videoPath = getIntent().getStringExtra(EXTRA_VIDEO_PATH);
        requestedFps = getIntent().getIntExtra(EXTRA_CAPTURE_FPS, 30);
        highSpeed = getIntent().getBooleanExtra(EXTRA_HIGH_SPEED, false);
        buildUi();
        worker.execute(this::analyze);
    }

    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Color.rgb(6,8,12));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,42,28,42); scroll.addView(root); setContentView(scroll);
        root.addView(tv("Ω FRAME-TRUTH RECONSTRUCTION", 22, Color.WHITE, Typeface.BOLD));
        status = tv("Opening recording...", 14, Color.rgb(188,255,50), Typeface.BOLD); root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000); root.addView(progress, new LinearLayout.LayoutParams(-1, 60));
        detail = tv("Pose anchors are sampled periodically. Intermediate frames are reconstructed with local block-matched optical flow, then corrected at the next pose anchor.", 12, Color.LTGRAY, Typeface.NORMAL); root.addView(detail);
    }

    private void analyze() {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        PoseDetector detector = PoseDetection.getClient(new PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build());
        try {
            if (videoPath == null) throw new IllegalArgumentException("Missing video path");
            mmr.setDataSource(videoPath);
            long durationMs = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            int rotation = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            int rawW = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int rawH = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            float metaFps = 0f;
            if (Build.VERSION.SDK_INT >= 24) { try { metaFps = Float.parseFloat(String.valueOf(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE))); } catch (Throwable ignored) {} }
            int captureFps = metaFps >= 20 ? Math.round(metaFps) : requestedFps;
            captureFps = Math.max(24, Math.min(240, captureFps));
            int analysisFps = highSpeed ? Math.min(120, captureFps) : Math.min(60, captureFps);
            analysisFps = Math.max(24, analysisFps);
            long intervalUs = Math.max(1, 1_000_000L / analysisFps);
            int frameCount = (int) Math.min(2200, Math.max(1, durationMs * analysisFps / 1000L));
            int poseStride = Math.max(1, Math.round(analysisFps / 30f));
            Profile profile = loadProfile();
            List<FrameData> frames = new ArrayList<>();
            Map<Integer, PointF> anchors = new HashMap<>();
            GrayFrame previousGray = null;
            double flowAgreementAccum = 0; int flowAgreementN = 0;
            int targetW = 360;
            int targetH = rawW > 0 && rawH > 0 ? Math.max(240, Math.round(targetW * (rotation == 90 || rotation == 270 ? rawW/(float)rawH : rawH/(float)rawW))) : 640;
            targetH = Math.min(720, targetH);
            final int fAnalysisFps = analysisFps;
            runOnUiThread(() -> status.setText(String.format(Locale.US, "Decoding %d frames/sec | capture metadata %d fps | pose every %d frame(s)", fAnalysisFps, captureFps, poseStride)));

            for (int i=0; i<frameCount && !Thread.currentThread().isInterrupted(); i++) {
                long tUs = i * intervalUs; if (tUs > durationMs * 1000L) break;
                Bitmap bmp = extractFrame(mmr, tUs, targetW, targetH, rotation); if (bmp == null) continue;
                GrayFrame gray = GrayFrame.fromBitmap(bmp); FrameData fd = new FrameData(tUs / 1000L, bmp.getWidth(), bmp.getHeight());
                boolean poseFrame = (i % poseStride == 0) || anchors.isEmpty();
                if (poseFrame) {
                    Map<Integer, PointF> predicted = copyPoints(anchors);
                    try {
                        Pose pose = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)), 2500, TimeUnit.MILLISECONDS);
                        double poseConf = 0; int confN = 0;
                        for (int id : IDS) {
                            PoseLandmark lm = pose.getPoseLandmark(id);
                            if (lm != null && lm.getInFrameLikelihood() >= 0.30f) {
                                PointF p = clampPoint(lm.getPosition(), bmp.getWidth(), bmp.getHeight()); anchors.put(id, p); fd.points.put(id, new PointF(p.x,p.y)); poseConf += lm.getInFrameLikelihood(); confN++;
                                PointF pred = predicted.get(id); if (pred != null) { double err = dist(pred, p); double diag = Math.hypot(bmp.getWidth(), bmp.getHeight()); double agree = clamp(1.0 - err / Math.max(1.0, diag * 0.08), 0, 1); flowAgreementAccum += agree; flowAgreementN++; }
                            }
                        }
                        fd.confidence = confN == 0 ? 0.25 : poseConf / confN; fd.source = "pose";
                    } catch (Throwable t) {
                        if (previousGray != null && !anchors.isEmpty()) { double c = flowAdvance(previousGray, gray, anchors); fd.points.putAll(copyPoints(anchors)); fd.confidence = c * 0.70; fd.source = "flow-fallback"; }
                    }
                } else if (previousGray != null && !anchors.isEmpty()) { double c = flowAdvance(previousGray, gray, anchors); fd.points.putAll(copyPoints(anchors)); fd.confidence = c; fd.source = "flow"; }
                if (!fd.points.isEmpty()) frames.add(fd); previousGray = gray; bmp.recycle();
                if (i % Math.max(1, frameCount/100) == 0) { final int pr = (int)(1000.0 * i / frameCount); final int fi = i; runOnUiThread(() -> { progress.setProgress(pr); detail.setText("Reconstructing frame " + fi + " / " + frameCount); }); }
            }

            if (frames.size() < 8) throw new IllegalStateException("Too few trackable frames");
            Calibration calibration = calibrate(frames, profile);
            List<MetricFrame> metrics = buildMetrics(frames, calibration.pxPerMeter);
            List<Strike> strikes = detectStrikes(frames, metrics, profile, calibration, captureFps, analysisFps, flowAgreementN == 0 ? 0 : flowAgreementAccum/flowAgreementN);
            SessionSummary summary = summarize(strikes);
            JSONObject session = buildSessionJson(frames, strikes, summary, profile, calibration, captureFps, analysisFps, durationMs);
            File out = saveSession(session);
            runOnUiThread(() -> { progress.setProgress(1000); status.setText(String.format(Locale.US, "Reconstruction complete: %d strikes | %.1f mph fastest | calibration %.0f%%", strikes.size(), summary.fastestMph, calibration.quality*100)); detail.setText("Opening synchronized evidence replay. Tap any coaching item there to jump directly to the frames that produced it."); Intent i = new Intent(this, OmegaReplayActivity.class); i.putExtra(EXTRA_SESSION_PATH, out.getAbsolutePath()); startActivity(i); });
        } catch (Throwable t) { runOnUiThread(() -> { status.setText("Analysis stopped: " + t.getClass().getSimpleName()); detail.setText(String.valueOf(t.getMessage())); }); }
        finally { try { detector.close(); } catch (Throwable ignored) {} try { mmr.release(); } catch (Throwable ignored) {} }
    }

    private Bitmap extractFrame(MediaMetadataRetriever mmr, long tUs, int targetW, int targetH, int rotation) {
        try {
            Bitmap b;
            if (Build.VERSION.SDK_INT >= 27) b = mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH);
            else { Bitmap raw = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST); b = raw == null ? null : Bitmap.createScaledBitmap(raw, targetW, targetH, true); if (raw != null && raw != b) raw.recycle(); }
            if (b == null) return null;
            if (rotation == 90 || rotation == 180 || rotation == 270) { Matrix m = new Matrix(); m.postRotate(rotation); Bitmap r = Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true); if (r != b) b.recycle(); b = r; }
            int w = 360; int h = Math.max(240, Math.round(w * b.getHeight()/(float)Math.max(1,b.getWidth())));
            if (b.getWidth() != w) { Bitmap s = Bitmap.createScaledBitmap(b,w,h,true); if (s != b) b.recycle(); b = s; }
            return b;
        } catch (Throwable t) { return null; }
    }

    private double flowAdvance(GrayFrame prev, GrayFrame cur, Map<Integer, PointF> anchors) {
        double total=0; int n=0;
        for (int id : new ArrayList<>(anchors.keySet())) { PointF p = anchors.get(id); FlowMove m = blockMatch(prev,cur,p, isHand(id) || id==PoseLandmark.LEFT_ANKLE || id==PoseLandmark.RIGHT_ANKLE ? 9 : 6); if (m != null) { p.x = clampf(p.x + m.dx, 0, cur.w-1); p.y = clampf(p.y + m.dy, 0, cur.h-1); total += m.confidence; n++; } }
        return n==0 ? 0.25 : total/n;
    }

    private FlowMove blockMatch(GrayFrame a, GrayFrame b, PointF p, int search) {
        if (a.w!=b.w || a.h!=b.h) return null; int x=Math.round(p.x), y=Math.round(p.y), r=3; if (x<r+search || y<r+search || x>=a.w-r-search || y>=a.h-r-search) return null;
        long best=Long.MAX_VALUE, second=Long.MAX_VALUE; int bx=0, by=0;
        for (int dy=-search;dy<=search;dy++) for (int dx=-search;dx<=search;dx++) { long sad=0; for (int yy=-r;yy<=r;yy++) for (int xx=-r;xx<=r;xx++) { int av=a.y[(y+yy)*a.w+(x+xx)]&255; int bv=b.y[(y+dy+yy)*b.w+(x+dx+xx)]&255; sad += Math.abs(av-bv); } if (sad<best) { second=best; best=sad; bx=dx; by=dy; } else if (sad<second) second=sad; }
        double max=(2*r+1)*(2*r+1)*255.0; double texture = second==Long.MAX_VALUE ? 0.4 : clamp((second-best)/(double)Math.max(1,second),0,1); double fit=clamp(1-best/(max*0.55),0,1); return new FlowMove(bx,by,clamp(0.35*texture+0.65*fit,0.05,0.99));
    }

    private Calibration calibrate(List<FrameData> frames, Profile p) {
        List<Double> shoulderPx=new ArrayList<>(); for (FrameData f:frames) { PointF l=f.points.get(PoseLandmark.LEFT_SHOULDER), r=f.points.get(PoseLandmark.RIGHT_SHOULDER); if (l!=null&&r!=null) { double d=dist(l,r); if (d>15) shoulderPx.add(d); } }
        Collections.sort(shoulderPx); double med= shoulderPx.isEmpty()?0:shoulderPx.get(shoulderPx.size()/2); double meters=p.shoulderIn*0.0254; double scale=med>10?med/Math.max(.18,meters):500; double spread=0; if (shoulderPx.size()>6) spread=percentile(shoulderPx,.85)-percentile(shoulderPx,.15); double q=med>10?clamp(1-spread/Math.max(1,med)*1.7,.45,.98):.30; return new Calibration(scale,q,med,spread);
    }

    private List<MetricFrame> buildMetrics(List<FrameData> frames,double pxPerM) {
        List<MetricFrame> out=new ArrayList<>(); Map<Integer,Double> smooth=new HashMap<>();
        for (int i=0;i<frames.size();i++) { FrameData f=frames.get(i); MetricFrame m=new MetricFrame(f.tMs); if (i>0) { FrameData prev=frames.get(i-1); double dt=(f.tMs-prev.tMs)/1000.0; if (dt>0 && dt<.25) { PointF tc=torso(f), tp=torso(prev); for (int id:IDS) { PointF c=f.points.get(id), p=prev.points.get(id); if (c==null||p==null) continue; double dx=c.x-p.x-(tc!=null&&tp!=null?tc.x-tp.x:0); double dy=c.y-p.y-(tc!=null&&tp!=null?tc.y-tp.y:0); double v=Math.hypot(dx,dy)/pxPerM/dt; double sm=smooth.containsKey(id)?smooth.get(id)*.35+v*.65:v; smooth.put(id,sm); m.speed.put(id,sm); } PointF sc=shoulderCenter(f), sp=shoulderCenter(prev); PointF hc=hipCenter(f), hp=hipCenter(prev); if(sc!=null&&sp!=null)m.shoulderSpeed=dist(sc,sp)/pxPerM/dt; if(hc!=null&&hp!=null)m.hipSpeed=dist(hc,hp)/pxPerM/dt; } } out.add(m); }
        return out;
    }

    private List<Strike> detectStrikes(List<FrameData> frames,List<MetricFrame> metrics,Profile profile,Calibration cal,int captureFps,int analysisFps,double flowAgreement) {
        List<Strike> out=new ArrayList<>();
        for(int weapon:WEAPONS){ boolean hand=isHand(weapon); double threshold=hand?1.7:2.3; int start=-1,peak=-1; double peakV=0; int below=0;
            for(int i=1;i<metrics.size();i++){ double v=metrics.get(i).speed.getOrDefault(weapon,0.0); if(start<0){ if(v>=threshold){ start=i; peak=i; peakV=v; below=0; } } else { if(v>peakV){peakV=v;peak=i;} if(v<threshold*.55) below++; else below=0; long dur=frames.get(i).tMs-frames.get(start).tMs; if((below>=Math.max(2,analysisFps/30) && i>peak) || dur>900){ int end=i; if(peakV<35 && peakV>=threshold) { Strike s=finishStrike(frames,metrics,start,peak,end,weapon,profile,cal,captureFps,flowAgreement); if(s!=null) out.add(s); } start=-1;peak=-1;peakV=0;below=0; } } }
        }
        out.sort(Comparator.comparingLong(s->s.peakMs)); return out;
    }

    private Strike finishStrike(List<FrameData> f,List<MetricFrame> m,int start,int peak,int end,int weapon,Profile p,Calibration cal,int captureFps,double flowAgreement) {
        boolean hand=isHand(weapon); String side=(weapon==PoseLandmark.LEFT_WRIST||weapon==PoseLandmark.LEFT_ANKLE)?"Left":"Right"; int support=supportJoint(weapon); double peakV=m.get(peak).speed.getOrDefault(weapon,0.0), sum=0; int n=0; double path=0,recoil=0;
        PointF torsoStart=torso(f.get(start)), torsoPeak=torso(f.get(peak)); PointF wStart=f.get(start).points.get(weapon), wPeak=f.get(peak).points.get(weapon); if(wStart==null||wPeak==null)return null;
        for(int i=start;i<=end;i++){ double v=m.get(i).speed.getOrDefault(weapon,0.0); if(v>0){sum+=v;n++;} if(i>peak)recoil=Math.max(recoil,v); if(i>start){PointF a=f.get(i-1).points.get(weapon),b=f.get(i).points.get(weapon); if(a!=null&&b!=null)path+=dist(a,b);} }
        PointF rs=new PointF(wStart.x-(torsoStart==null?0:torsoStart.x),wStart.y-(torsoStart==null?0:torsoStart.y)); PointF rp=new PointF(wPeak.x-(torsoPeak==null?0:torsoPeak.x),wPeak.y-(torsoPeak==null?0:torsoPeak.y)); double straight=dist(rs,rp); double efficiency=path>1?clamp(straight/path*100,0,100):100;
        long peakMs=f.get(peak).tMs, startMs=f.get(start).tMs,endMs=f.get(end).tMs; int hipPeak=findPeakIndex(m,start,peak,-100); int shoulderPeak=findPeakIndex(m,start,peak,-101); int supportPeak=findPeakIndex(m,start,peak,support); long hipLead=peakMs-m.get(hipPeak).tMs, shoulderLead=peakMs-m.get(shoulderPeak).tMs, supportLead=peakMs-m.get(supportPeak).tMs;
        double chain=100; if(m.get(hipPeak).tMs>m.get(shoulderPeak).tMs)chain-=18; if(m.get(shoulderPeak).tMs>m.get(supportPeak).tMs)chain-=12; if(supportPeak>peak)chain-=18; if(hipLead>500)chain-=10; if(shoulderLead>400)chain-=8; if(supportLead>300)chain-=8; chain=clamp(chain,25,100);
        String technique=classify(f,peak,weapon,p.stance); double kg=p.weightLb/KG_TO_LB; double base=hand?.065:.16; double chainMult=.70+.45*(chain/100.0); double effKg=Math.min(kg*(hand?.11:.28),kg*base*chainMult); double momentum=effKg*peakV, energy=.5*effKg*peakV*peakV;
        double confidenceAvg=0;int cn=0;for(int i=start;i<=end;i++){confidenceAvg+=f.get(i).confidence;cn++;} double fpsQuality=clamp(captureFps/120.0,.55,1); if(!highSpeed)fpsQuality=Math.min(fpsQuality,.82); double conf=clamp((cn==0?.4:confidenceAvg/cn)*.42+cal.quality*.28+fpsQuality*.18+flowAgreement*.12,.18,.99);
        Strike s=new Strike(); s.side=side;s.technique=technique;s.limb=hand?"Hand":"Leg";s.startMs=startMs;s.peakMs=peakMs;s.endMs=endMs;s.peakMph=peakV*MPS_TO_MPH;s.avgMph=(n==0?0:sum/n)*MPS_TO_MPH;s.recoilMph=recoil*MPS_TO_MPH;s.timeToPeakMs=peakMs-startMs;s.durationMs=endMs-startMs;s.extensionIn=limbExtensionIn(f.get(peak),weapon,cal.pxPerMeter);s.pathEfficiency=efficiency;s.chainScore=chain;s.confidence=conf;s.hipLeadMs=hipLead;s.shoulderLeadMs=shoulderLead;s.supportLeadMs=supportLead;s.effectiveMassLb=effKg*KG_TO_LB;s.momentum=momentum*KGMPS_TO_LBMFTPS;s.energyFtLb=energy*J_TO_FTLB;s.forceLow=momentum/.030*N_TO_LBF;s.forceHigh=momentum/.010*N_TO_LBF; addEvidence(s); return s;
    }

    private int findPeakIndex(List<MetricFrame> m,int a,int b,int key){int best=a;double v=-1;for(int i=a;i<=b;i++){double x=key==-100?m.get(i).hipSpeed:key==-101?m.get(i).shoulderSpeed:m.get(i).speed.getOrDefault(key,0.0);if(x>v){v=x;best=i;}}return best;}
    private void addEvidence(Strike s){ if(s.hipLeadMs < s.shoulderLeadMs) s.evidence.add("Hip peak arrived after the shoulder peak: proximal sequence leak."); if(s.supportLeadMs<0) s.evidence.add("Support-joint peak occurred after weapon peak: distal timing leak."); if(s.pathEfficiency<72) s.evidence.add("Weapon path was curved/long relative to displacement: path-efficiency leak."); if(s.recoilMph < s.peakMph*.35) s.evidence.add("Recovery velocity was low relative to outgoing peak: recoil bottleneck."); if(s.confidence<.65) s.evidence.add("Measurement confidence is limited; review visibility/calibration at this timestamp."); if(s.chainScore>=88 && s.pathEfficiency>=80) s.evidence.add("Strong kinetic-chain order and efficient weapon path on this strike."); if(s.evidence.isEmpty()) s.evidence.add("Balanced strike signature with no major rule-based leak detected."); }

    private String classify(List<FrameData> f,int peak,int weapon,String stance){ boolean hand=isHand(weapon); int start=Math.max(0,peak-5); PointF a=f.get(start).points.get(weapon),b=f.get(peak).points.get(weapon); if(a==null||b==null)return hand?"Punch":"Kick"; double dx=b.x-a.x,dy=b.y-a.y,adx=Math.abs(dx),ady=Math.abs(dy); String side=(weapon==PoseLandmark.LEFT_WRIST||weapon==PoseLandmark.LEFT_ANKLE)?"Left":"Right"; boolean lead=("ORTHODOX".equals(stance)&&"Left".equals(side))||("SOUTHPAW".equals(stance)&&"Right".equals(side)); if(hand){ int elbow=weapon==PoseLandmark.LEFT_WRIST?PoseLandmark.LEFT_ELBOW:PoseLandmark.RIGHT_ELBOW; int shoulder=weapon==PoseLandmark.LEFT_WRIST?PoseLandmark.LEFT_SHOULDER:PoseLandmark.RIGHT_SHOULDER; double ang=jointAngle(f.get(peak).points.get(shoulder),f.get(peak).points.get(elbow),f.get(peak).points.get(weapon)); if(dy<0 && ady>adx*.60) return lead?"Lead Uppercut":"Rear Uppercut"; if(ang>0&&ang<138&&adx>ady*.75)return lead?"Lead Hook":"Rear Hook"; if(dy>0&&ady>adx*.65)return lead?"Lead Overhand":"Rear Overhand"; return lead?"Jab":"Cross"; } else { if(adx>ady*1.35)return lead?"Lead Front/Side Kick":"Rear Front/Side Kick"; return lead?"Lead Round Kick":"Rear Round Kick"; } }
    private double limbExtensionIn(FrameData f,int weapon,double scale){int root=weapon==PoseLandmark.LEFT_WRIST?PoseLandmark.LEFT_SHOULDER:weapon==PoseLandmark.RIGHT_WRIST?PoseLandmark.RIGHT_SHOULDER:weapon==PoseLandmark.LEFT_ANKLE?PoseLandmark.LEFT_HIP:PoseLandmark.RIGHT_HIP;PointF a=f.points.get(root),b=f.points.get(weapon);return a==null||b==null?0:dist(a,b)/scale/0.0254;}
    private double jointAngle(PointF a,PointF b,PointF c){if(a==null||b==null||c==null)return -1;double ux=a.x-b.x,uy=a.y-b.y,vx=c.x-b.x,vy=c.y-b.y;double den=Math.hypot(ux,uy)*Math.hypot(vx,vy);if(den<1)return -1;return Math.toDegrees(Math.acos(clamp((ux*vx+uy*vy)/den,-1,1)));}

    private SessionSummary summarize(List<Strike> s){SessionSummary x=new SessionSummary();x.count=s.size();if(s.isEmpty())return x;double sum=0,chain=0,conf=0,left=0,right=0;int ln=0,rn=0;for(Strike a:s){x.fastestMph=Math.max(x.fastestMph,a.peakMph);sum+=a.peakMph;chain+=a.chainScore;conf+=a.confidence;if("Left".equals(a.side)){left+=a.peakMph;ln++;}else{right+=a.peakMph;rn++;}}x.averageMph=sum/s.size();x.avgChain=chain/s.size();x.avgConfidence=conf/s.size();if(ln>0&&rn>0){double l=left/ln,r=right/rn;x.asymmetry=Math.abs(l-r)/Math.max(1,Math.max(l,r))*100;}int half=Math.max(1,s.size()/2);double first=0,second=0;for(int i=0;i<half;i++)first+=s.get(i).peakMph;for(int i=half;i<s.size();i++)second+=s.get(i).peakMph;x.firstHalf=first/half;x.secondHalf=s.size()==half?x.firstHalf:second/(s.size()-half);x.retention=x.firstHalf>0?x.secondHalf/x.firstHalf*100:100;return x;}

    private JSONObject buildSessionJson(List<FrameData> frames,List<Strike> strikes,SessionSummary sum,Profile p,Calibration cal,int captureFps,int analysisFps,long durationMs)throws Exception{
        JSONObject root=new JSONObject();root.put("schema",4);root.put("createdAt",System.currentTimeMillis());root.put("videoPath",videoPath);root.put("captureFps",captureFps);root.put("analysisFps",analysisFps);root.put("highSpeed",highSpeed);root.put("durationMs",durationMs);root.put("frameWidth",frames.get(0).w);root.put("frameHeight",frames.get(0).h);
        JSONObject alg=new JSONObject();alg.put("poseEngine","ML Kit pose single-image anchors");alg.put("interpolation","Omega SAD block-matched optical flow");alg.put("poseAnchorRateHz",Math.min(30,analysisFps));alg.put("velocitySmoothing","EMA 0.65 new / 0.35 previous");alg.put("measured","trajectory and timing");alg.put("derived","velocity, momentum, kinetic-energy potential and chain timing");alg.put("estimated","effective mass and force window");root.put("algorithm",alg);
        JSONObject profile=new JSONObject();profile.put("weightLb",p.weightLb);profile.put("heightIn",p.heightIn);profile.put("armIn",p.armIn);profile.put("shoulderIn",p.shoulderIn);profile.put("stance",p.stance);root.put("profile",profile);
        JSONObject c=new JSONObject();c.put("pxPerMeter",cal.pxPerMeter);c.put("quality",cal.quality);c.put("shoulderMedianPx",cal.median);c.put("shoulderSpreadPx",cal.spread);root.put("calibration",c);
        JSONArray jf=new JSONArray();for(FrameData f:frames){JSONObject o=new JSONObject();o.put("t",f.tMs);o.put("c",f.confidence);o.put("s",f.source);JSONArray a=new JSONArray();for(int id:IDS){PointF q=f.points.get(id);if(q!=null){a.put(id);a.put(q.x/f.w);a.put(q.y/f.h);}}o.put("p",a);jf.put(o);}root.put("frames",jf);
        JSONArray js=new JSONArray();for(Strike s:strikes){JSONObject o=new JSONObject();o.put("side",s.side);o.put("limb",s.limb);o.put("technique",s.technique);o.put("startMs",s.startMs);o.put("peakMs",s.peakMs);o.put("endMs",s.endMs);o.put("peakMph",s.peakMph);o.put("avgMph",s.avgMph);o.put("recoilMph",s.recoilMph);o.put("timeToPeakMs",s.timeToPeakMs);o.put("durationMs",s.durationMs);o.put("extensionIn",s.extensionIn);o.put("pathEfficiency",s.pathEfficiency);o.put("chainScore",s.chainScore);o.put("confidence",s.confidence);o.put("hipLeadMs",s.hipLeadMs);o.put("shoulderLeadMs",s.shoulderLeadMs);o.put("supportLeadMs",s.supportLeadMs);o.put("effectiveMassLb",s.effectiveMassLb);o.put("momentum",s.momentum);o.put("energyFtLb",s.energyFtLb);o.put("forceLow",s.forceLow);o.put("forceHigh",s.forceHigh);JSONArray e=new JSONArray();for(String z:s.evidence)e.put(z);o.put("evidence",e);js.put(o);}root.put("strikes",js);
        JSONObject ss=new JSONObject();ss.put("count",sum.count);ss.put("fastestMph",sum.fastestMph);ss.put("averageMph",sum.averageMph);ss.put("avgChain",sum.avgChain);ss.put("avgConfidence",sum.avgConfidence);ss.put("asymmetryPct",sum.asymmetry);ss.put("firstHalfMph",sum.firstHalf);ss.put("secondHalfMph",sum.secondHalf);ss.put("retentionPct",sum.retention);root.put("summary",ss);return root;
    }

    private File saveSession(JSONObject j)throws Exception{File base=new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"StrikeVisionOmega/sessions");if(!base.exists())base.mkdirs();File out=new File(base,"session_"+System.currentTimeMillis()+".json");try(FileWriter w=new FileWriter(out)){w.write(j.toString());}return out;}
    private Profile loadProfile(){SharedPreferences s=getSharedPreferences("strikevision_omega_v3",MODE_PRIVATE);Profile p=new Profile();p.weightLb=s.getFloat("weightLb",150f);p.heightIn=s.getFloat("heightIn",67f);p.armIn=s.getFloat("armIn",27f);p.shoulderIn=s.getFloat("shoulderIn",17f);p.stance=s.getString("stance","ORTHODOX");return p;}
    private PointF torso(FrameData f){return center(shoulderCenter(f),hipCenter(f));}
    private PointF shoulderCenter(FrameData f){return center(f.points.get(PoseLandmark.LEFT_SHOULDER),f.points.get(PoseLandmark.RIGHT_SHOULDER));}
    private PointF hipCenter(FrameData f){return center(f.points.get(PoseLandmark.LEFT_HIP),f.points.get(PoseLandmark.RIGHT_HIP));}
    private PointF center(PointF a,PointF b){if(a==null)return b==null?null:new PointF(b.x,b.y);if(b==null)return new PointF(a.x,a.y);return new PointF((a.x+b.x)/2f,(a.y+b.y)/2f);}
    private boolean isHand(int id){return id==PoseLandmark.LEFT_WRIST||id==PoseLandmark.RIGHT_WRIST;}
    private int supportJoint(int id){if(id==PoseLandmark.LEFT_WRIST)return PoseLandmark.LEFT_ELBOW;if(id==PoseLandmark.RIGHT_WRIST)return PoseLandmark.RIGHT_ELBOW;if(id==PoseLandmark.LEFT_ANKLE)return PoseLandmark.LEFT_KNEE;return PoseLandmark.RIGHT_KNEE;}
    private Map<Integer,PointF> copyPoints(Map<Integer,PointF> in){Map<Integer,PointF> o=new HashMap<>();for(Map.Entry<Integer,PointF> e:in.entrySet())o.put(e.getKey(),new PointF(e.getValue().x,e.getValue().y));return o;}
    private PointF clampPoint(PointF p,int w,int h){return new PointF(clampf(p.x,0,w-1),clampf(p.y,0,h-1));}
    private double percentile(List<Double> a,double p){if(a.isEmpty())return 0;return a.get(Math.max(0,Math.min(a.size()-1,(int)Math.round((a.size()-1)*p))));}
    private double dist(PointF a,PointF b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
    private float clampf(float x,float a,float b){return Math.max(a,Math.min(b,x));}
    private long parseLong(String s,long d){try{return Long.parseLong(s);}catch(Throwable t){return d;}}
    private TextView tv(String s,int sz,int color,int style){TextView t=new TextView(this);t.setText(s);t.setTextSize(sz);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,style);t.setPadding(0,8,0,8);return t;}

    static class Profile{double weightLb,heightIn,armIn,shoulderIn;String stance;}
    static class Calibration{double pxPerMeter,quality,median,spread;Calibration(double a,double b,double c,double d){pxPerMeter=a;quality=b;median=c;spread=d;}}
    static class FrameData{long tMs;int w,h;double confidence=.25;String source="flow";Map<Integer,PointF> points=new HashMap<>();FrameData(long t,int w,int h){tMs=t;this.w=w;this.h=h;}}
    static class MetricFrame{long tMs;Map<Integer,Double> speed=new HashMap<>();double hipSpeed,shoulderSpeed;MetricFrame(long t){tMs=t;}}
    static class FlowMove{int dx,dy;double confidence;FlowMove(int x,int y,double c){dx=x;dy=y;confidence=c;}}
    static class Strike{String side,limb,technique;long startMs,peakMs,endMs,timeToPeakMs,durationMs,hipLeadMs,shoulderLeadMs,supportLeadMs;double peakMph,avgMph,recoilMph,extensionIn,pathEfficiency,chainScore,confidence,effectiveMassLb,momentum,energyFtLb,forceLow,forceHigh;List<String> evidence=new ArrayList<>();}
    static class SessionSummary{int count;double fastestMph,averageMph,avgChain,avgConfidence,asymmetry,firstHalf,secondHalf,retention;}
    static class GrayFrame{int w,h;byte[] y;GrayFrame(int w,int h,byte[] y){this.w=w;this.h=h;this.y=y;}static GrayFrame fromBitmap(Bitmap b){int w=b.getWidth(),h=b.getHeight();int[] p=new int[w*h];b.getPixels(p,0,w,0,0,w,h);byte[] y=new byte[p.length];for(int i=0;i<p.length;i++){int c=p[i],r=(c>>16)&255,g=(c>>8)&255,bl=c&255;y[i]=(byte)((77*r+150*g+29*bl)>>8);}return new GrayFrame(w,h,y);}}
}
