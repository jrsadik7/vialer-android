package com.voipgrid.vialer.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;

import com.voipgrid.vialer.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to check connectivity of the device.
 */
public class ConnectivityHelper {
    private static final long TYPE_NO_CONNECTION = -1;
    private static final long TYPE_SLOW = 0;
    private static final long TYPE_WIFI = 1;
    private static final long TYPE_LTE = 2;

    public final String CONNECTION_WIFI = "Wifi";
    public final String CONNECTION_4G = "4G";
    public final String CONNECTION_UNKNOWN = "unknown";

    private final ConnectivityManager mConnectivityManager;
    private final TelephonyManager mTelephonyManager;

    private static final List<Long> sFastDataTypes = new ArrayList<>();

    static {
        sFastDataTypes.add(TYPE_WIFI);
        sFastDataTypes.add(TYPE_LTE);
    }

    /**
     * Constructor.
     * @param connectivityManager
     * @param telephonyManager
     */
    public ConnectivityHelper(ConnectivityManager connectivityManager,
                              TelephonyManager telephonyManager) {
        mConnectivityManager = connectivityManager;
        mTelephonyManager = telephonyManager;
    }

    /**
     * Check the device current connectivity state based on the active network.
     * @return
     */
    public boolean hasNetworkConnection() {
        NetworkInfo activeNetwork = mConnectivityManager.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    /**
     * Get the current connection type.
     * @return Long representation of the connection type.
     */
    public long getConnectionType() {
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        if (info == null || !info.isConnected()) {
            return TYPE_NO_CONNECTION;
        }

        // We need to check 2 methods for the type because they both can give a different
        // value for the same information we are checking.
        // Get network type from ConnectivityManager.
        int networkTypeConnection = info.getSubtype();
        // Get network type from TelephonyManager.
        int networkTypeTelephony = mTelephonyManager.getNetworkType();

        if (info.getType() == ConnectivityManager.TYPE_WIFI) {
            return TYPE_WIFI;
        } else if (networkTypeConnection == TelephonyManager.NETWORK_TYPE_LTE ||
                networkTypeTelephony == TelephonyManager.NETWORK_TYPE_LTE) {
            return TYPE_LTE;
        } else {
            return TYPE_SLOW;
        }
    }

    /**
     * Get the connection type as string. This is mainly used for the GA tracking.
     *
     * @return String representation of the connection type.
     */
    public String getConnectionTypeString() {
        long connectionType = getConnectionType();
        String connectionString = CONNECTION_UNKNOWN;

        if (connectionType == TYPE_WIFI) {
            return CONNECTION_WIFI;
        } else if (connectionType == TYPE_LTE) {
            return CONNECTION_4G;
        }

        return connectionString;
    }

    /**
     * Check if the device is connected via wifi or LTE connection.
     * @return
     */
    public boolean hasFastData() {
        return sFastDataTypes.contains(getConnectionType());
    }

    public static ConnectivityHelper get(Context context) {
        ConnectivityManager c = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        TelephonyManager t = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        return new ConnectivityHelper(c, t);
    }
}
