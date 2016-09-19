/*****************************************************************************
 * NativeActivity.java
 *****************************************************************************
 * Copyright (C) 2016 VideoLAN
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the BSD license. See the LICENSE file for details.
 *****************************************************************************/

package org.videolan.nativesample;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.videolan.libvlc.AWindow;
import org.videolan.libvlc.AWindowNativeHandler;
import org.videolan.libvlc.IVLCVout;

@SuppressWarnings("JniMissingFunction")
public class NativeActivity extends AppCompatActivity implements IVLCVout.Callback {
    private static final boolean ENABLE_SUBTITLES = true;
    private static final String TAG = "NativeActivity";
    private static final int SURFACE_BEST_FIT = 0;
    private static final int SURFACE_FIT_HORIZONTAL = 1;
    private static final int SURFACE_FIT_VERTICAL = 2;
    private static final int SURFACE_FILL = 3;
    private static final int SURFACE_16_9 = 4;
    private static final int SURFACE_4_3 = 5;
    private static final int SURFACE_ORIGINAL = 6;
    private static int CURRENT_SIZE = SURFACE_BEST_FIT;

    private boolean mNativeStarted = false;
    private AWindow mAWindow = null;
    private SurfaceView mUiSurface = null;
    private FrameLayout mVideoSurfaceFrame = null;
    private SurfaceView mVideoSurface = null;
    private SurfaceView mSubtitlesSurface = null;

    private final Handler mHandler = new Handler();
    private View.OnLayoutChangeListener mOnLayoutChangeListener = null;

    private int mVideoHeight = 0;
    private int mVideoWidth = 0;
    private int mVideoVisibleHeight = 0;
    private int mVideoVisibleWidth = 0;
    private int mVideoSarNum = 0;
    private int mVideoSarDen = 0;

