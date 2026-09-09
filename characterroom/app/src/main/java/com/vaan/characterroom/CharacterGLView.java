package com.vaan.characterroom;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class CharacterGLView extends GLSurfaceView {
    public static final int KEY_OFF = 0;
    public static final int KEY_AUTO = 1;
    public static final int KEY_GREEN = 2;
    public static final int KEY_BLUE = 3;
    public static final int KEY_WHITE = 4;
    public static final int KEY_BLACK = 5;

    private final CharacterRenderer renderer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Uri pendingUri;
    private Surface videoSurface;
    private boolean audioEnabled = false;

    private float lastX, lastY;
    private float lastDistance, lastAngle;
    private boolean twoFinger = false;

    public CharacterGLView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setPreserveEGLContextOnPause(true);
        renderer = new CharacterRenderer();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void loadVideo(Uri uri) {
        pendingUri = uri;
        if (videoSurface != null) preparePlayer(uri);
    }

    private void preparePlayer(Uri uri) {
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(getContext(), uri);
            player.setSurface(videoSurface);
            player.setLooping(true);
            player.setVolume(audioEnabled ? 1f : 0f, audioEnabled ? 1f : 0f);
            player.setOnVideoSizeChangedListener((mp, w, h) -> queueEvent(() -> renderer.setVideoSize(w, h)));
            player.setOnPreparedListener(mp -> {
                mp.start();
                requestRender();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(getContext(), "That video format could not be opened.", Toast.LENGTH_LONG).show();
                return true;
            });
            player.prepareAsync();
        } catch (IOException e) {
            Toast.makeText(getContext(), "Could not open video: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.start();
    }

    public boolean isPlaying() { return player != null && player.isPlaying(); }

    public void setAudioEnabled(boolean enabled) {
        audioEnabled = enabled;
        if (player != null) player.setVolume(enabled ? 1f : 0f, enabled ? 1f : 0f);
    }

    public boolean isAudioEnabled() { return audioEnabled; }

    public void setKeyMode(int mode) { queueEvent(() -> renderer.keyMode = mode); requestRender(); }
    public int getKeyMode() { return renderer.keyMode; }
    public void setTolerance(float value) { queueEvent(() -> renderer.tolerance = value); requestRender(); }
    public void setSoftness(float value) { queueEvent(() -> renderer.softness = value); requestRender(); }
    public void setOpacity(float value) { queueEvent(() -> renderer.opacity = value); requestRender(); }
    public void setShadow(float value) { queueEvent(() -> renderer.shadow = value); requestRender(); }
    public void setMirror(boolean mirror) { queueEvent(() -> renderer.mirror = mirror); requestRender(); }
    public boolean isMirror() { return renderer.mirror; }
    public void resetPlacement() { queueEvent(renderer::resetPlacement); requestRender(); }

    public void onHostResume() {
        onResume();
        if (player != null && !player.isPlaying()) player.start();
    }

    public void onHostPause() {
        if (player != null && player.isPlaying()) player.pause();
        onPause();
    }

    public void release() {
        releasePlayer();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastX = e.getX(); lastY = e.getY(); twoFinger = false;
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() >= 2) {
            twoFinger = true;
            lastDistance = distance(e);
            lastAngle = angle(e);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (e.getPointerCount() >= 2) {
                float d = distance(e);
                float a = angle(e);
                float factor = lastDistance > 1f ? d / lastDistance : 1f;
                float deltaAngle = a - lastAngle;
                queueEvent(() -> {
                    renderer.userScale = clamp(renderer.userScale * factor, 0.08f, 2.5f);
                    renderer.rotation += deltaAngle;
                });
                lastDistance = d; lastAngle = a;
                requestRender();
            } else if (!twoFinger) {
                float dx = (e.getX() - lastX) / Math.max(1f, getWidth()) * 2f;
                float dy = -(e.getY() - lastY) / Math.max(1f, getHeight()) * 2f;
                queueEvent(() -> {
                    renderer.translateX = clamp(renderer.translateX + dx, -1.5f, 1.5f);
                    renderer.translateY = clamp(renderer.translateY + dy, -1.5f, 1.5f);
                });
                lastX = e.getX(); lastY = e.getY();
                requestRender();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            twoFinger = false;
            int remainingIndex = e.getActionIndex() == 0 ? 1 : 0;
            if (remainingIndex < e.getPointerCount()) {
                lastX = e.getX(remainingIndex); lastY = e.getY(remainingIndex);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            twoFinger = false;
            return true;
        }
        return true;
    }

    private static float distance(MotionEvent e) {
        float dx = e.getX(1) - e.getX(0), dy = e.getY(1) - e.getY(0);
        return (float)Math.sqrt(dx*dx + dy*dy);
    }
    private static float angle(MotionEvent e) {
        return (float)Math.toDegrees(Math.atan2(e.getY(1)-e.getY(0), e.getX(1)-e.getX(0)));
    }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    private class CharacterRenderer implements Renderer, SurfaceTexture.OnFrameAvailableListener {
        private final float[] vertices = {
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f
        };
        private FloatBuffer vertexBuffer;
        private int program, oesTexture;
        private SurfaceTexture surfaceTexture;
        private final float[] texMatrix = new float[16];
        private final float[] mvp = new float[16];
        private boolean frameAvailable;
        private int viewW = 1, viewH = 1, videoW = 9, videoH = 16;

        volatile int keyMode = KEY_AUTO;
        volatile float tolerance = 0.23f;
        volatile float softness = 0.10f;
        volatile float opacity = 1f;
        volatile float shadow = 0.25f;
        volatile boolean mirror = false;
        volatile float translateX = 0f, translateY = -0.04f, userScale = 0.63f, rotation = 0f;

        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0f,0f,0f,0f);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(vertices).position(0);
            program = buildProgram(VERTEX, FRAGMENT);
            oesTexture = createExternalTexture();
            surfaceTexture = new SurfaceTexture(oesTexture);
            surfaceTexture.setOnFrameAvailableListener(this);
            Surface newSurface = new Surface(surfaceTexture);
            main.post(() -> {
                if (videoSurface != null) videoSurface.release();
                videoSurface = newSurface;
                if (player != null) player.setSurface(videoSurface);
                else if (pendingUri != null) preparePlayer(pendingUri);
            });
        }

        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
            viewW = Math.max(1,width); viewH = Math.max(1,height);
            GLES20.glViewport(0,0,width,height);
        }

        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (surfaceTexture == null) return;
            synchronized (this) {
                if (frameAvailable) {
                    try { surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(texMatrix); } catch (Exception ignored) {}
                    frameAvailable = false;
                }
            }
            if (program == 0) return;
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"sTexture"),0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uTexMatrix"),1,false,texMatrix,0);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uMode"),keyMode);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uTolerance"),tolerance);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uSoftness"),softness);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uOpacity"),opacity);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uMirror"),mirror ? 1 : 0);

            int aPos = GLES20.glGetAttribLocation(program,"aPosition");
            int aTex = GLES20.glGetAttribLocation(program,"aTexCoord");
            vertexBuffer.position(0);
            GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,16,vertexBuffer);
            GLES20.glEnableVertexAttribArray(aPos);
            vertexBuffer.position(2);
            GLES20.glVertexAttribPointer(aTex,2,GLES20.GL_FLOAT,false,16,vertexBuffer);
            GLES20.glEnableVertexAttribArray(aTex);

            if (shadow > 0.001f) {
                computeMvp(translateX + 0.025f, translateY - 0.045f, userScale * 1.015f, rotation);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMvp"),1,false,mvp,0);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uShadowPass"),1);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uShadowStrength"),shadow);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            }

            computeMvp(translateX, translateY, userScale, rotation);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMvp"),1,false,mvp,0);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uShadowPass"),0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);

            GLES20.glDisableVertexAttribArray(aPos);
            GLES20.glDisableVertexAttribArray(aTex);
        }

        private void computeMvp(float tx, float ty, float scale, float angle) {
            Matrix.setIdentityM(mvp,0);
            Matrix.translateM(mvp,0,tx,ty,0f);
            Matrix.rotateM(mvp,0,angle,0f,0f,1f);
            float videoAspect = videoW / (float)Math.max(1,videoH);
            float screenAspect = viewW / (float)Math.max(1,viewH);
            float sx = scale * videoAspect / screenAspect;
            Matrix.scaleM(mvp,0,sx,scale,1f);
        }

        void resetPlacement() { translateX=0f; translateY=-0.04f; userScale=0.63f; rotation=0f; }
        void setVideoSize(int w, int h) { if (w>0 && h>0) { videoW=w; videoH=h; } }

        @Override public synchronized void onFrameAvailable(SurfaceTexture surfaceTexture) {
            frameAvailable = true;
            requestRender();
        }

        private int createExternalTexture() {
            int[] tex = new int[1];
            GLES20.glGenTextures(1,tex,0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            return tex[0];
        }

        private int buildProgram(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER,vs), f = compile(GLES20.GL_FRAGMENT_SHADER,fs);
            if (v==0 || f==0) return 0;
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p);
            int[] ok = new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
            if (ok[0]==0) { GLES20.glDeleteProgram(p); return 0; }
            return p;
        }
        private int compile(int type, String src) {
            int s = GLES20.glCreateShader(type); GLES20.glShaderSource(s,src); GLES20.glCompileShader(s);
            int[] ok = new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
            if (ok[0]==0) { GLES20.glDeleteShader(s); return 0; }
            return s;
        }
    }

    private static final String VERTEX =
            "attribute vec4 aPosition;\n"+
            "attribute vec2 aTexCoord;\n"+
            "uniform mat4 uMvp;\n"+
            "varying vec2 vTex;\n"+
            "void main(){ gl_Position=uMvp*aPosition; vTex=aTexCoord; }";

    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"+
            "precision mediump float;\n"+
            "uniform samplerExternalOES sTexture;\n"+
            "uniform mat4 uTexMatrix;\n"+
            "uniform int uMode; uniform int uMirror; uniform int uShadowPass;\n"+
            "uniform float uTolerance; uniform float uSoftness; uniform float uOpacity; uniform float uShadowStrength;\n"+
            "varying vec2 vTex;\n"+
            "vec2 uvmap(vec2 q){ if(uMirror==1) q.x=1.0-q.x; return (uTexMatrix*vec4(q,0.0,1.0)).xy; }\n"+
            "vec3 smp(vec2 q){ return texture2D(sTexture,uvmap(clamp(q,0.0,1.0))).rgb; }\n"+
            "float alphaFor(vec2 q, vec3 c){\n"+
            " if(uMode==0) return 1.0;\n"+
            " vec3 k; float d;\n"+
            " if(uMode==1){ vec3 a=smp(vec2(.025,.025)); vec3 b=smp(vec2(.975,.025)); vec3 e=smp(vec2(.025,.975)); vec3 f=smp(vec2(.975,.975)); d=min(min(distance(c,a),distance(c,b)),min(distance(c,e),distance(c,f))); }\n"+
            " else { if(uMode==2) k=vec3(0.05,0.95,0.08); else if(uMode==3) k=vec3(0.05,0.20,0.95); else if(uMode==4) k=vec3(0.95); else k=vec3(0.03); d=distance(c,k); }\n"+
            " return smoothstep(uTolerance, uTolerance+max(.002,uSoftness), d);\n"+
            "}\n"+
            "void main(){ vec3 c=smp(vTex); float a=alphaFor(vTex,c);\n"+
            " if(uShadowPass==1){ gl_FragColor=vec4(0.0,0.0,0.0,a*uShadowStrength*0.65); return; }\n"+
            " if(uMode==2){ float spill=max(0.0,c.g-max(c.r,c.b)); c.g-=spill*(1.0-a)*0.75; }\n"+
            " gl_FragColor=vec4(c,a*uOpacity); }";
}
