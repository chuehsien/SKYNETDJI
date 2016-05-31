package com.example.chueh.skynetdji;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dji.sdk.Camera.DJICamera;
import dji.sdk.Products.DJIAircraft;
import dji.sdk.base.DJIBaseProduct;

/**
 * Created by chue on 5/24/16.
 */
public class ATAKServiceHandlerPersistent extends Service {

    static final int MSG_SAY_HELLO = 1;
    static final int MSG_MESSENGER_DJIAPP = 21;
    static final int MSG_MESSENGER_ATAKAPP = 22;
    static final int MSG_STREAM = 3;
    static final int MSG_IS_DJIAPP_CONNECTED = 4;
    static final int MSG_IS_ATAKAPP_CONNECTED = 5;
    static final int MSG_START_BRIDGE = 6;
    static final int FALSE = 000;
    static final int TRUE = 111;
    private static final String TAG = ATAKServiceHandlerPersistent.class.getName();

    private boolean serviceStartBroadcast;


    private UsbAccessory accessory;
    private static final String ACTION_USB_PERMISSION ="com.android.example.USB_PERMISSION";

    InputStream djiStream = null;
    Messenger djiApp = null;
    OutputStream atakStream = null;
    Messenger atakApp = null;
    ParcelFileDescriptor[] pfdArr;
    int numBound;

    @Override
    public void onCreate() {
        super.onCreate();
        numBound = 0;
        serviceStartBroadcast = false;
        UsbManager mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);


        UsbAccessory[] accessoryList = mUsbManager.getAccessoryList();
        if (accessoryList != null && accessoryList.length >0) {
            accessory = accessoryList[0];

            if (mUsbManager.hasPermission(accessory)) {
                Intent myIntent = new Intent(getApplicationContext(), dji.sdk.SDKManager.DJIAoaControllerActivity.class);
                myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(myIntent);
                Log.d(TAG, "Already had permissions, started AoaActivity");
            } else {
                mUsbManager.requestPermission(accessory, mPermissionIntent);
            }
        }

