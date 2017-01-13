package com.idevicesinc.sweetblue;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import com.idevicesinc.sweetblue.compat.L_Util;
import com.idevicesinc.sweetblue.utils.Utils;
import com.idevicesinc.sweetblue.utils.Utils_String;
import java.util.List;
import static com.idevicesinc.sweetblue.BleManagerState.SCANNING;
import static com.idevicesinc.sweetblue.BleManagerState.SCANNING_PAUSED;
import static com.idevicesinc.sweetblue.BleManagerState.STARTING_SCAN;



final class P_ScanManager
{

    static final int Mode_NULL = -1;
    static final int Mode_BLE = 0;
    static final int Mode_CLASSIC = 1;
    static final int Mode_BLE_POST_LOLLIPOP = 2;


    private final BleManager m_manager;
    private PreLollipopScanCallback m_preLollipopScanCallback;
    private PostLollipopScanCallback m_postLollipopScanCallback;
    private BleScanApi mCurrentApi;
    private volatile PI_BleScanner m_bleScanner;
    private final int m_retryCountMax = 3;

    private int m_mode;


    public P_ScanManager(BleManager mgr)
    {
        m_manager = mgr;
        mCurrentApi = mgr.m_config.scanApi;
        m_preLollipopScanCallback = new PreLollipopScanCallback();
        if(Utils.isLollipop())
        {
            m_postLollipopScanCallback = new PostLollipopScanCallback();
        }
    }

    public final void setBleScanner(PI_BleScanner scanner)
    {
        m_bleScanner = scanner;
    }


    public final boolean startScan(PA_StateTracker.E_Intent intent, double scanTime, boolean m_isPoll)
    {
        switch (m_manager.m_config.scanApi)
        {
            case CLASSIC:
                mCurrentApi = BleScanApi.CLASSIC;
                return tryClassicDiscovery(intent, true);
            case POST_LOLLIPOP:
                if (isBleScanReady())
                {
                    if (Utils.isLollipop())
                    {
                        mCurrentApi = BleScanApi.POST_LOLLIPOP;
                        return startScanPostLollipop(scanTime, m_isPoll);
                    }
                    else
                    {
                        m_manager.getLogger().e("Tried to start post lollipop scan on a device not running lollipop or above! Defaulting to pre-lollipop scan instead.");
                        mCurrentApi = BleScanApi.PRE_LOLLIPOP;
                        return startScanPreLollipop(intent);
                    }
                }
                else
                {
                    m_manager.getLogger().e("Tried to start BLE scan, but scanning is not ready (most likely need to get permissions). Falling back to classic discovery.");
                    mCurrentApi = BleScanApi.CLASSIC;
                    return m_manager.getNativeAdapter().startDiscovery();
                }
            case AUTO:
            case PRE_LOLLIPOP:
                mCurrentApi = BleScanApi.PRE_LOLLIPOP;
                return startScanPreLollipop(intent);
            default:
                return false;
        }
    }

    private boolean startClassicDiscovery()
    {
        return m_bleScanner.startClassicDiscovery();
    }

    private boolean isBleScanReady()
    {
        return m_manager.isLocationEnabledForScanning() && m_manager.isLocationEnabledForScanning_byRuntimePermissions() && m_manager.isLocationEnabledForScanning_byOsServices();
    }

    public final void stopScan()
    {
        stopScan_private(true);
    }

