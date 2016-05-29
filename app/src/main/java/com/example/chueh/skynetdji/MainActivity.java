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

import com.example.chueh.aidl.atakservice.IATAKService;

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
    private static final String PRODUCT_READY = "product ready";
//    private int TIME = 1000;
//    public Handler mhandler;
    private VideoOverlay myVideoOverlay;

    protected DJICamera.CameraReceivedVideoDataCallback mReceivedVideoDataCallBack = null;

    // Codec for video live view
    protected DJICodecManager mCodecManager = null;
    protected TextView mConnectStatusTextView;

    protected TextureView mVideoSurface = null;
    protected TextureView mOverlapSurface = null;

    private Button mCaptureBtn, mShootPhotoModeBtn, mRecordVideoModeBtn;
    private ToggleButton mRecordBtn;
    private TextView recordingTime;


    private ATAKService bgService;
    private ServiceConnection serviceConnection;
    boolean mBound = false;
    Messenger mService = null;
    private boolean atakAppConnected = false;
    private Handler mHandler = null;
    private Object atakBooleanLock = new Object();
    private final Messenger mActivityMessenger = new Messenger(
            new ActivityHandler(this));


    private final static byte[] delimiter = {0,0,1,0,0,0,0}; //last 2 bytes are for size.

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

//    static {
//        System.loadLibrary("hello-android-jni");
//    }
//    public native String getMsgFromJni();
//
    private OutputStream outStream;
    public static boolean bridgeOn = false;
    private static final String ACTION_USB_PERMISSION ="com.android.example.USB_PERMISSION";
    private UsbAccessory accessory;

    public void setWritePfd(ParcelFileDescriptor writePfd){
        Log.d(TAG,"pipe created, registering outputstream");
        outStream = new ParcelFileDescriptor.AutoCloseOutputStream(writePfd);
    }

    public void writeToStream(byte[] buf, int size){

        if (outStream == null) Log.d(TAG,"os = null");
        if (!atakAppConnected) Log.d(TAG,"atakApp is not connected yet");
        else {

            try {
               // Log.d(TAG,"writing to os: " + size + " bytes");
                //Log.d(TAG,bytesToHexString(buf,size));
//                delimiter[6] = (byte)(0xFF & size);
//                delimiter[5] = (byte)((size>>8)&0xFF);
//                outStream.write(delimiter,0,7);
                outStream.write(buf,0,size);
            } catch (IOException e) {
                e.printStackTrace();
            }


        }

    }


    public void sayHello() {
        Log.d(TAG,"djiapp --HELLO--> service");
        if (!mBound) return;
        // Create and send a message to the service, using a supported 'what' value
        Message msg = Message.obtain(null, ATAKServiceHandler.MSG_MESSENGER_DJIAPP, 0, 0);
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

        setContentView(R.layout.activity_main);
        mHandler = new Handler();
        mHandler.postDelayed(updateRunnable,5000);


        Log.d(TAG,"onCreate");
//        UsbManager mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
//        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);

        serviceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder service) {
                // This is called when the connection with the service has been
                // established, giving us the object we can use to
                // interact with the service.  We are communicating with the
                // service using a Messenger, so here we get a client-side
                // representation of that from the raw IBinder object.
                mService = new Messenger(service);
                mBound = true;
                Log.d(TAG,"Connected to service!");
                sayHello();
            }

            public void onServiceDisconnected(ComponentName className) {
                // This is called when the connection with the service has been
                // unexpectedly disconnected -- that is, its process crashed.
                Log.d(TAG,"Disconnected from service!");
                mService = null;
                mBound = false;
            }
        };



//        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
//        Log.d(TAG,"Size0: " + deviceList.size());
//        printMap(deviceList);

//        UsbAccessory[] accessoryList = mUsbManager.getAccessoryList();
//        if (accessoryList != null && accessoryList.length == 1){
//            accessory = accessoryList[0];
//        }else{
//            Log.d(TAG,"Connect DJIcontroller!");
//        }





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
//                    Log.d(TAG,"atak not connected");
                    if (mCodecManager != null) {
                        //Log.d(TAG,"Bytes: " + bytesToHexString(videoBuffer,size));

                        //add header information


                        mCodecManager.sendDataToDecoder(videoBuffer, size);
                    } else {
                        //Log.e(TAG, "mCodecManager is null " + size);
                    }
                }else{
                    if (bridgeOn)writeToStream(videoBuffer,size);
                }
            }
        };

        DJICamera camera = MainApplication.getCameraInstance();

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

        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(MainApplication.FLAG_CONNECTION_CHANGE);
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
//        filter.addAction(PRODUCT_READY);
        registerReceiver(mReceiver, filter);