        Log.d(TAG,"Registering broadcast filters to service");
        IntentFilter filter2 = new IntentFilter();
        filter2.addAction(MainApplication.FLAG_CONNECTION_CHANGE);
        filter2.addAction(ACTION_USB_PERMISSION);
        filter2.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mReceiver, filter2);

    }
    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG,"Received filter: " + intent.getAction());

            if (intent.getAction() == MainApplication.FLAG_CONNECTION_CHANGE) {
//                updateTitleBar();
//                onProductChange();
//                initPreviewer();
                 initProduct();

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
//                UsbAccessory accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                Log.d(TAG,"accessory detached");
            }

        }

    };


    private void initProduct() {

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
        }else{
            initPreviewer();
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
                    camera.setDJICameraReceivedVideoDataCallback(mReceivedVideoDataCallBack);
                    Log.d(TAG,"receiving video from " + camera.getDisplayName());
                }
            }
        }
    }

    public DJICamera.CameraReceivedVideoDataCallback mReceivedVideoDataCallBack = new DJICamera.CameraReceivedVideoDataCallback() {

        @Override
        public void onResult(final byte[] videoBuffer, final int size) {
            Log.d("thread","Received video data " + size);
            if (serviceStartBroadcast) {
                new Thread() {
                    public void run() {
                        Log.d(TAG, "Writing " + size);
//                    writeToStream(videoBuffer,size);
                    }
                }.start();
            }
        }
    };
    public void writeToStream(byte[] buf, int size){

        if (atakStream == null) Log.d(TAG,"os = null");
        else {

            try {
//                Log.d(TAG,"writing to os: " + size + " bytes");
                atakStream.write(buf,0,size);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    @Override
    public void onDestroy() {
//        Log.e(TAG, "onDestroy");
//        uninitPreviewer();
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case MSG_SAY_HELLO:
                    Toast.makeText(getApplicationContext(), "hello!", Toast.LENGTH_SHORT).show();
                    break;
//                case MSG_MESSENGER_DJIAPP:
//                    Log.d(TAG,"received djiapp messenger!");
//                    djiApp = msg.replyTo;
//                    if (pfdArr == null) {
//                        try {
//                            pfdArr = ParcelFileDescriptor.createPipe();
//
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                            break;
//                        }
//                    }
//
//                    //send write stream to dji app : pfdArr[1]
//                    Message replyMsg = Message.obtain(null, ATAKServiceHandler.MSG_STREAM, 0, 0);
//                    Bundle b = new Bundle();
//                    b.putParcelable("pfd",pfdArr[1]);
//                    replyMsg.setData(b);
//                    try{
//                        Log.d(TAG,"djiapp <---STREAM---- service"+pfdArr[0] + " " + pfdArr[1]);
//                        djiApp.send(replyMsg);
//                    }catch (RemoteException e){
//                        e.printStackTrace();
//                        break;
//                    }
//                    //read stream for myself:
//                    // djiStream = new ParcelFileDescriptor.AutoCloseInputStream(pfdArr[0]);
//                    break;

                case MSG_MESSENGER_ATAKAPP:
                    Log.d(TAG,"received atakapp messenger!");
                    atakApp = msg.replyTo;

                    if (pfdArr == null) {
                        try {
                            pfdArr = ParcelFileDescriptor.createPipe();

                        } catch (IOException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                    //send write stream to dji app : pfdArr[1]
                    Message replyMsg2 = Message.obtain(null, ATAKServiceHandler.MSG_STREAM, 0, 0);
                    Bundle b2 = new Bundle();
                    b2.putParcelable("pfd",pfdArr[0]);
                    replyMsg2.setData(b2);
                    try{
                        Log.d(TAG,"atakapp <---STREAM---- service"+pfdArr[0] + " " + pfdArr[1]);
                        atakApp.send(replyMsg2);
                    }catch (RemoteException e){
                        e.printStackTrace();
                        break;
                    }

                    //read stream for myself:
                    atakStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfdArr[1]);

                    break;

                case MSG_IS_ATAKAPP_CONNECTED:
                    //send write stream to dji app : pfdArr[1]
                    Message replyMsg3 = Message.obtain(null, ATAKServiceHandler.MSG_IS_ATAKAPP_CONNECTED, 0, 0);
                    replyMsg3.arg1 = (atakApp==null) ? FALSE : TRUE;
                    try{
                        djiApp.send(replyMsg3);
                    }catch (RemoteException e){
                        e.printStackTrace();
                        break;
                    }

                    break;
                case MSG_IS_DJIAPP_CONNECTED:
                    //send write stream to dji app : pfdArr[1]
                    Message replyMsg4 = Message.obtain(null, ATAKServiceHandler.MSG_IS_DJIAPP_CONNECTED, 0, 0);
                    replyMsg4.arg1 = (djiApp==null) ? FALSE : TRUE;
                    try{
                        atakApp.send(replyMsg4);
                    }catch (RemoteException e){
                        e.printStackTrace();
                        break;
                    }
                    break;
                case MSG_START_BRIDGE:
                    Log.d(TAG,"Received request to start copystream");
                    if (atakApp!=null && djiApp!=null){
//                        new Thread()
//                        {
//                            public void run() {
//                                copyStream(djiStream,atakStream);
//                            }
//                        }.start();

//                       MainActivity.bridgeOn = true;
                        serviceStartBroadcast = true;

                    }
                    break;

                default:
                    Log.d(TAG,"received unknown msg");
                    super.handleMessage(msg);
            }
        }
    }


    final Messenger mMessenger = new Messenger(new IncomingHandler());

    @Override
    public boolean onUnbind(Intent intent) {
        numBound --;
        if (numBound == 0){
            pfdArr = null;
        }
        return super.onUnbind(intent);

    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG,"onBind");
        numBound ++;
        Toast.makeText(getApplicationContext(), "onBind", Toast.LENGTH_SHORT).show();
        return mMessenger.getBinder();
    }




    public void copyStream(InputStream input, OutputStream output)
    {
        MainActivity.bridgeOn = true;
        byte[] buffer = new byte[2050]; // Adjust if you want
        int bytesRead;

        try {
            while ((bytesRead = input.read(buffer)) != -1) {
//                Log.d(TAG,"copyStream: "  + bytesRead);
                output.write(buffer, 0, bytesRead);
                output.flush();
            }
        }catch (IOException e){
            e.printStackTrace();
            Log.d(TAG,"copyStream failed!");
            atakApp = null;
            atakStream = null;
            return;
        }

        MainActivity.bridgeOn = false;


    }

}
