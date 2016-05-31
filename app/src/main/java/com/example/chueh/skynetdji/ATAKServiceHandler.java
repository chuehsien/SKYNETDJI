package com.example.chueh.skynetdji;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dji.sdk.base.DJIBaseProduct;

/**
 * Created by chue on 5/24/16.
 */
public class ATAKServiceHandler extends Service {

    static final int MSG_SAY_HELLO = 1;
    static final int MSG_MESSENGER_DJIAPP = 21;
    static final int MSG_MESSENGER_ATAKAPP = 22;
    static final int MSG_STREAM = 3;
    static final int MSG_IS_DJIAPP_CONNECTED = 4;
    static final int MSG_IS_ATAKAPP_CONNECTED = 5;
    static final int MSG_START_BRIDGE = 6;
    static final int MSG_STOP_BRIDGE = 7;
    static final int MSG_HELLO_FROM_ATAK = 8;
    static final int MSG_HELLO_FROM_DJI = 9;
    static final int MSG_HELLO_FROM_SERVICE = 10;
    static final int FALSE = 000;
    static final int TRUE = 111;


    InputStream djiStream = null;
    Messenger djiApp = null;
    OutputStream atakStream = null;
    Messenger atakApp = null;
    ParcelFileDescriptor[] pfdArr;
    int numBound;
    CountDownTimer atakCountdown, djiCountdown;

    @Override
    public void onCreate() {
        super.onCreate();
        numBound = 0;
    }


    private static final String TAG = ATAKServiceHandler.class.getName();

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case MSG_SAY_HELLO:
                    Toast.makeText(getApplicationContext(), "hello!", Toast.LENGTH_SHORT).show();
                    break;
                case MSG_HELLO_FROM_ATAK:
                    Log.d(TAG,"Hello from atak");
                    if (atakCountdown!= null){
                        atakCountdown.cancel();
                        atakCountdown.start();
                    }

                    Message replyMsg3 = Message.obtain(null, ATAKServiceHandler.MSG_HELLO_FROM_SERVICE, 0, 0);
                    Bundle b3 = new Bundle();
                    if (djiApp==null){
                        b3.putString("status","Please start SKYNETDJI app first before launching ATAK");
                    }else{
                        DJIBaseProduct product = MainApplication.getProductInstance();
                        if (product != null && product.isConnected())
                            b3.putString("status", MainApplication.getProductInstance().getModel() + " Connected");
                        else{
                            b3.putString("status","Waiting for connection with drone");
                        }
                    }

                    replyMsg3.setData(b3);
                    try{
                        if (atakApp!=null) atakApp.send(replyMsg3);
                    }catch (RemoteException e){
                        e.printStackTrace();
                        break;
                    }
                    break;
                case MSG_HELLO_FROM_DJI:
                    Log.d(TAG,"Hello from dji");
                //    Log.d(TAG,"received hello from dji");
                    if (djiCountdown!= null){
                        djiCountdown.cancel();
                        djiCountdown.start();
                    }
                    break;
                case MSG_MESSENGER_DJIAPP:
                    if (djiCountdown!= null){
                        djiCountdown.cancel();
                        djiCountdown.start();
                    }
                //    Log.d(TAG,"received djiapp messenger!");
                    djiApp = msg.replyTo;
                    if (pfdArr == null) {
                        Log.d(TAG,"Creating new pipe");
                        try {
                            pfdArr = ParcelFileDescriptor.createPipe();

                        } catch (IOException e) {
                            e.printStackTrace();
                            break;
                        }
                    }

                    //send write stream to dji app : pfdArr[1]
                    Message replyMsg = Message.obtain(null, ATAKServiceHandler.MSG_STREAM, 0, 0);
                    Bundle b = new Bundle();
                    b.putParcelable("pfd",pfdArr[1]);
                    replyMsg.setData(b);
                    try{
                        //Log.d(TAG,"djiapp <---STREAM---- service"+pfdArr[0] + " " + pfdArr[1]);
                        djiApp.send(replyMsg);
                    }catch (RemoteException e){
                        e.printStackTrace();
                        break;
                    }
                    if (atakApp!=null){
                        Message replyMsg2 = Message.obtain(null, ATAKServiceHandler.MSG_STREAM, 0, 0);
                        Bundle b2 = new Bundle();
                        DJIBaseProduct product = MainApplication.getProductInstance();
                        if (product != null && product.isConnected())
                            b2.putString("status", MainApplication.getProductInstance().getModel() + " Connected");
                        else{
                            b2.putString("status","Waiting for connection with drone");
                        }
                        replyMsg2.setData(b2);
                        try{
                            atakApp.send(replyMsg2);
                        }catch (RemoteException e){
                            e.printStackTrace();
                            break;
                        }
                    }



