#!/usr/bin/env python3
import base64, hashlib, json, pathlib, struct, zlib

ROOT = pathlib.Path('HANMA_COMBO_CALLER_FULL_APK')
ASSETS = ROOT/'app/src/main/assets'
JAVA_DIR = ROOT/'app/src/main/java/com/hanmaclan/combatengine'
RES = ROOT/'app/src/main/res/values'
for p in (ASSETS, JAVA_DIR, RES): p.mkdir(parents=True, exist_ok=True)

def dec_file(path):
    s = pathlib.Path(path).read_text().strip()
    return base64.b64decode(s, validate=True)

def extract_local_entries(blob):
    out = {}
    pos = 0
    sig = b'PK\x03\x04'
    while True:
        i = blob.find(sig, pos)
        if i < 0 or i + 30 > len(blob): break
        try:
            fields = struct.unpack_from('<IHHHHHIIIHH', blob, i)
            _, ver, flags, method, mt, md, crc, csz, usz, nlen, xlen = fields
            name_start = i + 30
            name_end = name_start + nlen
            extra_end = name_end + xlen
            if extra_end > len(blob): break
            name = blob[name_start:name_end].decode('utf-8', 'replace')
            data_end = extra_end + csz
            if data_end <= len(blob):
                raw = blob[extra_end:data_end]
                try:
                    if method == 0: data = raw
                    elif method == 8: data = zlib.decompress(raw, -15)
                    else: data = None
                    if data is not None and len(data) == usz:
                        out[name] = data
                except Exception:
                    pass
            pos = max(i + 4, data_end)
        except Exception:
            pos = i + 4
    return out

# Verified-good transfer regions. Each 14,000 base64 chars decodes to 10,500 ZIP bytes.
first = b''.join(dec_file(f'hanma_source.part{i:02d}') for i in (0,1,2))
mid = b''.join(dec_file(f'hanma_source.part{i:02d}') for i in (4,5))
entries = {}
entries.update(extract_local_entries(first))
entries.update(extract_local_entries(mid))

wanted = {
 'HANMA_COMBO_CALLER_FULL_APK/app/src/main/assets/compiled_vault.json': ('compiled_vault.json','3c269e092d01e7ecb3528008e5d681ea5720a510f551d38043a5ccdf32b4fafb'),
 'HANMA_COMBO_CALLER_FULL_APK/app/src/main/assets/index.html': ('index.html','77f13bedbbcd4f26cb120e80537fdde7f1f083fd0c3e882245e088357915f2d9'),
 'HANMA_COMBO_CALLER_FULL_APK/app/src/main/assets/app.js': ('app.js','1303063e487e1325ee866e51210205aef6728d41229fce10fb47e6fa090bf553'),
}
for src,(dst,sha) in wanted.items():
    if src not in entries:
        raise SystemExit(f'Missing verified entry: {src}')
    data=entries[src]
    got=hashlib.sha256(data).hexdigest()
    if got != sha:
        raise SystemExit(f'Hash mismatch for {dst}: {got}')
    (ASSETS/dst).write_bytes(data)
    print('verified', dst, got)

compiled = json.loads((ASSETS/'compiled_vault.json').read_text())
full = compiled['fullVault']
missing = compiled.get('missingCombatVault', {})
assert full['declared']['callableBanks'] == 77
assert full['declared']['callableItems'] == 1135
assert len(full['banks']) == 77
assert len(full['presets']) == 37
assert sum(len(b.get('items',[])) for b in full['banks'].values()) == 1109

# Runtime bridges regenerated from the machine-readable master vault.
(ASSETS/'full_vault.js').write_text('(function(g){"use strict";g.HANMA_FULL_VAULT='+json.dumps(full,separators=(',',':'),ensure_ascii=False)+';})(window);\n')
(ASSETS/'recovered_content.js').write_text('(function(g){"use strict";const v='+json.dumps(full,separators=(',',':'),ensure_ascii=False)+';g.HANMA_RECOVERED={version:v.version,banks:Object.fromEntries(Object.entries(v.banks).map(([k,x])=>[k,x.items||[]])),bankManifest:Object.fromEntries(Object.entries(v.banks).map(([k,x])=>[k,x.declaredCount||0])),declared:v.declared};})(window);\n')
(ASSETS/'hanma_missing_combat_vault.js').write_text('(function(g){"use strict";g.HANMA_MISSING_COMBAT_VAULT='+json.dumps(missing,separators=(',',':'),ensure_ascii=False)+';})(window);\n')
(ASSETS/'hanma_missing_vault_runtime.js').write_text('''(function(g){
"use strict";
const v=g.HANMA_MISSING_COMBAT_VAULT||{};
const banned=/^(EXACT|STRUCTURE|UNRESOLVED|OPAQUE)$/i;
function spoken(a){return String(a||"").replace(/→|->/g," ").replace(/\\.(mp4|mp3|wav)$/ig,"").replace(/\\b(EXACT|STRUCTURE|UNRESOLVED|OPAQUE|filename|source slot)\\b/ig,"").replace(/\\b([1-8])\\b/g,m=>({1:"One",2:"Two",3:"Three",4:"Four",5:"Five",6:"Six",7:"Seven",8:"Eight"}[m])).replace(/\\s+/g," ").trim();}
function actions(x){if(Array.isArray(x))return x.flatMap(actions);if(typeof x==='string'&&!banned.test(x))return x.split(/\\s*(?:→|->)\\s*/).map(spoken).filter(Boolean);return [];}
g.HANMA_MISSING_VAULT_RUNTIME={version:"1.0-integrated",vault:v,spoken,actions,ttsRules:(v.metadata&&v.metadata.ttsRules)||{}};
})(window);
''')

