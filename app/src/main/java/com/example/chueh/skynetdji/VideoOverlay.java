package com.example.chueh.skynetdji;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import static org.opencv.core.Core.flip;

/**
 * Created by chueh on 5/21/2016.
 */
public class VideoOverlay extends Thread implements TextureView.SurfaceTextureListener {
    private static final String TAG = VideoOverlay.class.getName();
    private static final int TIME_BETWEEN_RUNS = 200;

    private Object mLock = new Object();        // guards mSurfaceTexture, mDone
    private boolean mDone;

    private int mWidth;     // from SurfaceTexture
    private int mHeight;
    //haar

    private boolean preparedClassifier = false;
    private CascadeClassifier haar_faceClassifier = null;
    private CascadeClassifier haar_sideClassifier = null;

    private CascadeClassifier lbp_faceClassifier = null;
    private CascadeClassifier lbp_sideClassifier = null;
    private boolean isProductReady = false;

    private Mat mat = null;
    private Mat mat1 = null;
    private int mAbsoluteFaceSize   = 200;
    private float mRelativeFaceSize   = 0.02f;
    SurfaceTexture mSurfaceTexture = null;
    TextureView myTextureview = null;
    Context context = null;

    public VideoOverlay(TextureView tv, Context context) {
        super("TextureViewCanvas Renderer");
        isProductReady = false;
        this.myTextureview = tv;
        this.context = context;
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG,"opencv init error!");// Handle initialization error
        }
    }

    @Override
    public void run() {
        Log.d(TAG,"thread started");
        while (true) {
            Log.d(TAG,"thread first loop");
            SurfaceTexture surfaceTexture = null;

            // Latch the SurfaceTexture when it becomes available.  We have to wait for
            // the TextureView to create it.
            synchronized (mLock) {
                while (!mDone && (surfaceTexture = mSurfaceTexture) == null) {
                    try {
                        Log.d(TAG,"waiting for lock");
                        mLock.wait();
                        Log.d(TAG, "notified");
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);     // not expected
                    }
                }
                if (mDone) {
                    Log.d(TAG,"mDone");
                    break;
                }
            }
            //Log.d("bitmap", "Got surfaceTexture=" + surfaceTexture);
            // Render frames until we're told to stop or the SurfaceTexture is destroyed.
            Log.d(TAG,"Acquired lock, do anim");
            doAnimation();
        }

        Log.d(TAG, "Renderer thread exiting");
    }

    /**
     * Draws updates as fast as the system will allow.
     * <p>
     * In 4.4, with the synchronous buffer queue queue, the frame rate will be limited.
     * In previous (and future) releases, with the async queue, many of the frames we
     * render may be dropped.
     * <p>
     * The correct thing to do here is use Choreographer to schedule frame updates off
     * of vsync, but that's not nearly as much fun.
     */


    private void doAnimation() {
        Log.d(TAG,"doAnimation");
        // Create a Surface for the SurfaceTexture.
        Surface surface = null;
        synchronized (mLock) {
            SurfaceTexture surfaceTexture = mSurfaceTexture;
            if (surfaceTexture == null) {
                Log.d("bitmap", "ST null on entry");
                return;
            }
            surface = new Surface(surfaceTexture);
        }


        Paint myPaint = new Paint();
        myPaint.setColor(Color.rgb(255, 255, 255));
        myPaint.setStrokeWidth(15);
        myPaint.setStyle(Paint.Style.STROKE);


        Paint borderPaint = new Paint();
        borderPaint.setColor(Color.rgb(0x29, 0x80, 0xb9));
        borderPaint.setStrokeWidth(10);
        borderPaint.setStyle(Paint.Style.STROKE);

        if (!preparedClassifier) {
            prepareClassifiers();
            preparedClassifier = true;
        }

        CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
        int bitmapH = 0;
        int bitmapW = 0;
        Canvas canvas = null;

        while (true) {

            try {

                if (mSurfaceTexture == null) {
                    Log.d(TAG,"mSurfaceTexture is null!");
                    break;
                }
                //Log.d(TAG,"trying to lock canvas");
                //Rect fullSurface = new Rect(0,0,myTextureview.getWidth(),myTextureview.getWidth());
                canvas = surface.lockCanvas(null);
                //Log.d(TAG,"managed to lock canvas");
                if (canvas == null) {
                    Log.e(TAG, "lockCanvas() failed");
                    break;
                }
                canvas.drawColor(0, PorterDuff.Mode.CLEAR); //clear canvas of previosu rectangle

                if (bitmapW == 0 || bitmapH == 0) {
                    Bitmap b0 = myTextureview.getBitmap();
                    bitmapW = b0.getWidth();
                    bitmapH = b0.getHeight();
                }
                Log.d(TAG,"bitmap w: " + bitmapW + ", h: " + bitmapH);


                Bitmap b = Bitmap.createScaledBitmap(myTextureview.getBitmap(), bitmapW / 2, bitmapH / 2, false);
//                Bitmap b = myTextureview.getBitmap();
//


                if (mat == null) mat = new Mat(b.getWidth(), b.getHeight(), CvType.CV_8UC1);
                if (mat1 == null) mat1 = new Mat(b.getWidth(), b.getHeight(), CvType.CV_8UC1);

                Mat grayMat = new Mat();
                Utils.bitmapToMat(b, mat);
                Imgproc.cvtColor(mat, mat1, Imgproc.COLOR_BGR2GRAY);
                clahe.apply(mat1, grayMat);
//
//                Mat grayMat_flipped = new Mat();
//                flip(grayMat, grayMat_flipped, 1);

                MatOfRect faces0 = new MatOfRect();
                Log.d(TAG,"detecting...");
                haar_faceClassifier.detectMultiScale(grayMat, faces0, 1.1, 4, 2, new Size(50, 50), new Size());
//                MatOfRect faces1 = new MatOfRect();
//                haar_sideClassifier.detectMultiScale(grayMat, faces1, 1.1, 4, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());
//                MatOfRect faces2 = new MatOfRect();
//                haar_sideClassifier.detectMultiScale(grayMat_flipped, faces2, 1.1, 4, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());
                Log.d(TAG,"detected: " + faces0.toArray().length);
                for (org.opencv.core.Rect face : faces0.toArray()) {
                    Log.d(TAG,"Drawing rect");
                    int top = (int) (face.tl().y)*2 ;
                    int left = (int) (face.tl().x)*2 ;
                    int bottom = (int) (face.br().y)*2 ;
                    int right = (int) (face.br().x)*2 ;
                    canvas.drawRect(left, top, right, bottom, myPaint);
                }
//                for (org.opencv.core.Rect face : faces1.toArray()) {
//                    int top = (int) (face.tl().y) * 2;
//                    int left = (int) (face.tl().x) * 2;
//                    int bottom = (int) (face.br().y) * 2;
//                    int right = (int) (face.br().x) * 2;
//                    canvas.drawRect(left, top, right, bottom, myPaint);
//                }
//                for (org.opencv.core.Rect face : faces2.toArray()) {
//                    int top = (int) (face.tl().y) * 2;
//                    int left = (int) (face.tl().x) * 2;
//                    int bottom = (int) (face.br().y) * 2;
//                    int right = (int) (face.br().x) * 2;
//                    canvas.drawRect(canvas.getWidth() - left, top, canvas.getWidth() - right, bottom, myPaint);
//                }

                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), borderPaint);


                if (canvas != null) {
                    if (surface == null) {
                        Log.d(TAG,"Surface is null");
                        break;
                    }
                    // if (surface)

                    surface.unlockCanvasAndPost(canvas);
                }


            } catch (IllegalArgumentException iae) {
                Log.d(TAG, "unlockCanvasAndPost failed: " + iae.getMessage());
                break;
            } catch (Exception e1) {
                e1.printStackTrace();
                break;
            }
            try {
                sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
//        Log.d(TAG,"doAnimation, out of true loop");


    }

    private void prepareClassifiers() {
        try {
            InputStream is = context.getResources().openRawResource(R.raw.haarcascade_frontalface_alt);
            File cascadeDir = context.getDir("cascade", Context.MODE_PRIVATE);
            File mCascadeFile = new File(cascadeDir, "haarcascade_frontalface_default.xml");
            FileOutputStream os = new FileOutputStream(mCascadeFile);

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            is.close();
            os.close();
            haar_faceClassifier = new CascadeClassifier(mCascadeFile.getAbsolutePath());
            if (haar_faceClassifier.empty()) {
                Log.e("classify", "Failed to load face cascade classifier");
                haar_faceClassifier = null;
            } else
                Log.i(TAG, "Loaded cascade classifier from " + mCascadeFile.getAbsolutePath());

        }
        catch(Exception e){
            e.printStackTrace();
        }

//        try {
//            InputStream is = context.getResources().openRawResource(R.raw.haarcascade_profileface);
//            File cascadeDir = context.getDir("cascade", Context.MODE_PRIVATE);
//            File mCascadeFile = new File(cascadeDir, "haarcascade_profileface.xml");
//            FileOutputStream os = new FileOutputStream(mCascadeFile);
//
//            byte[] buffer = new byte[4096];
//            int bytesRead;
//            while ((bytesRead = is.read(buffer)) != -1) {
//                os.write(buffer, 0, bytesRead);
//            }
//            is.close();
//            os.close();
//            haar_sideClassifier = new CascadeClassifier(mCascadeFile.getAbsolutePath());
//            if (haar_sideClassifier.empty()) {
//                Log.e("classify", "Failed to load sideprof cascade classifier");
//                haar_sideClassifier = null;
//            } else
//                Log.i(TAG, "Loaded cascade classifier from " + mCascadeFile.getAbsolutePath());
//        }
//        catch(Exception e){
//            e.printStackTrace();
//        }
    }



    /**
     * Tells the thread to stop running.
     */
    public void halt() {
        synchronized (mLock) {
            mDone = true;
            mLock.notify();
        }
    }

    @Override   // will be called on UI thread
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        //Log.d("bitmap", "onSurfaceTextureAvailable(" + width + "x" + height + ")");
        Log.i(TAG, "onSurfaceTextureAvailable: " + st.toString());
        mWidth = width;
        mHeight = height;
        synchronized (mLock) {
            mSurfaceTexture = st;
            mLock.notify();
        }
    }

    @Override   // will be called on UI thread
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        //Log.d("bitmap", "onSurfaceTextureSizeChanged(" + width + "x" + height + ")");
        mWidth = width;
        mHeight = height;
    }

    @Override   // will be called on UI thread
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        //Log.d("bitmap", "onSurfaceTextureDestroyed");

        synchronized (mLock) {
            mSurfaceTexture = null;
        }
        return true;
    }

    @Override   // will be called on UI thread
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
        //Log.d(TAG, "onSurfaceTextureUpdated");
    }

    public void productReady() {
        isProductReady = true;
    }
}