//        mUsbManager.requestPermission(accessory, mPermissionIntent);

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
            }else if (intent.getAction() == ACTION_USB_PERMISSION) {
                accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                Boolean perm = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                if (perm && (accessory != null)) {
                    Log.d(TAG, "USB permission allowed");

                    Intent myIntent = new Intent(getApplicationContext(), dji.sdk.SDKManager.DJIAoaControllerActivity.class);
                    myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(myIntent);
                    Log.d(TAG, "Started AoaActivity");
                }
                else if (perm && (accessory == null)){
                    Log.d(TAG, "USB accessory null");
                }else if (!perm) {
                    Log.d(TAG, "USB permission denied");
                }


            }else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())) {
                UsbAccessory accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                Log.d(TAG,"accessory detached");
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
//        initPreviewer();
//        //myVideoOverlay.start();
//        updateTitleBar();
//        if(mVideoSurface == null) {
//            Log.e(TAG, "mVideoSurface is null");
//        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
//        uninitPreviewer();
//        //myVideoOverlay.halt();
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
//        uninitPreviewer();
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
//        myVideoOverlay = new VideoOverlay(mOverlapSurface,this);
//        myVideoOverlay.start();

        mOverlapSurface.setSurfaceTextureListener(mRenderer);
        mOverlapSurface.setAlpha(0.99f);



        mVideoSurface = (TextureView)findViewById(R.id.video_previewer_surface);
        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }



//        mOverlapSurface = (TextureView) findViewById(R.id.overlap_surface);
//        mOverlapSurface.setSurfaceTextureListener(myVideoOverlay);
//


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
//                     Set the callback
                    camera.setDJICameraReceivedVideoDataCallback(mReceivedVideoDataCallBack);
//                    Intent intent = new Intent(PRODUCT_READY);
//                    sendBroadcast(intent);
                    showToast("Connected to " + camera.getDisplayName());

                }
            }
        }
    }

    private void uninitPreviewer() {
        DJICamera camera = MainApplication.getCameraInstance();
        if (camera != null){
            // Reset the callback
            MainApplication.getCameraInstance().setDJICameraReceivedVideoDataCallback(null);
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
        //mCodecManager = null;
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

    // Method for taking photo
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

    // Method for starting recording
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

    // Method for stopping recording
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
        Log.i("FPV","changing aspect ratio");
        int viewWidth = mVideoSurface.getWidth();
        int viewHeight = mVideoSurface.getHeight();
        Log.i("FPV","viewWidth :" + viewWidth + " viewHeight: " +viewHeight);
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
                Log.d("bitmap", "Got surfaceTexture=" + surfaceTexture);

                // Render frames until we're told to stop or the SurfaceTexture is destroyed.
                doAnimation();
            }

//        Log.d(TAG, "Renderer thread exiting");
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
                    canvas.drawColor(0, PorterDuff.Mode.CLEAR); //clear canvas of previosu rectangle

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

                    Bitmap b = Bitmap.createScaledBitmap(mVideoSurface.getBitmap(), bitmapW / 2, bitmapH / 2, false);

                    if (mat == null) mat = new Mat(b.getWidth(), b.getHeight(), CvType.CV_8UC1);
                    if (mat1 == null) mat1 = new Mat(b.getWidth(), b.getHeight(), CvType.CV_8UC1);
                    Mat grayMat = new Mat();


                    Utils.bitmapToMat(b, mat);
                    Imgproc.cvtColor(mat, mat1, Imgproc.COLOR_BGR2GRAY);
                    clahe.apply(mat1, grayMat);

//                    Mat grayMat_flipped = new Mat();
//                    flip(grayMat, grayMat_flipped, 1);

                    MatOfRect faces0 = new MatOfRect();
                    haar_faceClassifier.detectMultiScale(grayMat, faces0, 1.1, 4, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());
//                    MatOfRect faces1 = new MatOfRect();
//                    haar_sideClassifier.detectMultiScale(grayMat, faces1, 1.1, 4, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());
//                    MatOfRect faces2 = new MatOfRect();
//                    haar_sideClassifier.detectMultiScale(grayMat_flipped, faces2, 1.1, 4, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());

                    for (org.opencv.core.Rect face : faces0.toArray()) {
                        int top = (int) (face.tl().y) * 2;
                        int left = (int) (face.tl().x) * 2;
                        int bottom = (int) (face.br().y) * 2;
                        int right = (int) (face.br().x) * 2;
                        canvas.drawRect(left, top, right, bottom, myPaint);
                    }
//                    for (org.opencv.core.Rect face : faces1.toArray()) {
//                        int top = (int) (face.tl().y) * 2;
//                        int left = (int) (face.tl().x) * 2;
//                        int bottom = (int) (face.br().y) * 2;
//                        int right = (int) (face.br().x) * 2;
//                        canvas.drawRect(left, top, right, bottom, myPaint);
//                    }
//                    for (org.opencv.core.Rect face : faces2.toArray()) {
//                        int top = (int) (face.tl().y) * 2;
//                        int left = (int) (face.tl().x) * 2;
//                        int bottom = (int) (face.br().y) * 2;
//                        int right = (int) (face.br().x) * 2;
//                        canvas.drawRect(canvas.getWidth() - left, top, canvas.getWidth() - right, bottom, myPaint);
//                    }

                    canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), borderPaint);
                    Log.d(TAG,"Still drawing");

                    if (canvas != null) {
                        if (surface == null) break;
                        // if (surface)

                        surface.unlockCanvasAndPost(canvas);
                    }


                } catch (IllegalArgumentException iae) {
                    Log.d("classify", "unlockCanvasAndPost failed: " + iae.getMessage());
                    break;
                } catch (Exception e1) {
                    e1.printStackTrace();
                    break;
                }
                try {
                    sleep(1000);
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
            Log.i("bitmap", "surface tex2: " + st.toString());
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
    }







}