# Mobile UI styling, rebuilt around the existing intact index.html.
(ASSETS/'styles.css').write_text(r'''*{box-sizing:border-box}html,body{margin:0;background:#070707;color:#f5f5f5;font-family:Inter,Roboto,Arial,sans-serif}body{padding-bottom:88px}button,select,input{font:inherit}.brand{position:sticky;top:0;z-index:9;display:flex;justify-content:space-between;align-items:center;padding:14px 16px;background:rgba(7,7,7,.96);border-bottom:1px solid #541010}.brand h1{margin:0;font-size:20px;letter-spacing:1.5px}.brand small{color:#aaa}.sigil{font-size:30px;color:#e41f26}.wrap{max-width:980px;margin:auto;padding:12px}.card{background:#101010;border:1px solid #2b2b2b;border-radius:15px;padding:14px;margin:10px 0;box-shadow:0 10px 28px #0008}.title{font-weight:900;letter-spacing:.7px}.mini,small{color:#aaa}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.grow{flex:1;min-width:180px}.grid,.grid2,.grid3{display:grid;gap:10px}.grid{grid-template-columns:repeat(auto-fit,minmax(170px,1fr))}.grid2{grid-template-columns:repeat(2,minmax(0,1fr))}.grid3{grid-template-columns:repeat(3,minmax(0,1fr))}label{font-size:12px;color:#bbb;display:block;margin-bottom:5px}select,input{width:100%;background:#171717;color:#fff;border:1px solid #404040;border-radius:10px;padding:11px}button{border:1px solid #5f1719;background:#201112;color:#fff;border-radius:12px;padding:12px 14px;font-weight:800}button.primary{background:#b3151b;border-color:#ed3137}button:active{transform:scale(.98)}.controls{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.reactionGrid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.clock{font-size:48px;font-weight:1000;letter-spacing:2px}.cue{font-size:25px;line-height:1.35;font-weight:900;min-height:82px;display:flex;align-items:center}.badges{display:flex;gap:7px;flex-wrap:wrap}.badge,.provenance{display:inline-block;background:#211314;border:1px solid #562125;border-radius:999px;padding:5px 9px;font-size:11px}.vaultGrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:8px}.vaultItem{padding:10px;border:1px solid #333;border-radius:10px;background:#151515}.vaultItem small{display:block;margin:4px 0}.statline{color:#ddd;margin-top:6px}.tabs{display:flex;gap:6px;overflow:auto;padding-bottom:4px}.hidden{display:none!important}@media(max-width:600px){.wrap{padding:8px}.grid2,.grid3{grid-template-columns:1fr}.controls{grid-template-columns:repeat(2,1fr)}.reactionGrid{grid-template-columns:repeat(2,1fr)}.clock{font-size:40px}.cue{font-size:21px}.brand h1{font-size:17px}}body.largeText{font-size:19px}body.largeText .cue{font-size:31px}body.minimal .secondaryPanel{display:none}''')

# Android project files.
(ROOT/'settings.gradle').write_text("pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name='HanmaComboCaller'\ninclude ':app'\n")
(ROOT/'build.gradle').write_text("plugins { id 'com.android.application' version '8.7.3' apply false }\n")
(ROOT/'gradle.properties').write_text("org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.nonTransitiveRClass=true\n")
(ROOT/'app').mkdir(exist_ok=True)
(ROOT/'app/build.gradle').write_text("""plugins { id 'com.android.application' }

android {
 namespace 'com.hanmaclan.combatengine'
 compileSdk 35
 defaultConfig { applicationId 'com.hanmaclan.combatengine'; minSdk 23; targetSdk 35; versionCode 40; versionName '4.0-FullVault' }
}
""")
(ROOT/'app/proguard-rules.pro').write_text('')
(ROOT/'app/src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.VIBRATE"/><application android:theme="@style/AppTheme" android:label="HANMA Combo Caller" android:allowBackup="true"><activity android:name=".MainActivity" android:screenOrientation="portrait" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''')
(RES/'styles.xml').write_text('''<resources><style name="AppTheme" parent="android:style/Theme.Material.NoActionBar"><item name="android:fontFamily">sans</item><item name="android:windowFullscreen">false</item><item name="android:colorAccent">#E41F26</item><item name="android:navigationBarColor">#070707</item><item name="android:statusBarColor">#070707</item><item name="android:windowLightStatusBar">false</item></style></resources>''')