    public final void stopNativeScan(final P_Task_Scan scanTask)
    {
        if( m_mode == Mode_BLE )
        {
            try
            {
                stopScanPreLollipop();
            }
            catch(NullPointerException e)
            {
                //--- DRK > Catching this because of exception thrown one time...only ever seen once, so not very reproducible.
                //			java.lang.NullPointerException
                //			07-02 15:04:48.149: E/AndroidRuntime(24389): 	at android.bluetooth.BluetoothAdapter$GattCallbackWrapper.stopLeScan(BluetoothAdapter.java:1819)
                //			07-02 15:04:48.149: E/AndroidRuntime(24389): 	at android.bluetooth.BluetoothAdapter.stopLeScan(BluetoothAdapter.java:1722)
                m_manager.getLogger().w(e.getStackTrace().toString());

                m_manager.uhOh(BleManager.UhOhListener.UhOh.RANDOM_EXCEPTION);
            }
        }
        else if ( m_mode == Mode_BLE_POST_LOLLIPOP)
        {
            try
            {
                stopScanPostLollipop();
            }
            catch (NullPointerException e)
            {
                m_manager.getLogger().w(e.getStackTrace().toString());

                m_manager.uhOh(BleManager.UhOhListener.UhOh.RANDOM_EXCEPTION);
            }
        }
        else if( m_mode == Mode_CLASSIC )
        {
            //--- DRK > This assert tripped, but not sure what I can do about it. Technically discovery can be cancelled
            //---		by another app or something, so its usefulness as a logic checker is debatable.
//			ASSERT(m_btMngr.getAdapter().isDiscovering(), "Trying to cancel discovery when not natively running.");

            stopClassicDiscovery();
        }

        if( m_manager.m_config.enableCrashResolver )
        {
            m_manager.m_crashResolver.stop();
        }

        m_manager.getNativeStateTracker().remove(BleManagerState.SCANNING, scanTask.getIntent(), BleStatuses.GATT_STATUS_NOT_APPLICABLE);
    }

    final void pauseScan()
    {
        stopScan_private(false);
    }

    final synchronized void postScanResult(final BluetoothDevice device, final int rssi, final byte[] scanRecord)
    {
        m_manager.getPostManager().postToUpdateThread(new Runnable()
        {
            @Override public void run()
            {
                m_manager.getCrashResolver().notifyScannedDevice(device, m_preLollipopScanCallback);

                m_manager.onDiscoveredFromNativeStack(device, rssi, scanRecord);
            }
        });
    }

    public final void stopScan_private(boolean stopping)
    {
        switch (mCurrentApi)
        {
            case CLASSIC:
                stopClassicDiscovery();
                break;
            case POST_LOLLIPOP:
                if (Utils.isLollipop())
                {
                    stopScanPostLollipop();
                }
                else
                {
                    stopScanPreLollipop();
                }
                break;
            case AUTO:
            case PRE_LOLLIPOP:
                stopScanPreLollipop();
        }
        if (stopping)
        {
            m_manager.getStateTracker().update(PA_StateTracker.E_Intent.INTENTIONAL, BleStatuses.GATT_STATUS_NOT_APPLICABLE, SCANNING, false);
        }
        else
        {
            m_manager.getStateTracker().update(PA_StateTracker.E_Intent.INTENTIONAL, BleStatuses.GATT_STATUS_NOT_APPLICABLE, SCANNING, false, SCANNING_PAUSED, true);
        }
    }

