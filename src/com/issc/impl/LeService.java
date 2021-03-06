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

package com.issc.impl;

import com.issc.Bluebit;
import com.issc.gatt.Gatt;
import com.issc.gatt.GattAdapter;
import com.issc.gatt.Gatt.Listener;
import com.issc.gatt.GattCharacteristic;
import com.issc.gatt.GattDescriptor;
import com.issc.gatt.GattService;
import com.issc.impl.aosp.AospGattAdapter;
import com.issc.impl.test.FakeGattAdapter;
import com.issc.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/**
 * This class is a wrapper that handles Gatt related operations.
 *
 * Upper layer just use this service so that they do not have to
 * deal with Gatt by themself.
 */
public class LeService extends Service {

    private IBinder mBinder;

    private boolean mGattReady = false;
    private GattAdapter mGattAdapter = null;
    private Gatt mGatt = null;
    private Gatt.Listener mCallback;

    private List<Listener> mListeners;
    private Object mLock;

    @Override
    public void onCreate() {
        super.onCreate();
        mLock = new Object();
        mCallback   = new TheCallback();
        mListeners  = new ArrayList<Listener>();

        mBinder = new LocalBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseGatt();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean fake = intent.getBooleanExtra(Bluebit.USE_FAKE, false);
        if (fake) {
            Log.d("Use FakeGattAdapter for testing");
            mGattAdapter = new FakeGattAdapter(this, mCallback);
        } else {
            Log.d("Use AospGattAdapter");
            mGattAdapter = new AospGattAdapter(this, mCallback);
        }

        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void addListener(Listener l) {
        synchronized(mLock) {
            mListeners.add(l);
        }
    }

    public boolean rmListener(Listener l) {
        synchronized(mLock) {
            return mListeners.remove(l);
        }
    }

    private void releaseGatt() {
        synchronized(mLock) {
            mGattReady = false;
            if (mGatt != null) {
                mGatt.disconnect();
                mGatt.close();
            }
            mGatt = null;
        }
    }

    /**
     * Invoke this method to initialize Gatt before using Gatt.
     *
     * FIXME: right now we support connect to just 1 device.
     */
    public Gatt connectGatt(Context ctx, boolean auto, BluetoothDevice dev) {
        if (mGatt != null) {
            closeGatt(dev);
        }
        mGatt = mGattAdapter.connectGatt(ctx, auto, mCallback, dev);
        return mGatt;
    }

    public void closeGatt(BluetoothDevice device) {
        /* In general, there should be an activity that close an Gatt when
         * onDestroy. However, if user press-back-key too fast, this Service will
         * release Gatt before destroying the activity, therefore Gatt might be null
         * when activity do closing Gatt.*/
        if (mGatt != null) {
            mGatt.close();
            mGatt = null;
        }
    }

    public boolean startScan(GattAdapter.LeScanCallback clbk) {
        return mGattAdapter.startLeScan(clbk);
    }

    public void stopScan(GattAdapter.LeScanCallback clbk) {
        mGattAdapter.stopLeScan(clbk);
    }

    public boolean connect(BluetoothDevice device, boolean auto) {
        return mGatt.connect();
    }

    public void disconnect(BluetoothDevice device) {
        /* In general, there should be an activity that disconnect from an Gatt when
         * onDestroy. However, if user press-back-key too fast, this Service will
         * release Gatt before destroying the activity, therefore Gatt might be null
         * when activity do disconnecting.*/
        if (mGatt != null) {
            mGatt.disconnect();
        }
    }

    public List<BluetoothDevice> getConnectedDevices() {
        return mGattAdapter.getConnectedDevices();
    }

    public boolean discoverServices(BluetoothDevice device) {
        return mGatt.discoverServices();
    }

    public int getConnectionState(BluetoothDevice device) {
        return mGattAdapter.getConnectionState(device);
    }

    public GattService getService(BluetoothDevice device, UUID uuid) {
        return mGatt.getService(uuid);
    }

    public List<GattService> getServices(BluetoothDevice device) {
        return mGatt.getServices();
    }

    public boolean readCharacteristic(GattCharacteristic chr) {
        return mGatt.readCharacteristic(chr);
    }

    public boolean writeCharacteristic(GattCharacteristic chr) {
        return mGatt.writeCharacteristic(chr);
    }

    public boolean readDescriptor(GattDescriptor dsc) {
        return mGatt.readDescriptor(dsc);
    }

    public boolean writeDescriptor(GattDescriptor dsc) {
        return mGatt.writeDescriptor(dsc);
    }

    public boolean setCharacteristicNotification(GattCharacteristic chr, boolean enable) {
        return mGatt.setCharacteristicNotification(chr, enable);
    }

    /* This is the only one callback that register to GATT. It dispatch each
     * of returen value to listeners. */
    class TheCallback implements Gatt.Listener {
        @Override
        public void onCharacteristicChanged(Gatt gatt, GattCharacteristic chrc) {
            synchronized(mListeners) {
                Iterator<Listener> it = mListeners.iterator();
                while(it.hasNext()) {
                    it.next().onCharacteristicChanged(gatt, chrc);
                }
            }
        }

        @Override
        public void onCharacteristicRead(Gatt gatt, GattCharacteristic chrc, int status) {
            synchronized(mListeners) {
                Iterator<Listener> it = mListeners.iterator();
                while(it.hasNext()) {
                    it.next().onCharacteristicRead(gatt, chrc, status);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(Gatt gatt, GattCharacteristic chrc, int status) {
            synchronized(mListeners) {
                Iterator<Listener> it = mListeners.iterator();
                while(it.hasNext()) {
                    it.next().onCharacteristicWrite(gatt, chrc, status);
                }
            }
        }

        @Override
        public void onConnectionStateChange(Gatt gatt, int status, int newState) {
            synchronized(mListeners) {
                Iterator<Listener> it = mListeners.iterator();
                while(it.hasNext()) {
                    it.next().onConnectionStateChange(gatt, status, newState);
                }
            }
        }

        @Override
        public void onDescriptorRead(Gatt gatt, GattDescriptor descriptor, int status) {
            synchronized(mListeners) {
                Iterator<Listener> it = mListeners.iterator();
                while(it.hasNext()) {
                    it.next().onDescriptorRead(gatt, descriptor, status);
                }
            }
        }

        @Override
        public void onDescriptorWrite(Gatt gatt, GattDescriptor descriptor, int status) {
            synchronized(mListeners) {
                Iterator<Listener> it = mListeners.iterator();
                while(it.hasNext()) {
                    it.next().onDescriptorWrite(gatt, descriptor, status);
                }
            }
        }

        @Override
        public void onReadRemoteRssi(Gatt gatt,  int rssi, int status) {
            synchronized(mListeners) {
                Iterator<Listener> it = mListeners.iterator();
                while(it.hasNext()) {
                    it.next().onReadRemoteRssi(gatt, rssi, status);
                }
            }
        }

        @Override
        public void onServicesDiscovered(Gatt gatt,  int status) {
            synchronized(mListeners) {
                Iterator<Listener> it = mListeners.iterator();
                while(it.hasNext()) {
                    it.next().onServicesDiscovered(gatt, status);
                }
            }
        }
    }

    public class LocalBinder extends Binder {
        public LeService getService() {
            return LeService.this;
        }
    }
}