JAVA_DIR.joinpath('MainActivity.java').write_text(r'''package com.hanmaclan.combatengine;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
  private WebView webView;
  private TextToSpeech tts;
  private volatile boolean exchangeActive=false;
  private volatile String finalUtterance="";

  @Override public void onCreate(Bundle b){super.onCreate(b);webView=new WebView(this);setContentView(webView);WebSettings s=webView.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(true);s.setAllowContentAccess(true);s.setMediaPlaybackRequiresUserGesture(false);webView.setWebViewClient(new WebViewClient());webView.setWebChromeClient(new WebChromeClient());webView.addJavascriptInterface(new Bridge(this),"HanmaBridge");tts=new TextToSpeech(this,this);webView.loadUrl("file:///android_asset/index.html");}
  @Override public void onInit(int status){if(status==TextToSpeech.SUCCESS&&tts!=null){tts.setLanguage(Locale.US);tts.setSpeechRate(.95f);tts.setPitch(.90f);tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){} public void onError(String id){doneIfFinal(id);} public void onDone(String id){doneIfFinal(id);}});}}
  private void doneIfFinal(String id){if(id!=null&&id.equals(finalUtterance)){exchangeActive=false;finalUtterance="";runOnUiThread(()->webView.evaluateJavascript("window.onNativeExchangeDone&&window.onNativeExchangeDone()",null));}}
  private String clean(String in){if(in==null)return "";return in.replace("→"," ").replace("->"," ").replaceAll("(?i)\\b(EXACT|STRUCTURE|UNRESOLVED|OPAQUE|filename|source slot)\\b","").replaceAll("(?i)\\.(mp4|mp3|wav)\\b","").replaceAll("\\s+"," ").trim();}

  public final class Bridge {
    private final Context c; Bridge(Context c){this.c=c;}
    @JavascriptInterface public boolean isSpeaking(){return exchangeActive;}
    @JavascriptInterface public void speakExchange(String json,double rate,double pitch,int pauseMs){runOnUiThread(()->{if(tts==null||exchangeActive)return;try{JSONArray a=new JSONArray(json);if(a.length()==0){webView.evaluateJavascript("window.onNativeExchangeDone&&window.onNativeExchangeDone()",null);return;} exchangeActive=true;tts.setSpeechRate((float)Math.max(.45,Math.min(1.8,rate)));tts.setPitch((float)Math.max(.5,Math.min(1.5,pitch)));String prefix="hx-"+UUID.randomUUID();String last="";for(int i=0;i<a.length();i++){String x=clean(a.optString(i));if(x.isEmpty())continue;String id=prefix+"-"+i;last=id;tts.speak(x,TextToSpeech.QUEUE_ADD,null,id);if(pauseMs>0&&i<a.length()-1)tts.playSilentUtterance(Math.min(1200,Math.max(0,pauseMs)),TextToSpeech.QUEUE_ADD,prefix+"-p-"+i);}if(last.isEmpty()){exchangeActive=false;webView.evaluateJavascript("window.onNativeExchangeDone&&window.onNativeExchangeDone()",null);}else finalUtterance=last;}catch(Exception e){exchangeActive=false;webView.evaluateJavascript("window.onNativeExchangeDone&&window.onNativeExchangeDone()",null);}});}
    @JavascriptInterface public void testSpeak(String text,double rate,double pitch){runOnUiThread(()->{if(tts==null||exchangeActive)return;tts.setSpeechRate((float)rate);tts.setPitch((float)pitch);tts.speak(clean(text),TextToSpeech.QUEUE_FLUSH,null,"hanma-test");});}
    @JavascriptInterface public void stopSpeaking(){runOnUiThread(()->{if(tts!=null)tts.stop();exchangeActive=false;finalUtterance="";});}
    @JavascriptInterface public void vibrate(int ms){runOnUiThread(()->{Vibrator v=(Vibrator)c.getSystemService(Context.VIBRATOR_SERVICE);if(v!=null&&v.hasVibrator()){int d=Math.max(20,Math.min(ms,1000));if(android.os.Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createOneShot(d,VibrationEffect.DEFAULT_AMPLITUDE));else v.vibrate(d);}});}
    @JavascriptInterface public void shareText(String text){runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,text==null?"":text);startActivity(Intent.createChooser(i,"Share Hanma session"));});}
  }
  @Override protected void onDestroy(){if(tts!=null){tts.stop();tts.shutdown();}if(webView!=null)webView.destroy();super.onDestroy();}
}''')

(ASSETS/'content_manifest.json').write_text(json.dumps({'version':'4.0-FullVault','declared':full['declared'],'actualCallableBanks':sum(1 for b in full['banks'].values() if b.get('items')),'actualCallableItems':sum(len(b.get('items',[])) for b in full['banks'].values()),'provenance':'EXACT / STRUCTURE / OPAQUE preserved'},indent=2))

print('REBUILD_OK')
print('banks',len(full['banks']),'actual_items',sum(len(b.get('items',[])) for b in full['banks'].values()),'presets',len(full['presets']))
