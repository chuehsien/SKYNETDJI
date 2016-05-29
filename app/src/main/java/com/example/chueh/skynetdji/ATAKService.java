package com.example.chueh.skynetdji;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import com.example.chueh.aidl.atakservice.IATAKService;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import dji.midware.data.model.P3.DataFlycUploadWayPointMsgByIndex;
import dji.sdk.Camera.DJICamera;
import dji.sdk.Camera.DJICameraSettingsDef;
import dji.sdk.Codec.DJICodecManager;
import dji.sdk.Products.DJIAircraft;
import dji.sdk.base.DJIBaseProduct;

/**
 * Created by chueh on 5/23/2016.
 */
public class ATAKService extends Service {

    private static final String TAG = ATAKService.class.getName();
    private OutputStream outStream = null;
    private StreamThread th;
    private UsbAccessory accessory;
    private static final String ACTION_USB_PERMISSION ="com.android.example.USB_PERMISSION";
    protected TextureView mVideoSurface = null;
    private Renderer mRenderer; protected DJICodecManager mCodecManager = null;
    public void writeToStream(byte[] buf, int size){

        if (outStream == null) Log.d(TAG,"os = null");
        else {

            try {
//                Log.d(TAG,"writing to os: " + size + " bytes");
                outStream.write(buf,0,size);
            } catch (IOException e) {
                e.printStackTrace();
            }


        }


    }

    public DJICamera.CameraReceivedVideoDataCallback mReceivedVideoDataCallBack = new DJICamera.CameraReceivedVideoDataCallback() {

        @Override
        public void onResult(final byte[] videoBuffer, int size) {
            Log.d("thread","Received video data");
            if (mCodecManager != null){
                Log.d("thread","forward to codecmanager");
                mCodecManager.sendDataToDecoder(videoBuffer, size);
            }
            new Thread(){
                public void run(){
                    Log.d("thread","Writing video data");
                }
            }.start();

//                if (getOutStream() == null) Log.d(TAG,"onresult: outstream: null");
//
//                else Log.d(TAG,"onresult: outstream: " + getOutStream().toString());
//
//                if (mCodecManager!=null) {
//                    mCodecManager.sendDataToDecoder(videoBuffer, size);
//                }else Log.e(TAG, "mCodecManager is null " + size);


            //writeToStream(videoBuffer,size);
        }
    };


    public static void printMap(Map mp) {
        Iterator it = mp.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            Log.d(TAG,pair.getKey() + " = " + pair.getValue());
            it.remove(); // avoids a ConcurrentModificationException
        }
    }


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("service","onCreate!");

        UsbManager mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);


//        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
//        Log.d(TAG,"Size0: " + deviceList.size());
//        printMap(deviceList);


//        Log.d(TAG,"Size1: " + accessoryList.length);
//        for (UsbAccessory ua : accessoryList){
//            Log.d(TAG,"usb accessory: " + ua.getDescription());
//        }

