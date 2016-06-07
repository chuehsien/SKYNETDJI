package com.example.chueh.skynetdji;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.Surface;
import android.widget.TextView;

import dji.sdk.Camera.DJICamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Toast;
import android.widget.ToggleButton;

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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

import static org.opencv.core.Core.flip;

import dji.sdk.Camera.DJICamera.CameraReceivedVideoDataCallback;
import dji.sdk.Codec.DJICodecManager;
import dji.sdk.Products.DJIAircraft;
import dji.sdk.base.DJIBaseComponent.DJICompletionCallback;
import dji.sdk.base.DJIBaseProduct;
import dji.sdk.base.DJIBaseProduct.Model;
import dji.sdk.base.DJIError;
import dji.sdk.Camera.DJICameraSettingsDef.CameraMode;
import dji.sdk.Camera.DJICameraSettingsDef.CameraShootPhotoMode;

public class MainActivity extends Activity implements SurfaceTextureListener,OnClickListener{

    private static final String TAG = MainActivity.class.getName();
    private static final int VIDEO_HEIGHT = 720;
    private static final int VIDEO_WIDTH = 960;
    private static final long RENDER_INTERVAL_MS = 1000;


    // DJI specific stuff
    protected DJICamera.CameraReceivedVideoDataCallback mReceivedVideoDataCallBack = null;

    // Codec for video live view - used when DJISKYNET is run as a standalone app (without atak)
    protected DJICodecManager mCodecManager = null;

    // UI stuff
    protected TextView mConnectStatusTextView;
    protected TextureView mVideoSurface = null;
    protected TextureView mOverlapSurface = null;
    private Button mCaptureBtn, mShootPhotoModeBtn, mRecordVideoModeBtn;
    private ToggleButton mRecordBtn;
    private TextView recordingTime;

    // service connection stuff
    private ServiceConnection serviceConnection;
    boolean mBound = false;
    Messenger mService = null;
    private boolean atakAppConnected = false;
    private Handler mHandler = null;
    private Object atakBooleanLock = new Object();
    private final Messenger mActivityMessenger = new Messenger(new ActivityHandler(this));
    private CountDownTimer helloTimer;
    private final static byte[] delimiter = {0,0,1,0,0,0,0}; //last 2 bytes are for size.