    private static int sInit = -1;
    static synchronized boolean loadLibraries(Context context) {
        if (sInit != -1)
            return sInit == 1;

        System.loadLibrary("c++_shared");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1) {
            try {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.HONEYCOMB_MR1)
                    System.loadLibrary("anw.10");
                else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.HONEYCOMB_MR2)
                    System.loadLibrary("anw.13");
                else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1)
                    System.loadLibrary("anw.14");
                else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT_WATCH)
                    System.loadLibrary("anw.18");
                else
                    System.loadLibrary("anw.21");
            } catch (Throwable t) {
                Log.w(TAG, "Unable to load the anw library: " + t);
            }
        }

        try {
            System.loadLibrary("vlc");
            System.loadLibrary("native");
        } catch (UnsatisfiedLinkError ule) {
            Toast.makeText(context, "Can't load vlcjni library: " + ule, Toast.LENGTH_LONG).show();
            sInit = 0;
            return false;
        } catch (SecurityException se) {
            Toast.makeText(context, "Encountered a security issue when loading vlcjni library: " + se, Toast.LENGTH_LONG).show();
            sInit = 0;
            return false;
        }
        sInit = 1;
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!loadLibraries(this)) {
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        mAWindow = new AWindow(null);
        mAWindow.addCallback(this);

        mUiSurface = (SurfaceView) findViewById(R.id.ui_surface);
        mVideoSurfaceFrame = (FrameLayout) findViewById(R.id.video_surface_frame);
        mVideoSurface = (SurfaceView) findViewById(R.id.video_surface);
        if (ENABLE_SUBTITLES) {
            final ViewStub stub = (ViewStub) findViewById(R.id.subtitles_stub);
            mSubtitlesSurface = (SurfaceView) stub.inflate();
            mSubtitlesSurface.setZOrderMediaOverlay(true);
            mSubtitlesSurface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        }
        if (!nativeCreate()) {
            Toast.makeText(this, "Couldn't create LibVLC", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nativeDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();

        mAWindow.setVideoView(mVideoSurface);
        if (mSubtitlesSurface != null)
            mAWindow.setSubtitlesView(mSubtitlesSurface);
        mAWindow.attachViews();

        if (mOnLayoutChangeListener == null) {
            mOnLayoutChangeListener = new View.OnLayoutChangeListener() {
                private final Runnable mRunnable = new Runnable() {
                    @Override
                    public void run() {
                        updateVideoSurfaces();
                    }
                };
                @Override
                public void onLayoutChange(View v, int left, int top, int right,
                                           int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                        mHandler.removeCallbacks(mRunnable);
                        mHandler.post(mRunnable);
                    }
                }
            };
        }
        mVideoSurfaceFrame.addOnLayoutChangeListener(mOnLayoutChangeListener);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mOnLayoutChangeListener != null) {
            mVideoSurfaceFrame.removeOnLayoutChangeListener(mOnLayoutChangeListener);
            mOnLayoutChangeListener = null;
        }

        if (mNativeStarted)
            nativeStop();

        mAWindow.detachViews();
    }
    private void updateVideoSurfaces() {
        if (mVideoWidth * mVideoHeight == 0)
            return;
        int sw = getWindow().getDecorView().getWidth();
        int sh = getWindow().getDecorView().getHeight();

        mAWindow.setWindowSize(sw, sh);
        double dw = sw, dh = sh;
        final boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;

        if (sw > sh && isPortrait || sw < sh && !isPortrait) {
            dw = sh;
            dh = sw;
        }

        // sanity check
        if (dw * dh == 0) {
            Log.e(TAG, "Invalid surface size");
            return;
        }

        // compute the aspect ratio
        double ar, vw;
        if (mVideoSarDen == mVideoSarNum) {
            /* No indication about the density, assuming 1:1 */
            vw = mVideoVisibleWidth;
            ar = (double)mVideoVisibleWidth / (double)mVideoVisibleHeight;
        } else {
            /* Use the specified aspect ratio */
            vw = mVideoVisibleWidth * (double)mVideoSarNum / mVideoSarDen;
            ar = vw / mVideoVisibleHeight;
        }

        // compute the display aspect ratio
        double dar = dw / dh;

        switch (CURRENT_SIZE) {
            case SURFACE_BEST_FIT:
                if (dar < ar)
                    dh = dw / ar;
                else
                    dw = dh * ar;
                break;
            case SURFACE_FIT_HORIZONTAL:
                dh = dw / ar;
                break;
            case SURFACE_FIT_VERTICAL:
                dw = dh * ar;
                break;
            case SURFACE_FILL:
                break;
            case SURFACE_16_9:
                ar = 16.0 / 9.0;
                if (dar < ar)
                    dh = dw / ar;
                else
                    dw = dh * ar;
                break;
            case SURFACE_4_3:
                ar = 4.0 / 3.0;
                if (dar < ar)
                    dh = dw / ar;
                else
                    dw = dh * ar;
                break;
            case SURFACE_ORIGINAL:
                dh = mVideoVisibleHeight;
                dw = vw;
                break;
        }

        // set display size
        ViewGroup.LayoutParams lp = mVideoSurface.getLayoutParams();
        lp.width  = (int) Math.ceil(dw * mVideoWidth / mVideoVisibleWidth);
        lp.height = (int) Math.ceil(dh * mVideoHeight / mVideoVisibleHeight);
        mVideoSurface.setLayoutParams(lp);
        if (mSubtitlesSurface != null)
            mSubtitlesSurface.setLayoutParams(lp);

        // set frame size (crop if necessary)
        lp = mVideoSurfaceFrame.getLayoutParams();
        lp.width = (int) Math.floor(dw);
        lp.height = (int) Math.floor(dh);
        mVideoSurfaceFrame.setLayoutParams(lp);

        mVideoSurface.invalidate();
        if (mSubtitlesSurface != null)
            mSubtitlesSurface.invalidate();
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onNewLayout(IVLCVout vlcVout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        mVideoWidth = width;
        mVideoHeight = height;
        mVideoVisibleWidth = visibleWidth;
        mVideoVisibleHeight = visibleHeight;
        mVideoSarNum = sarNum;
        mVideoSarDen = sarDen;
        updateVideoSurfaces();
    }

    @Override
    public void onSurfacesCreated(IVLCVout vlcVout) {
        if (!mNativeStarted) {
            if (!nativeStart(mAWindow.getNativeHandler())) {
                Toast.makeText(this, "Couldn't start LibVLC", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        mNativeStarted = true;
    }

    @Override
    public void onSurfacesDestroyed(IVLCVout vlcVout) {
        if (mNativeStarted)
            nativeStop();
    }

    @SuppressWarnings("unused") // used by JNI
    private long mInstance;
    private native boolean nativeCreate();
    private native void nativeDestroy();
    private native boolean nativeStart(AWindowNativeHandler aWindowNative);
    private native void nativeStop();
}
