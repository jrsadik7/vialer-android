package com.voipgrid.vialer;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.voipgrid.vialer.api.models.PhoneAccount;
import com.voipgrid.vialer.api.models.SystemUser;
import com.voipgrid.vialer.util.JsonStorage;
import com.voipgrid.vialer.util.RemoteLogger;

/**
 * Class used for storing preferences related to SIP.
 */
public class Preferences {

    public static final String PREF_HAS_SIP_ENABLED = "PREF_HAS_SIP_ENABLED";
    public static final String PREF_HAS_SIP_PERMISSION = "PREF_HAS_SIP_PERMISSION";
    public static final String PREF_REMOTE_LOGGING = "PREF_REMOTE_LOGGING";
    public static final String PREF_REMOTE_LOGGING_ID = "PREF_REMOTE_LOGGING_ID";

    public static final boolean DEFAULT_VALUE_HAS_SIP_ENABLED = true;
    public static final boolean DEFAULT_VALUE_HAS_SIP_PERMISSION = false;

    private Context mContext;
    private SharedPreferences mPreferences;

    public Preferences(Context context) {
        mContext = context;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public String getLoggerIdentifier() {
        String identifier = mPreferences.getString(PREF_REMOTE_LOGGING_ID, null);
        if (identifier == null) {
            identifier = RemoteLogger.generateIdentifier();
            setLoggerIdentifier(identifier);
        }
        return identifier;
    }

    private void setLoggerIdentifier(String identifier) {
        mPreferences.edit().putString(PREF_REMOTE_LOGGING_ID, identifier).apply();
    }

    /**
     * Whether remote logging is active or not.
     * @return
     */
    public boolean remoteLoggingIsActive() {
        // TODO SET DEFAULT TO FALSE AFTER BETA.
        return  mPreferences.getBoolean(PREF_REMOTE_LOGGING, true);
    }

    /**
     * Set the state of the remote logging.
     * @param active
     */
    public void setRemoteLogging(boolean active) {
        mPreferences.edit().putBoolean(PREF_REMOTE_LOGGING, active).apply();
    }

    /**
     * Function to check if a user is logged in.
     * @return If a system user is present thus logged in.
     */
    public boolean isLoggedIn() {
        JsonStorage storage = new JsonStorage(mContext);
        return storage.has(SystemUser.class);
    }

    /**
     * Function to check if a phone account is present.
     * @return
     */
    public boolean hasPhoneAccount() {
        return new JsonStorage(mContext).has(PhoneAccount.class);
    }

    /**
     * Function to set the sip permission.
     * @param sipPermission
     */
    public void setSipPermission(boolean sipPermission) {
        mPreferences.edit().putBoolean(PREF_HAS_SIP_PERMISSION, sipPermission).apply();
    }

    /**
     * Function to check for the sip permission.
     * @return
     */
    public boolean hasSipPermission() {
         return  mPreferences.getBoolean(PREF_HAS_SIP_PERMISSION, DEFAULT_VALUE_HAS_SIP_PERMISSION);
    }

    /**
     * Function to set the enabled state of sip.
     * @param sipEnabled
     */
    public void setSipEnabled(boolean sipEnabled) {
        mPreferences.edit().putBoolean(PREF_HAS_SIP_ENABLED, sipEnabled).apply();
    }

    /**
     * Function to check if sip is enabled.
     * @return
     */
    public boolean hasSipEnabled() {
        return  mPreferences.getBoolean(PREF_HAS_SIP_ENABLED, DEFAULT_VALUE_HAS_SIP_ENABLED);

    }

    /**
     * Function that checks if all requirements are met for using sip. (Sip enabled, permission
     * and phone account).
     * @return
     */
    public boolean canUseSip() {
        return hasSipPermission() && hasSipEnabled() && hasPhoneAccount();
    }
}