    private boolean startScanPreLollipop(PA_StateTracker.E_Intent intent)
    {
        //--- DRK > Not sure how useful this retry loop is. I've definitely seen startLeScan
        //---		fail but then work again at a later time (seconds-minutes later), so
        //---		it's possible that it can recover although I haven't observed it in this loop.
        int retryCount = 0;

        while (retryCount <= m_retryCountMax)
        {
            final boolean success = startLeScan();

            if (success)
            {
                if (retryCount >= 1)
                {
                    //--- DRK > Not really an ASSERT case...rather just really want to know if this can happen
                    //---		so if/when it does I want it to be loud.
                    //---		UPDATE: Yes, this hits...TODO: Now have to determine if this is my fault or Android's.
                    //---		Error message is "09-29 16:37:11.622: E/BluetoothAdapter(16286): LE Scan has already started".
                    //---		Calling stopLeScan below "fixes" the issue.
                    //---		UPDATE: Seems like it mostly happens on quick restarts of the app while developing, so
                    //---		maybe the scan started in the previous app sessions is still lingering in the new session.
//					ASSERT(false, "Started Le scan on attempt number " + retryCount);
                }

                break;
            }

            retryCount++;

            if (retryCount <= m_retryCountMax)
            {
                if (retryCount == 1)
                {
                    m_manager.getLogger().w("Failed first startLeScan() attempt. Calling stopLeScan() then trying again...");

                    //--- DRK > It's been observed that right on app start up startLeScan can fail with a log
                    //---		message saying it's already started...not sure if it's my fault or not but throwing
                    //---		this in as a last ditch effort to "fix" things.
                    //---
                    //---		UPDATE: It's been observed through simple test apps that when restarting an app through eclipse,
                    //---		Android somehow, sometimes, keeps the same actual BleManager instance in memory, so it's not
                    //---		far-fetched to assume that the scan from the previous app run can sometimes still be ongoing.
                    //m_btMngr.getAdapter().stopLeScan(m_listeners.m_scanCallback);
                    stopLeScan();
                }
                else
                {
                    m_manager.getLogger().w("Failed startLeScan() attempt number " + retryCount + ". Trying again...");
                }
            }
        }

        if (retryCount > m_retryCountMax)
        {
            m_manager.getLogger().w("Pre-Lollipop LeScan totally failed to start!");

            tryClassicDiscovery(intent, /*suppressUhOh=*/false);
            return true;
        }
        else
        {
            if (retryCount > 0)
            {
                m_manager.getLogger().w("Started native scan with " + (retryCount + 1) + " attempts.");
            }

            if (m_manager.m_config.enableCrashResolver)
            {
                m_manager.getCrashResolver().start();
            }

            BleManagerState.SCANNING.setScanApi(BleScanApi.PRE_LOLLIPOP);

            m_manager.getStateTracker().update(PA_StateTracker.E_Intent.INTENTIONAL, BleStatuses.GATT_STATUS_NOT_APPLICABLE, BleManagerState.SCANNING, true, SCANNING_PAUSED, false, STARTING_SCAN, false);

            m_mode = Mode_BLE;
            return true;
        }
    }

    private boolean startScanPostLollipop(double scanTime, boolean m_isPoll)
    {
        int nativePowerMode;
        BleScanPower power = m_manager.m_config.scanPower;
        if (power == BleScanPower.AUTO)
        {
            if (m_manager.isForegrounded())
            {
                if (m_isPoll || scanTime == Double.POSITIVE_INFINITY)
                {
                    power = BleScanPower.MEDIUM_POWER;
                    nativePowerMode = BleScanPower.MEDIUM_POWER.getNativeMode();
                }
                else
                {
                    power = BleScanPower.HIGH_POWER;
                    nativePowerMode = BleScanPower.HIGH_POWER.getNativeMode();
                }
            }
            else
            {
                power = BleScanPower.LOW_POWER;
                nativePowerMode = BleScanPower.LOW_POWER.getNativeMode();
            }
        }
        else
        {
            if (power == BleScanPower.VERY_LOW_POWER)
            {
                if (!Utils.isMarshmallow())
                {
                    m_manager.getLogger().e("BleScanPower set to VERY_LOW, but device is not running Marshmallow. Defaulting to LOW instead.");
                    power = BleScanPower.LOW_POWER;
                }
            }
            nativePowerMode = power.getNativeMode();
        }
        if (Utils.isMarshmallow())
        {
            startMScan(nativePowerMode);
        }
        else
        {
            startLScan(nativePowerMode);
        }
        BleManagerState.SCANNING.setScanApi(BleScanApi.POST_LOLLIPOP);
        BleManagerState.SCANNING.setPower(power);
        m_manager.getStateTracker().update(PA_StateTracker.E_Intent.INTENTIONAL, BleStatuses.GATT_STATUS_NOT_APPLICABLE, BleManagerState.SCANNING, true, SCANNING_PAUSED, false, STARTING_SCAN, false);
        m_mode = Mode_BLE_POST_LOLLIPOP;
        return true;
    }