//        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
//        registerReceiver(mUsbReceiver, filter);
        // The callback for receiving the raw H264 video data for camera live view




        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter2 = new IntentFilter();
        filter2.addAction(MainApplication.FLAG_CONNECTION_CHANGE);
        filter2.addAction(ACTION_USB_PERMISSION);
        filter2.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mReceiver, filter2);



        UsbAccessory[] accessoryList = mUsbManager.getAccessoryList();
        if (accessoryList != null && accessoryList.length == 1){
            accessory = accessoryList[0];

            if (mUsbManager.hasPermission(accessory)){
//                Intent myIntent = new Intent(getApplicationContext(), dji.sdk.SDKManager.DJIAoaControllerActivity.class);
//                myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//                startActivity(myIntent);
//                Log.d(TAG, "Already had permissions, started AoaActivity");
            }else{
                mUsbManager.requestPermission(accessory, mPermissionIntent);
            }


        }else{
            Log.d(TAG,"Connect DJIcontroller!");
        }

    }



    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG,"Received filter: " + intent.getAction());

            if (intent.getAction() == MainApplication.FLAG_CONNECTION_CHANGE) {
//                updateTitleBar();
//                onProductChange();
//                initPreviewer();
                logProductStatus();
            }else if (intent.getAction() == ACTION_USB_PERMISSION) {
                accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                Boolean perm = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                if (perm && (accessory != null)) {
                    Log.d(TAG, "USB permission allowed");

//                    Intent myIntent = new Intent(getApplicationContext(), dji.sdk.SDKManager.DJIAoaControllerActivity.class);
//                    myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//                    startActivity(myIntent);
//                    Log.d(TAG, "Started AoaActivity");
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
    @Override
    public void onDestroy() {
//        Log.e(TAG, "onDestroy");
//        uninitPreviewer();
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

//    protected void onProductChange() {
//        initPreviewer();
//    }

    protected class Renderer extends Thread implements TextureView.SurfaceTextureListener{
        private Object mLock = new Object();        // guards mSurfaceTexture, mDone
        private boolean mDone;
        SurfaceTexture mSurfaceTexture = null;
        @Override
        public void run() {
            Log.d(TAG,"Thread started!");
            while (true) {
                SurfaceTexture surfaceTexture = null;

                // Latch the SurfaceTexture when it becomes available.  We have to wait for
                // the TextureView to create it.
                synchronized (mLock) {
                    while (!mDone && (surfaceTexture = mSurfaceTexture) == null) {
                        try {
                            Log.d(TAG,"Thread waiting!");
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
                //doAnimation();
            }

//        Log.d(TAG, "Renderer thread exiting");
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            Log.d(TAG, "onSurfaceTextureAvailable");
            if (mCodecManager == null) {
                mCodecManager = new DJICodecManager(getApplicationContext(), surfaceTexture, i, i1);
            }
            synchronized (mLock) {
                mSurfaceTexture = surfaceTexture;
                mLock.notify();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            synchronized (mLock) {
                mSurfaceTexture = null;
            }
            return true;

        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    }
    private void initPreviewer() {

        DJIBaseProduct product = MainApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            Log.d(TAG,getString(R.string.disconnected));
        } else {

            if (!product.getModel().equals(DJIBaseProduct.Model.UnknownAircraft)) {
                DJICamera camera = product.getCamera();
                if (camera != null){
                    // Set the callback
                    LayoutInflater inflater = (LayoutInflater) getApplicationContext().getSystemService( Context.LAYOUT_INFLATER_SERVICE );
                    View child = inflater.inflate(R.layout.activity_main, null);
                    mVideoSurface = (TextureView)child.findViewById(R.id.video_previewer_surface);
                    mRenderer = new Renderer();
                    mRenderer.start();

                    if (null != mVideoSurface) {
                        mVideoSurface.setSurfaceTextureListener(mRenderer);
                    }else{
                        Log.d(TAG,"videosurface is null");

                    }

                    camera.setDJICameraReceivedVideoDataCallback(mReceivedVideoDataCallBack);
                    Log.d(TAG,"receiving video from " + camera.getDisplayName());
                }
            }
        }
    }


    private void logProductStatus() {

        boolean ret = false;
        DJIBaseProduct product = MainApplication.getProductInstance();
        if (product != null) {
            if(product.isConnected()) {
                //The product is connected
                Log.d(TAG,MainApplication.getProductInstance().getModel() + " Connected");
                ret = true;
            } else {
                if(product instanceof DJIAircraft) {
                    DJIAircraft aircraft = (DJIAircraft)product;
                    if(aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                        // The product is not connected, but the remote controller is connected
                        Log.d(TAG,"only RC Connected");
                        ret = true;
                    }
                }
            }
        }

        if(!ret) {
            // The product or the remote controller are not connected.
            Log.d(TAG, "Disconnected");
        }
    }


    public void showToast(final String msg) {
        Handler handler = new Handler(Looper.getMainLooper());

        handler.post(new Runnable() {

            @Override
            public void run() {
                Toast.makeText(ATAKService.this.getApplicationContext(),msg,Toast.LENGTH_SHORT).show();
            }
        });


    }
    public void setWritePfd(ParcelFileDescriptor writePfd){
        Log.d(TAG,"pipe created, registering outputstream");
        outStream = new ParcelFileDescriptor.AutoCloseOutputStream(writePfd);
       // Log.d(TAG,"out pfd is: " + getOutStream().toString());


    }
    @Override
    public IBinder onBind(Intent intent) {
        // Return the interface
        Log.d(TAG,"Binded to app");
        return mBinder;
    }


    protected final IATAKService.Stub mBinder = new IATAKService.Stub() {
        @Override
        public int add(int num1, int num2) throws RemoteException {
            Log.d("service","add called");
            return num1+num2;
        }



        public ParcelFileDescriptor startDJIVid() throws RemoteException{
            Log.d("service","startDJIVid called");
            initPreviewer();

            ParcelFileDescriptor[] pfdArr;
            try {
                pfdArr = ParcelFileDescriptor.createPipe();

            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }

            setWritePfd(pfdArr[1]);

            return pfdArr[0];


        }

    };
}