    // handler for all messages received from service
    private class ActivityHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        public ActivityHandler(MainActivity activity) {
            mActivity = new WeakReference<MainActivity>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            //Log.d(TAG,"msg from service");
            switch (msg.what) {
                case ATAKServiceHandler.MSG_STREAM: {
                    Log.d(TAG,"Received pfd from service");
                    ParcelFileDescriptor pfd = msg.getData().getParcelable("pfd");
                    setWritePfd(pfd);
                }
                break;

                case ATAKServiceHandler.MSG_IS_ATAKAPP_CONNECTED: {
                    Log.d(TAG,"From service: Update ATAKAPP_CONNECTED: " + ((msg.arg1==ATAKServiceHandler.FALSE)?"false":"true"));

                    if (msg.arg1 == ATAKServiceHandler.FALSE) {
                        synchronized (atakBooleanLock) {
                            atakAppConnected = false;
                        }
                    }
                    else if (msg.arg1 == ATAKServiceHandler.TRUE) {
                        synchronized (atakBooleanLock) {
                            atakAppConnected = true;
                        }
                    }
                }
                break;
                default:
                    Log.d(TAG,"Unknown msg from service");
            }
        }

    }
    private Renderer mRenderer;
    private OutputStream outStream;
    public static boolean bridgeOn = false; //represents whether the pipe connection to the atak app is on. (both sides need to receive a parcelfieldescriptor created by the service

    public void setWritePfd(ParcelFileDescriptor writePfd){
        Log.d(TAG,"pipe created, registering outputstream");
        outStream = new ParcelFileDescriptor.AutoCloseOutputStream(writePfd);
    }

    public void writeToStream(byte[] buf, int size){

        if (outStream == null) Log.d(TAG,"os = null");
        if (!atakAppConnected) Log.d(TAG,"atakApp is not connected yet");
        else {

            try {
                // writes a delimiter of 0 0 1 0 0 sizeHiByte sizeLoByte
                delimiter[6] = (byte)(0xFF & size);
                delimiter[5] = (byte)((size>>8)&0xFF);
                outStream.write(delimiter,0,7);

                // writes the rest of the data
                outStream.write(buf,0,size);
            } catch (IOException e) {
                e.printStackTrace();
            }


        }

    }

   private void sayHelloToService(){
       // for sending 'alive' signals to the service
        Message msg = Message.obtain(null, ATAKServiceHandler.MSG_HELLO_FROM_DJI, 0, 0);
        try {
            mService.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void initConnectionWithService() {

        if (!mBound) return;
        // Create and send a message of type "MSG_MESSENGER_DJIAPP"
        Message msg = Message.obtain(null, ATAKServiceHandler.MSG_MESSENGER_DJIAPP, 0, 0);

        //the service uses the 'replyTo' field to reply to this activity.
        msg.replyTo = mActivityMessenger;
        try {
            mService.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void updateAtakAppStatus(){
        Message msg = Message.obtain(null, ATAKServiceHandler.MSG_IS_ATAKAPP_CONNECTED, 0, 0);
        try {
            mService.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
    private Runnable updateRunnable = new Runnable() {

        @Override
        public void run() {
            updateAtakAppStatus();
            mHandler.postDelayed(updateRunnable,5000);

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        Log.d(TAG,"onCreate");
        setContentView(R.layout.activity_main);

        //checks and updates if the atak app is connected.
        mHandler = new Handler();
        mHandler.postDelayed(updateRunnable,5000);

        serviceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder service) {
                mService = new Messenger(service);
                mBound = true;
                Log.d(TAG,"Connected to service!");

                //send first message, requesting for parcelfiledescriptor from the service.
                initConnectionWithService();

                // timer to tell service that this activity is alive. happens every 5seconds
                helloTimer = new CountDownTimer(5000,4000) {
                    @Override
                    public void onTick(long l) {
                        //
                    }

                    @Override
                    public void onFinish() {
                        sayHelloToService();
                        helloTimer.cancel();
                        helloTimer.start();
                    }
                }.start();
            }

            public void onServiceDisconnected(ComponentName className) {
                // This is called when the connection with the service has been
                // unexpectedly disconnected -- that is, its process crashed.
                Log.d(TAG,"Disconnected from service!");
                mService = null;
                mBound = false;
            }
        };

        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG,"opencv init error!");// Handle initialization error
        }

        initUI();

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataCallBack = new CameraReceivedVideoDataCallback() {

            @Override
            public void onResult(byte[] videoBuffer, int size) {
//                Log.d(TAG,"onResult of callback");

                if (!atakAppConnected) {
                    if (mCodecManager != null) {
                        //Log.d(TAG,"Bytes: " + bytesToHexString(videoBuffer,size));
                        mCodecManager.sendDataToDecoder(videoBuffer, size);
                    } else {

                    }
                }else{
                    if (bridgeOn)writeToStream(videoBuffer,size);
                }
            }
        };

        DJICamera camera = MainApplication.getCameraInstance();

        /* NOT USED - remnants from DJI FPV sample code */
        if (camera != null) {
            camera.setDJICameraUpdatedSystemStateCallback(new DJICamera.CameraUpdatedSystemStateCallback() {
                @Override
                public void onResult(DJICamera.CameraSystemState cameraSystemState) {
                    if (null != cameraSystemState) {

                        int recordTime = cameraSystemState.getCurrentVideoRecordingTimeInSeconds();
                        int minutes = (recordTime % 3600) / 60;
                        int seconds = recordTime % 60;

                        final String timeString = String.format("%02d:%02d", minutes, seconds);
                        final boolean isVideoRecording = cameraSystemState.isRecording();

                        MainActivity.this.runOnUiThread(new Runnable() {

                            @Override
                            public void run() {

                                recordingTime.setText(timeString);

                                /*
                                 * Update recordingTime TextView visibility and mRecordBtn's check state
                                 */
                                if (isVideoRecording){
                                    recordingTime.setVisibility(View.VISIBLE);
                                }else
                                {
                                    recordingTime.setVisibility(View.INVISIBLE);
                                }
                            }
                        });
                    }
                }
            });
        }

        // Register the broadcast receiver for receiving the device connection's changes from MainApplication.
        IntentFilter filter = new IntentFilter();
        filter.addAction(MainApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);

    }
    public static String bytesToHexString(byte[] bytes, int size){
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for(byte b : bytes){
            sb.append(String.format("%02x ", b&0xff));
            i ++;
            if (i == size) break;
        }
        return i +": " + sb.toString();
    }
    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG,"Received filter: " + intent.getAction());

            if (intent.getAction() == MainApplication.FLAG_CONNECTION_CHANGE) {
                updateTitleBar();
                onProductChange();
                initPreviewer();
            }

        }

    };

    private void updateTitleBar() {
        if(mConnectStatusTextView == null) return;
        boolean ret = false;
        DJIBaseProduct product = MainApplication.getProductInstance();
        if (product != null) {
            if(product.isConnected()) {
                //The product is connected
                mConnectStatusTextView.setText(MainApplication.getProductInstance().getModel() + " Connected");
                ret = true;
            } else {
                if(product instanceof DJIAircraft) {
                    DJIAircraft aircraft = (DJIAircraft)product;
                    if(aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                        // The product is not connected, but the remote controller is connected
                        mConnectStatusTextView.setText("only RC Connected");
                        ret = true;
                    }
                }
            }
        }

        if(!ret) {
            // The product or the remote controller are not connected.
            mConnectStatusTextView.setText("Disconnected");
        }
    }

    protected void onProductChange() {
        initPreviewer();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG,"onStart()");
        // Bind to the service
        if (!mBound) {
            Log.d(TAG,"binding to service");
            bindService(new Intent(this, ATAKServiceHandler.class), serviceConnection,
                    Context.BIND_AUTO_CREATE);
        }
    }


    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view){
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        if (mBound) {
            unbindService(serviceConnection);
            mBound = false;
        }

        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    private void initUI() {

        mOverlapSurface = (TextureView) findViewById(R.id.overlap_surface);


        mRenderer = new Renderer();
        mRenderer.start();

        mOverlapSurface.setSurfaceTextureListener(mRenderer);
        mOverlapSurface.setAlpha(0.99f);



        mVideoSurface = (TextureView)findViewById(R.id.video_previewer_surface);
        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }



        mConnectStatusTextView = (TextView) findViewById(R.id.ConnectStatusTextView);
        recordingTime = (TextView) findViewById(R.id.timer);
        mCaptureBtn = (Button) findViewById(R.id.btn_capture);
        mRecordBtn = (ToggleButton) findViewById(R.id.btn_record);
        mShootPhotoModeBtn = (Button) findViewById(R.id.btn_shoot_photo_mode);
        mRecordVideoModeBtn = (Button) findViewById(R.id.btn_record_video_mode);

        mCaptureBtn.setOnClickListener(this);
        mRecordBtn.setOnClickListener(this);
        mShootPhotoModeBtn.setOnClickListener(this);
        mRecordVideoModeBtn.setOnClickListener(this);

        recordingTime.setVisibility(View.INVISIBLE);

        mRecordBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startRecord();
                } else {
                    stopRecord();
                }
            }
        });
    }

    private void initPreviewer() {

        DJIBaseProduct product = MainApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            showToast(getString(R.string.disconnected));
        } else {


            if (null != mVideoSurface) {
                mVideoSurface.setSurfaceTextureListener(this);
            }
            if (!product.getModel().equals(Model.UnknownAircraft)) {
                DJICamera camera = product.getCamera();
                if (camera != null){
                    camera.setDJICameraReceivedVideoDataCallback(mReceivedVideoDataCallBack);
                    showToast("Connected to " + camera.getDisplayName());

                }
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            mCodecManager = new DJICodecManager(this, surface, width, height);
            switchCameraMode(CameraMode.ShootPhoto);
            adjustAspectRatio();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureSizeChanged");
        adjustAspectRatio();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG,"onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }

        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {
            case R.id.btn_capture:{
                captureAction();
                break;
            }
            case R.id.btn_shoot_photo_mode:{
                switchCameraMode(CameraMode.ShootPhoto);
                break;
            }
            case R.id.btn_record_video_mode:{
                switchCameraMode(CameraMode.RecordVideo);
                break;
            }
            default:
                break;
        }
    }

    private void switchCameraMode(CameraMode cameraMode){

        DJICamera camera = MainApplication.getCameraInstance();
        if (camera != null) {
            camera.setCameraMode(cameraMode, new DJICompletionCallback() {
                @Override
                public void onResult(DJIError error) {

                    if (error == null) {
                        showToast("Switch Camera Mode Succeeded");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            });
        }

    }

    // NOT USED - remnants from DJI FPV sample code - Method for taking photo
    private void captureAction(){

        CameraMode cameraMode = CameraMode.ShootPhoto;

        final DJICamera camera = MainApplication.getCameraInstance();
        if (camera != null) {

            CameraShootPhotoMode photoMode = CameraShootPhotoMode.Single; // Set the camera capture mode as Single mode
            camera.startShootPhoto(photoMode, new DJICompletionCallback() {

                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("take photo: success");
                    } else {
                        showToast(error.getDescription());
                    }
                }

            }); // Execute the startShootPhoto API
        }
    }

    // NOT USED - remnants from DJI FPV sample code - Method for starting recording
    private void startRecord(){

        CameraMode cameraMode = CameraMode.RecordVideo;
        final DJICamera camera = MainApplication.getCameraInstance();
        if (camera != null) {
            camera.startRecordVideo(new DJICompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("Record video: success");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            }); // Execute the startRecordVideo API
        }
    }

    // NOT USED - remnants from DJI FPV sample code - Method for stopping recording
    private void stopRecord(){

        DJICamera camera = MainApplication.getCameraInstance();
        if (camera != null) {
            camera.stopRecordVideo(new DJICompletionCallback() {

                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("Stop recording: success");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            }); // Execute the stopRecordVideo API
        }
    }




    private void adjustAspectRatio() {
        Log.i(TAG,"changing aspect ratio");
        int viewWidth = mVideoSurface.getWidth();
        int viewHeight = mVideoSurface.getHeight();
        double aspectRatio = (double) VIDEO_HEIGHT / VIDEO_WIDTH;

        int newWidth, newHeight;
        if (viewHeight > (int) (viewWidth * aspectRatio)) {
            // limited by narrow width; restrict height
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        } else {
            // limited by short height; restrict width
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        }
        int xoff = (viewWidth - newWidth) / 2;
        int yoff = (viewHeight - newHeight) / 2;
        Log.v(TAG, "video=" + VIDEO_WIDTH + "x" + VIDEO_HEIGHT +
                " view=" + viewWidth + "x" + viewHeight +
                " newView=" + newWidth + "x" + newHeight +
                " off=" + xoff + "," + yoff);

        Matrix txform = new Matrix();
        mVideoSurface.getTransform(txform);
        txform.setScale((float) newWidth / viewWidth, (float) newHeight / viewHeight);
        //txform.postRotate(10);          // just for fun
        txform.postTranslate(xoff, yoff);
        mVideoSurface.setTransform(txform);
    }








    /*------------------------renderer private class-----------------------*/

    public class Renderer extends Thread implements TextureView.SurfaceTextureListener {
        private Object mLock = new Object();        // guards mSurfaceTexture, mDone
        private boolean mDone;

        private int mWidth;     // from SurfaceTexture
        private int mHeight;
        //haar
        private CascadeClassifier haar_faceClassifier = null;
        private CascadeClassifier haar_sideClassifier = null;

        private CascadeClassifier lbp_faceClassifier = null;
        private CascadeClassifier lbp_sideClassifier = null;

        private Mat mat = null;
        private Mat mat1 = null;
        private int mAbsoluteFaceSize   = 0;
        private float mRelativeFaceSize   = 0.02f;
        SurfaceTexture mSurfaceTexture = null;
        public Renderer() {
            super("TextureViewCanvas Renderer");
        }

        @Override
        public void run() {
            while (true) {
                SurfaceTexture surfaceTexture = null;

                // Latch the SurfaceTexture when it becomes available.  We have to wait for
                // the TextureView to create it.
                synchronized (mLock) {
                    while (!mDone && (surfaceTexture = mSurfaceTexture) == null) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException ie) {
                            throw new RuntimeException(ie);     // not expected
                        }
                    }
                    if (mDone) {
                        break;
                    }
                }
                // Render frames until we're told to stop or the SurfaceTexture is destroyed.
                doAnimation();
            }

        Log.d(TAG, "Renderer thread exiting");
        }

        /**
         * Draws updates every RENDER_INTERVAL_MS milliseconds. fast as the system will allow.
         * TODO: use Choreographer to trigger the drawing of the frame, rather than just drawing
         * every RENDER_INTERVAL_MS milliseconds without knowing if a frame has been fully rendered.
         */

        private void doAnimation() {

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

            prepareClassifiers();

            CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
            int bitmapH = 0;
            int bitmapW = 0;
            Canvas canvas = null;
            while (true) {
                try {

                    if (mSurfaceTexture == null) break;
                    canvas = surface.lockCanvas(null);

                    if (canvas == null) {
                        Log.e("classify", "lockCanvas() failed");
                        break;
                    }
                    canvas.drawColor(0, PorterDuff.Mode.CLEAR); //clear canvas of previous rectangle

                    if (mAbsoluteFaceSize == 0) {
                        int height = 0;
                        if (bitmapH != 0) {
                            height = bitmapH / 2;
                        } else {
                            height = canvas.getHeight() / 2;
                        }
                        if (Math.round(height * mRelativeFaceSize) > 0) {
                            mAbsoluteFaceSize = Math.round(height * mRelativeFaceSize);
                        }
                    }

                    if (bitmapW == 0 || bitmapH == 0) {
                        Bitmap b0 = mVideoSurface.getBitmap();
                        bitmapW = b0.getWidth();
                        bitmapH = b0.getHeight();
                    }

                    // downscale for faster processing
                    Bitmap b = Bitmap.createScaledBitmap(mVideoSurface.getBitmap(), bitmapW / 2, bitmapH / 2, false);

                    if (mat == null) mat = new Mat(b.getWidth(), b.getHeight(), CvType.CV_8UC1);
                    if (mat1 == null) mat1 = new Mat(b.getWidth(), b.getHeight(), CvType.CV_8UC1);
                    Mat grayMat = new Mat();


                    Utils.bitmapToMat(b, mat);
                    Imgproc.cvtColor(mat, mat1, Imgproc.COLOR_BGR2GRAY);
                    clahe.apply(mat1, grayMat);




                    MatOfRect faces0 = new MatOfRect();
                    haar_faceClassifier.detectMultiScale(grayMat, faces0, 1.1, 4, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());
                    for (org.opencv.core.Rect face : faces0.toArray()) {
                        int top = (int) (face.tl().y) * 2;
                        int left = (int) (face.tl().x) * 2;
                        int bottom = (int) (face.br().y) * 2;
                        int right = (int) (face.br().x) * 2;
                        canvas.drawRect(left, top, right, bottom, myPaint);
                    }


                    /* uncomment to process sideprofiles of images as well*/
                    /*
                    Mat grayMat_flipped = new Mat();
                    flip(grayMat, grayMat_flipped, 1);

                    MatOfRect faces1 = new MatOfRect();
                    haar_sideClassifier.detectMultiScale(grayMat, faces1, 1.1, 4, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());
                    MatOfRect faces2 = new MatOfRect();
                    haar_sideClassifier.detectMultiScale(grayMat_flipped, faces2, 1.1, 4, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());


                    for (org.opencv.core.Rect face : faces1.toArray()) {
                        int top = (int) (face.tl().y) * 2;
                        int left = (int) (face.tl().x) * 2;
                        int bottom = (int) (face.br().y) * 2;
                        int right = (int) (face.br().x) * 2;
                        canvas.drawRect(left, top, right, bottom, myPaint);
                    }
                    for (org.opencv.core.Rect face : faces2.toArray()) {
                        int top = (int) (face.tl().y) * 2;
                        int left = (int) (face.tl().x) * 2;
                        int bottom = (int) (face.br().y) * 2;
                        int right = (int) (face.br().x) * 2;
                        canvas.drawRect(canvas.getWidth() - left, top, canvas.getWidth() - right, bottom, myPaint);
                    }
                    */

                    canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), borderPaint);

                    if (canvas != null) {
                        if (surface == null) break;
                        surface.unlockCanvasAndPost(canvas);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
                try {
                    sleep(RENDER_INTERVAL_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }


        }

        private void prepareClassifiers() {
            try {
                InputStream is = getResources().openRawResource(R.raw.haarcascade_frontalface_alt);
                File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
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

            try {
                InputStream is = getResources().openRawResource(R.raw.haarcascade_profileface);
                File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
                File mCascadeFile = new File(cascadeDir, "haarcascade_profileface.xml");
                FileOutputStream os = new FileOutputStream(mCascadeFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                is.close();
                os.close();
                haar_sideClassifier = new CascadeClassifier(mCascadeFile.getAbsolutePath());
                if (haar_sideClassifier.empty()) {
                    Log.e("classify", "Failed to load sideprof cascade classifier");
                    haar_sideClassifier = null;
                } else
                    Log.i(TAG, "Loaded cascade classifier from " + mCascadeFile.getAbsolutePath());
            }
            catch(Exception e){
                e.printStackTrace();
            }
        }


        @Override   // will be called on UI thread
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            Log.d(TAG + " renderer", "onSurfaceTextureAvailable(" + width + "x" + height + ")");
            mWidth = width;
            mHeight = height;
            synchronized (mLock) {
                mSurfaceTexture = st;
                mLock.notify();
            }
        }

        @Override   // will be called on UI thread
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            Log.d(TAG + " renderer", "onSurfaceTextureSizeChanged(" + width + "x" + height + ")");
            mWidth = width;
            mHeight = height;
        }

        @Override   // will be called on UI thread
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            Log.d(TAG + " renderer", "onSurfaceTextureDestroyed");

            synchronized (mLock) {
                mSurfaceTexture = null;
            }
            return true;
        }

        @Override   // will be called on UI thread
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            Log.d(TAG + " renderer", "onSurfaceTextureUpdated");
        }
    }

    /*------------------------renderer private class-----------------------*/


}