                    djiCountdown = new CountDownTimer(10000, 9000) {

                        public void onTick(long millisUntilFinished) {
//                            mTextField.setText("seconds remaining: " + millisUntilFinished / 1000);
                        }
                        public void onFinish() {
                            disconnectDji();
                        }
                    }.start();



                    //read stream for myself:
                   // djiStream = new ParcelFileDescriptor.AutoCloseInputStream(pfdArr[0]);
                    break;

                case MSG_MESSENGER_ATAKAPP:
                    Log.d(TAG,"received atakapp messenger!");
                    if (atakCountdown!= null){
                        atakCountdown.cancel();
                        atakCountdown.start();
                    }
                    atakApp = msg.replyTo;

                    if (pfdArr == null) {
                        try {
                            Log.d(TAG,"Creating new pipe");
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
                    if (djiApp==null){
                        b2.putString("status","Please start SKYNETDJI app first before launching ATAK");
                    }else{
                        DJIBaseProduct product = MainApplication.getProductInstance();
                        if (product != null && product.isConnected())
                            b2.putString("status", MainApplication.getProductInstance().getModel() + " Connected");
                        else{
                            b2.putString("status","Waiting for connection with drone");
                        }
                    }

                    replyMsg2.setData(b2);
                    try{
                        //Log.d(TAG,"atakapp <---STREAM---- service"+pfdArr[0] + " " + pfdArr[1]);
                        atakApp.send(replyMsg2);
                    }catch (RemoteException e){
                        e.printStackTrace();
                        break;
                    }
                    //read stream for myself:
                    //atakStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfdArr2[1]);
                    atakCountdown = new CountDownTimer(10000, 9000) {

                        public void onTick(long millisUntilFinished) {
//                            mTextField.setText("seconds remaining: " + millisUntilFinished / 1000);
                        }
                        public void onFinish() {
                            disconnectAtak();
                        }
                    }.start();
                    break;

                case MSG_IS_ATAKAPP_CONNECTED:
                    //send write stream to dji app : pfdArr[1]
                    Message replyMsg5 = Message.obtain(null, ATAKServiceHandler.MSG_IS_ATAKAPP_CONNECTED, 0, 0);
                    replyMsg5.arg1 = (atakApp==null) ? FALSE : TRUE;
                    try{
                        djiApp.send(replyMsg5);
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
                    if (atakApp!=null && djiApp!=null){
                        MainActivity.bridgeOn = true;
                    }
                    break;

                case MSG_STOP_BRIDGE:
                    MainActivity.bridgeOn = false;
                    break;

                default:
                    Log.d(TAG,"received unknown msg");
                    super.handleMessage(msg);
            }
        }
    }


    final Messenger mMessenger = new Messenger(new IncomingHandler());
    private void disconnectDji(){
        Log.d(TAG,"Disconnecting DJI");
        MainActivity.bridgeOn = false;
        djiStream = null;
        djiApp = null;
        djiCountdown.cancel();
        djiCountdown = null;
        if (atakApp==null && djiApp==null){
            pfdArr = null;
        }
    }

    private void disconnectAtak(){
        Log.d(TAG,"Disconnecting ATAK");
        MainActivity.bridgeOn = false;
        atakStream = null;
        atakApp = null;
        atakCountdown.cancel();
        atakCountdown = null;
        if (atakApp==null && djiApp==null){
            pfdArr = null;
        }
    }


    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG,"onUnbind");
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
        Toast.makeText(getApplicationContext(), "binding", Toast.LENGTH_SHORT).show();
        return mMessenger.getBinder();
    }

}
