// vim: et sw=4 sts=4 tabstop=4
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.issc.ui;

import com.issc.Bluebit;
import com.issc.gatt.Gatt;
import com.issc.gatt.GattCharacteristic;
import com.issc.gatt.GattDescriptor;
import com.issc.gatt.GattService;
import com.issc.impl.LeService;
import com.issc.impl.GattTransaction;
import com.issc.R;
import com.issc.util.Log;
import com.issc.util.Util;
import com.issc.util.TransactionQueue;

import java.io.FileOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.UUID;
import java.util.List;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.ToggleButton;

public class ActivityTransparent extends Activity implements
    TransactionQueue.Consumer<GattTransaction> {

    private LeService mService;
    private BluetoothDevice mDevice;
    private Gatt.Listener mListener;
    private SrvConnection mConn;

    private ProgressDialog mConnectionDialog;
    private ProgressDialog mTimerDialog;
    protected ViewHandler  mViewHandler;

    private OutputStream mStream;
    private TransactionQueue mQueue;

    private final static int PAYLOAD_MAX = 20; // 90 bytes might be max

    private final static int CONNECTION_DIALOG = 1;
    private final static int TIMER_DIALOG      = 2;
    private final static int CHOOSE_FILE = 0x101;
    private final static int COMPARE_FILE = 0x102;
    private final static int MENU_CLEAR  = 0x501;

    private final static String INFO_CONTENT = "the_information_body";
    private final static String RCV_ENABLED = "could_receive_data_if_enabled";

    private final static int SHOW_CONNECTION_DIALOG     = 0x1000;
    private final static int DISMISS_CONNECTION_DIALOG  = 0x1001;
    private final static int CONSUME_TRANSACTION        = 0x1002;
    private final static int DISMISS_TIMER_DIALOG       = 0x1003;
    private final static int APPEND_MESSAGE             = 0x1004;
    private final static int RCV_STATE                  = 0x1005;


    private TabHost mTabHost;
    private TextView mMsg;
    private EditText mInput;
    private Button   mBtnSend;
    private ToggleButton mToggleEcho;
    private ToggleButton mToggleResponse;
    private CompoundButton mRcvIndicator;

    private Spinner mSpinnerDelta;
    private Spinner mSpinnerSize;
    private Spinner mSpinnerRepeat;

    private int[] mValueDelta;
    private int[] mValueSize;
    private int[] mValueRepeat;

    private GattCharacteristic mTransTx;
    private GattCharacteristic mTransRx;

    private int mSuccess = 0;
    private int mFail    = 0;
    private Calendar mStartTime;

    private final static int MAX_LINES = 50;
    private ArrayList<CharSequence> mLogBuf;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trans);

        mQueue = new TransactionQueue(this);

        mMsg     = (TextView)findViewById(R.id.trans_msg);
        mInput   = (EditText)findViewById(R.id.trans_input);
        mBtnSend = (Button)findViewById(R.id.trans_btn_send);
        mToggleEcho     = (ToggleButton)findViewById(R.id.echo_toggle);
        mToggleResponse = (ToggleButton)findViewById(R.id.trans_type);
        mRcvIndicator   = (CompoundButton)findViewById(R.id.rcv_indicator);

        mViewHandler = new ViewHandler();

        mTabHost = (TabHost) findViewById(R.id.tabhost);
        mTabHost.setup();
        addTab(mTabHost, "Tab1", "Raw", R.id.tab_raw);
        addTab(mTabHost, "Tab2", "Timer", R.id.tab_timer);
        addTab(mTabHost, "Tab3", "Receive", R.id.tab_rcv);

        mMsg.setMovementMethod(ScrollingMovementMethod.getInstance());
        registerForContextMenu(mMsg);

        mDevice = getIntent().getParcelableExtra(Bluebit.CHOSEN_DEVICE);

        mListener = new GattListener();
        initSpinners();

        mLogBuf = new ArrayList<CharSequence>();

        /* Transparent is not a leaf activity. connect service in onCreate*/
        mConn = new SrvConnection();
        bindService(new Intent(this, LeService.class), mConn, 0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mQueue.clear();
        closeStream();
        mViewHandler.removeCallbacksAndMessages(null);

        /* Transparent is not a leaf activity. disconnect/unregister-listener in onDestroy*/
        mService.rmListener(mListener);
        mService = null;
        unbindService(mConn);
    }

    private void initSpinners() {
        Resources res = getResources();

        mSpinnerDelta  = (Spinner)findViewById(R.id.timer_delta);
        mSpinnerSize   = (Spinner)findViewById(R.id.timer_size);
        mSpinnerRepeat = (Spinner)findViewById(R.id.timer_repeat);

        mValueDelta  = res.getIntArray(R.array.delta_value);
        mValueSize   = res.getIntArray(R.array.size_value);
        mValueRepeat = res.getIntArray(R.array.repeat_value);

        initSpinner(R.array.delta_text, mSpinnerDelta);
        initSpinner(R.array.size_text, mSpinnerSize);
        initSpinner(R.array.repeat_text, mSpinnerRepeat);

        mSpinnerDelta.setSelection(3);  // supposed to select 1000ms
        mSpinnerSize.setSelection(19);  // supposed to select 20bytes
        mSpinnerRepeat.setSelection(0); // supposed to select Unlimited
    }

    private void initSpinner(int textArrayId, Spinner spinner) {
        ArrayAdapter<CharSequence> adapter;
        adapter = ArrayAdapter.createFromResource(
                this, textArrayId, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void addTab(TabHost host, String tag, CharSequence text, int viewResource) {
        View indicator = getLayoutInflater().inflate(R.layout.tab_indicator, null);
        TextView tv = (TextView)indicator.findViewById(R.id.indicator_text);
        tv.setText(text);

        TabHost.TabSpec spec = host.newTabSpec(tag);
        spec.setIndicator(indicator);
        spec.setContent(viewResource);
        host.addTab(spec);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu,
                                        View v,
                                        ContextMenuInfo info) {
        super.onCreateContextMenu(menu, v, info);
        if (v == mMsg) {
            menu.setHeaderTitle("Message Area");
            menu.add(0, MENU_CLEAR, Menu.NONE, "Clear");
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == MENU_CLEAR) {
            mLogBuf.clear();
            mMsg.setText("");
            mMsg.scrollTo(0, 0);
        }
        return true;
    }

    public void onClickSend(View v) {
        CharSequence cs = mInput.getText();
        msgShow("send", cs);
        write(cs);
    }

    public void onClickStartTimer(View v) {
        showDialog(TIMER_DIALOG);
        startTimer();
    }

    public void onClickCompare(View v) {
        Intent i = new Intent(this, ActivityFileChooser.class);
        i.putExtra(Bluebit.CHOOSE_PATH, Bluebit.DATA_DIR);
        startActivityForResult(i, COMPARE_FILE);
    }

    public void onClickChoose(View v) {
        Intent i = new Intent(this, ActivityFileChooser.class);
        i.putExtra(Bluebit.CHOOSE_PATH, Bluebit.DATA_DIR);
        startActivityForResult(i, CHOOSE_FILE);
    }

    public void onClickType(View v) {
        onSetType(mToggleResponse.isChecked());
    }

    public void onClickRcv(View v) {
        toggleReceive();
    }

    private void onSetType(boolean withResponse) {
        Log.d("set write with response:" + withResponse);
    }

    private void toggleReceive() {

        if (mRcvIndicator.isChecked()) {
            disableNotification();
            closeStream();
        } else {
            enableNotification();
            openStream(Bluebit.DEFAULT_LOG);
        }
    }

    private void enableNotification() {
        boolean set = mService.setCharacteristicNotification(mTransTx, true);
        Log.d("set notification:" + set);
        GattDescriptor dsc = mTransTx.getDescriptor(Bluebit.DES_CLIENT_CHR_CONFIG);
        dsc.setValue(dsc.getConstantBytes(GattDescriptor.ENABLE_NOTIFICATION_VALUE));
        boolean success = mService.writeDescriptor(dsc);
        Log.d("writing enable descriptor:" + success);
    }

    private void disableNotification() {
        boolean set = mService.setCharacteristicNotification(mTransTx, false);
        Log.d("set notification:" + set);
        GattDescriptor dsc = mTransTx.getDescriptor(Bluebit.DES_CLIENT_CHR_CONFIG);
        dsc.setValue(dsc.getConstantBytes(GattDescriptor.DISABLE_NOTIFICATION_VALUE));
        boolean success = mService.writeDescriptor(dsc);
        Log.d("writing disable descriptor:" + success);
    }

    private void openStream(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                Log.w("Target file does not exist, create: " + path);
                File parent = file.getParentFile();
                Log.w("make dirs:" + parent.getPath());
                parent.mkdirs();
                file.createNewFile();
            }

            mStream = new FileOutputStream(file, false);
        } catch (IOException e) {
            msgShow("open stream fail", e.toString());
            e.printStackTrace();
        }
    }

    private void closeStream() {
        try {
            if (mStream != null) {
                mStream.flush();
                mStream.close();
            }
        } catch (IOException e) {
            msgShow("close stream fail", e.toString());
            e.printStackTrace();
        }

        mStream = null;
    }

    private void writeToStream(byte[] data) {
        if (mStream != null) {
            try {
                mStream.write(data, 0, data.length);
                mStream.flush();
            } catch (IOException e) {
                msgShow("write fail", e.toString());
                e.printStackTrace();
            }
        }
    }

    private void compareFile(String pathA, String pathB) {
        try {
            String md5A = Util.getMD5FromBytes(Util.readBytesFromFile(pathA));
            String md5B = Util.getMD5FromBytes(Util.readBytesFromFile(pathB));
            msgShow(pathA, md5A);
            msgShow(pathB, md5B);
            if (md5A.equals(md5B)) {
                msgShow("compare", "Match");
            } else {
                msgShow("compare", "Not Match");
            }
        } catch (IOException e) {
            msgShow("comapre fail", e.toString());
            e.printStackTrace();
        }
    }

    /**
     * Received data from remote when enabling Echo.
     *
     * Display the data and transfer back to device.
     */
    private void onReceived(byte[] data) {
        StringBuffer sb = new StringBuffer();
        if (data == null) {
            sb.append("Received empty data");
        } else {
            String recv = new String(data);
            sb.append("recv:");
            sb.append(recv);
            writeToStream(data);

            if (mToggleEcho.isChecked()) {
                write(data);
                sb.append("echo:");
                sb.append(recv);
            }
        }
        Bundle msg = new Bundle();
        msg.putCharSequence(INFO_CONTENT, sb);

        updateView(APPEND_MESSAGE, msg);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == CHOOSE_FILE) {
            if (result == Activity.RESULT_OK) {
                Uri uri = data.getData();
                String filePath = uri.getPath();
                Log.d("chosen file:" + filePath);
                try {
                    mStartTime = Calendar.getInstance();
                    write(Util.readBytesFromFile(filePath));
                    msgShow("send", filePath);
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.d("IO Exception");
                }
            }
        } else if (request == COMPARE_FILE) {
            if (data != null) {
                Uri uri = data.getData();
                String filePath = uri.getPath();
                compareFile(filePath, Bluebit.DEFAULT_LOG);
            }
        }
    }

    private void msgShow(CharSequence prefix, CharSequence cs) {
        StringBuffer sb = new StringBuffer();
        sb.append(prefix);
        sb.append(": ");
        sb.append(cs);
        Log.d(sb.toString());
        Bundle msg = new Bundle();
        msg.putCharSequence(INFO_CONTENT, sb.toString());
        updateView(APPEND_MESSAGE, msg);
    }

    /**
     * Write string to remote device.
     */
    private void write(CharSequence cs) {
        byte[] bytes = cs.toString().getBytes();
        write(bytes);
    }

    /**
     * Write data to remote device.
     */
    private void write(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.allocate(bytes.length);
        buf.put(bytes);
        buf.position(0);
        while(buf.remaining() != 0) {
            int size = (buf.remaining() > PAYLOAD_MAX) ? PAYLOAD_MAX: buf.remaining();
            byte[] dst = new byte[size];
            buf.get(dst, 0, size);
            GattTransaction t = new GattTransaction(mTransRx, dst);
            mQueue.add(t);
        }
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        /*FIXME: this function is deprecated. */
        if (id == CONNECTION_DIALOG) {
            mConnectionDialog = new ProgressDialog(this);
            mConnectionDialog.setMessage(this.getString(R.string.connecting));
            mConnectionDialog.setCancelable(true);
            return mConnectionDialog;
        } else if (id == TIMER_DIALOG) {
            mTimerDialog = new ProgressDialog(this);
            mTimerDialog.setMessage("Timer is running");
            mTimerDialog.setOnCancelListener(new Dialog.OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    Log.d("some one canceled me");
                    stopTimer();
                }
            });
            return mTimerDialog;
        }
        return null;
    }

    private void onTimerSend(int count, int size) {
        /* max is 20 */
        String out = String.format("%020d", count);
        if (out.length() > size) {
            // if too long
            out = out.substring(out.length() - size);
        }
        msgShow("send", out);
        write(out);
    }

    private boolean mRunning;

    private void startTimer() {

        final int delta  = mValueDelta[mSpinnerDelta.getSelectedItemPosition()];
        final int size   = mValueSize[mSpinnerSize.getSelectedItemPosition()];
        final int repeat = mValueRepeat[mSpinnerRepeat.getSelectedItemPosition()];
        mRunning = true;
        Thread runner = new Thread() {
            public void run() {
                int counter = 0;
                try {
                    while(mRunning) {
                        if (repeat != 0 && repeat == counter) {
                            stopTimer();
                        } else {
                            onTimerSend(counter, size);
                            sleep(delta);
                            counter++;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                updateView(DISMISS_TIMER_DIALOG, null);
            }
        };
        runner.start();
    }

    private void stopTimer() {
        mRunning = false;
    }

    /**
     * Add message to UI.
     */
    private void appendMsg(CharSequence msg) {
        StringBuffer sb = new StringBuffer();
        sb.append(msg);
        sb.append("\n");
        mLogBuf.add(sb);
        // we don't want to display too many lines
        if (mLogBuf.size() > MAX_LINES) {
            mLogBuf.remove(0);
        }

        StringBuffer text = new StringBuffer();
        for (int i = 0; i < mLogBuf.size(); i++) {
            text.append(mLogBuf.get(i));
        }
        mMsg.setText(text);
    }

    private void onConnected() {
        List<GattService> list = mService.getServices(mDevice);
        if ((list == null) || (list.size() == 0)) {
            Log.d("no services, do discovery");
            mService.discoverServices(mDevice);
        } else {
            onDiscovered();
        }
    }

    private void onDisconnected() {
        Log.d("transparent activity disconnected, closing");
        stopTimer();
        mStartTime = null;
        mQueue.clear();
        this.setResult(Bluebit.RESULT_REMOTE_DISCONNECT);
        this.finish();
    }

    private void onDiscovered() {
        updateView(DISMISS_CONNECTION_DIALOG, null);

        GattService proprietary = mService.getService(mDevice, Bluebit.SERVICE_ISSC_PROPRIETARY);
        mTransTx = proprietary.getCharacteristic(Bluebit.CHR_ISSC_TRANS_TX);
        mTransRx = proprietary.getCharacteristic(Bluebit.CHR_ISSC_TRANS_RX);
        Log.d(String.format("found Tx:%b, Rx:%b", mTransTx != null, mTransRx != null));
    }

    @Override
    public void onTransact(GattTransaction t) {
        t.chr.setValue(t.value);
        if (t.isWrite) {
            int type = mToggleResponse.isChecked() ?
                GattCharacteristic.WRITE_TYPE_DEFAULT:
                GattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            t.chr.setWriteType(type);
            mService.writeCharacteristic(t.chr);
        } else {
            mService.readCharacteristic(t.chr);
        }
    }

    public void updateView(int tag, Bundle info) {
        if (info == null) {
            info = new Bundle();
        }

        // remove previous log since the latest log
        // already contains needed information.
        mViewHandler.removeMessages(tag);

        Message msg = mViewHandler.obtainMessage(tag);
        msg.what = tag;
        msg.setData(info);
        mViewHandler.sendMessage(msg);
    }

    class ViewHandler extends Handler {
        public void handleMessage(Message msg) {
            Bundle bundle = msg.getData();
            if (bundle == null) {
                Log.d("ViewHandler handled a message without information");
                return;
            }

            int tag = msg.what;
            if (tag == SHOW_CONNECTION_DIALOG) {
                showDialog(CONNECTION_DIALOG);
            } else if (tag == DISMISS_CONNECTION_DIALOG) {
                if (mConnectionDialog != null && mConnectionDialog.isShowing()) {
                    dismissDialog(CONNECTION_DIALOG);
                }
            } else if (tag == DISMISS_TIMER_DIALOG) {
                if (mTimerDialog != null && mTimerDialog.isShowing()) {
                    dismissDialog(TIMER_DIALOG);
                }
            } else if (tag == CONSUME_TRANSACTION) {
                // mQueue itself will consume next transaction
                //mQueue.process();
            } else if (tag == APPEND_MESSAGE) {
                CharSequence content = bundle.getCharSequence(INFO_CONTENT);
                if (content != null) {
                    appendMsg(content);

                    /*fot automaticaly scrolling to end*/
                    final int amount = mMsg.getLayout().getLineTop(mMsg.getLineCount())
                        - mMsg.getHeight();
                    if (amount > 0) {
                        mMsg.scrollTo(0, amount);
                    }
                }
            } else if (tag == RCV_STATE) {
                mRcvIndicator.setChecked(bundle.getBoolean(RCV_ENABLED, false));
            }
        }
    }

    class GattListener extends Gatt.ListenerHelper {

        GattListener() {
            super("ActivityTransparent");
        }

        @Override
        public void onConnectionStateChange(Gatt gatt, int status, int newState) {
            if (!mDevice.getAddress().equals(gatt.getDevice().getAddress())) {
                // not the device I care about
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnected();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onDisconnected();
            }
        }

        @Override
        public void onServicesDiscovered(Gatt gatt, int status) {
            onDiscovered();
        }

        @Override
        public void onCharacteristicRead(Gatt gatt, GattCharacteristic charac, int status) {
            Log.d("read char, uuid=" + charac.getUuid().toString());
            byte[] value = charac.getValue();
            Log.d("get value, byte length:" + value.length);
            for (int i = 0; i < value.length; i++) {
                Log.d("[" + i + "]" + Byte.toString(value[i]));
            }
            mQueue.onConsumed();
        }

        @Override
        public void onCharacteristicChanged(Gatt gatt, GattCharacteristic chrc) {
            Log.d("on chr changed" );
            if (chrc.getUuid().equals(Bluebit.CHR_ISSC_TRANS_TX)) {
                onReceived(chrc.getValue());
            }
        }

        @Override
        public void onCharacteristicWrite(Gatt gatt, GattCharacteristic charac, int status) {
            if (status == Gatt.GATT_SUCCESS) {
                mSuccess +=charac.getValue().length;
            } else {
                mFail += charac.getValue().length;
            }

            String s = String.format("%d bytes, success= %d, fail= %d, pending= %d",
                    charac.getValue().length,
                    mSuccess,
                    mFail,
                    mQueue.size());
            msgShow("wrote", s);
            mQueue.onConsumed();
            if (mQueue.size() == 0 && mStartTime != null) {
                long elapse =  Calendar.getInstance().getTimeInMillis()
                    - mStartTime.getTimeInMillis();
                msgShow("time", "spent " + (elapse / 1000) + " seconds");
                mStartTime = null;
            }
            updateView(CONSUME_TRANSACTION, null);
        }

        @Override
        public void onDescriptorWrite(Gatt gatt, GattDescriptor dsc, int status) {
            if (status == Gatt.GATT_SUCCESS) {
                byte[] value = dsc.getValue();
                if (Arrays.equals(value, dsc.getConstantBytes(GattDescriptor.ENABLE_NOTIFICATION_VALUE))) {
                    Bundle state = new Bundle();
                    state.putBoolean(RCV_ENABLED, true);
                    updateView(RCV_STATE, state);
                } else if (Arrays.equals(value, dsc.getConstantBytes(GattDescriptor.DISABLE_NOTIFICATION_VALUE))) {
                    Bundle state = new Bundle();
                    state.putBoolean(RCV_ENABLED, false);
                    updateView(RCV_STATE, state);
                }
            }
        }
    }

    class SrvConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mService = ((LeService.LocalBinder)service).getService();
            mService.addListener(mListener);

            int conn = mService.getConnectionState(mDevice);
            if (conn == BluetoothProfile.STATE_DISCONNECTED) {
                onDisconnected();
            } else {
                Log.d("already connected");
                onConnected();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.e("Gatt Service disconnected");
        }
    }
}