    private boolean tryClassicDiscovery(final PA_StateTracker.E_Intent intent, final boolean suppressUhOh)
    {
        if (m_manager.m_config.revertToClassicDiscoveryIfNeeded)
        {
            if (false == startClassicDiscovery())
            {
                m_manager.getLogger().w("Classic discovery failed to start!");

                fail();

                m_manager.uhOh(BleManager.UhOhListener.UhOh.CLASSIC_DISCOVERY_FAILED);

                return false;
            }
            else
            {
                if (false == suppressUhOh)
                {
                    m_manager.uhOh(BleManager.UhOhListener.UhOh.START_BLE_SCAN_FAILED__USING_CLASSIC);
                }

                BleManagerState.SCANNING.setScanApi(BleScanApi.CLASSIC);
                m_manager.getStateTracker().update(PA_StateTracker.E_Intent.INTENTIONAL, BleStatuses.GATT_STATUS_NOT_APPLICABLE, BleManagerState.SCANNING, true, SCANNING_PAUSED, false, STARTING_SCAN, false);
                m_mode = Mode_CLASSIC;

                return true;
            }
        }
        else
        {
            fail();

            m_manager.uhOh(BleManager.UhOhListener.UhOh.START_BLE_SCAN_FAILED);

            return false;
        }
    }

    private boolean startLeScan()
    {
        return m_bleScanner.startLeScan(m_preLollipopScanCallback);
    }

    private void startLScan(int mode)
    {
        m_bleScanner.startLScan(mode, m_manager.m_config.scanReportDelay, m_postLollipopScanCallback);
    }

    private void startMScan(int mode)
    {
        m_bleScanner.startMScan(mode, m_manager.m_config.scanReportDelay, m_postLollipopScanCallback);
    }

    private void fail()
    {
        m_manager.getTaskQueue().fail(P_Task_Scan.class, m_manager);
    }

    private void stopScanPreLollipop()
    {
        try
        {
            stopLeScan();
        } catch (Exception e)
        {
            m_manager.getLogger().e("Got an exception (" + e.getClass().getSimpleName() + ") with a message of " + e.getMessage() + " when trying to stop a pre-lollipop scan!");
        }
    }

    private void stopLeScan()
    {
        m_bleScanner.stopLeScan(m_preLollipopScanCallback);
    }

    private void stopScanPostLollipop()
    {
        L_Util.stopNativeScan(m_manager);
    }

    private void stopClassicDiscovery()
    {
        m_bleScanner.stopClassicDiscovery();
    }




    private class PreLollipopScanCallback implements BluetoothAdapter.LeScanCallback
    {

        @Override public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord)
        {
            postScanResult(device, rssi, scanRecord);
        }
    }

    private class PostLollipopScanCallback implements L_Util.ScanCallback
    {

        public static final int SCAN_FAILED_ALREADY_STARTED = 1;

        @Override public void onScanResult(int callbackType, L_Util.ScanResult result)
        {
            postScanResult(result.getDevice(), result.getRssi(), result.getRecord());
        }

        @Override public void onBatchScanResults(List<L_Util.ScanResult> results)
        {
            m_manager.getLogger().d("Got batched scan results.");
            for (L_Util.ScanResult res : results)
            {
                postScanResult(res.getDevice(), res.getRssi(), res.getRecord());
            }
        }

        @Override public void onScanFailed(int errorCode)
        {
            m_manager.getLogger().e(Utils_String.concatStrings("Post lollipop scan failed with error code ", String.valueOf(errorCode)));
            if (errorCode != SCAN_FAILED_ALREADY_STARTED)
            {
                fail();
            }
            else
            {
                tryClassicDiscovery(PA_StateTracker.E_Intent.INTENTIONAL, /*suppressUhOh=*/false);

                m_mode = Mode_CLASSIC;
            }
        }
    }

    private P_GattLayer gatt()
    {
        return m_manager.m_config.defaultGattLayer;
    }

    final boolean isPreLollipopScan()
    {
        return m_mode == Mode_BLE;
    }

    final boolean isPostLollipopScan()
    {
        return m_mode == Mode_BLE_POST_LOLLIPOP;
    }

    final boolean isClassicScan()
    {
        return m_mode == Mode_CLASSIC;
    }

}