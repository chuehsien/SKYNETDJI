package com.example.chueh.skynetdji;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
    static final int FALSE = 000;
    static final int TRUE = 111;


    InputStream djiStream = null;
    Messenger djiApp = null;
    OutputStream atakStream = null;
    Messenger atakApp = null;

    private static final String TAG = ATAKServiceHandler.class.getName();

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case MSG_SAY_HELLO:
                    Toast.makeText(getApplicationContext(), "hello!", Toast.LENGTH_SHORT).show();
                    break;
                case MSG_MESSENGER_DJIAPP:
                    Log.d(TAG,"received djiapp messenger!");
                    djiApp = msg.replyTo;

                    ParcelFileDescriptor[] pfdArr;
                    try {
                        pfdArr = ParcelFileDescriptor.createPipe();

                    } catch (IOException e) {
                        e.printStackTrace();
                        break;
                    }

                    //send write stream to dji app : pfdArr[1]
                    Message replyMsg = Message.obtain(null, ATAKServiceHandler.MSG_STREAM, 0, 0);
                    Bundle b = new Bundle();
                    b.putParcelable("pfd",pfdArr[1]);
                    replyMsg.setData(b);
                    try{
                        Log.d(TAG,"djiapp <---STREAM---- service");
                        djiApp.send(replyMsg);
                    }catch (RemoteException e){
                        e.printStackTrace();
                        break;
                    }
                    //read stream for myself:
                    djiStream = new ParcelFileDescriptor.AutoCloseInputStream(pfdArr[0]);
                    break;

                case MSG_MESSENGER_ATAKAPP:
                    Log.d(TAG,"received atakapp messenger!");
                    atakApp = msg.replyTo;

                    ParcelFileDescriptor[] pfdArr2;
                    try {
                        pfdArr2 = ParcelFileDescriptor.createPipe();

                    } catch (IOException e) {
                        e.printStackTrace();
                        break;
                    }

                    //send write stream to dji app : pfdArr[1]
                    Message replyMsg2 = Message.obtain(null, ATAKServiceHandler.MSG_STREAM, 0, 0);
                    Bundle b2 = new Bundle();
                    b2.putParcelable("pfd",pfdArr2[0]);
                    replyMsg2.setData(b2);
                    try{
                        Log.d(TAG,"atakapp <---STREAM---- service");
                        atakApp.send(replyMsg2);
                    }catch (RemoteException e){
                        e.printStackTrace();
                        break;
                    }
                    //read stream for myself:
                    atakStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfdArr2[1]);

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
                    if (djiStream!=null && atakStream!=null){
                        new Thread()
                        {
                            public void run() {
                                copyStream(djiStream,atakStream);
                            }
                        }.start();

                    }
                    break;

                default:
                    Log.d(TAG,"received unknown msg");
                    super.handleMessage(msg);
            }
        }
    }


    final Messenger mMessenger = new Messenger(new IncomingHandler());

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG,"onBind");
        Toast.makeText(getApplicationContext(), "binding", Toast.LENGTH_SHORT).show();
        return mMessenger.getBinder();
    }




    public static void copyStream(InputStream input, OutputStream output)
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
            return;
        }

        MainActivity.bridgeOn = false;
    }

}
