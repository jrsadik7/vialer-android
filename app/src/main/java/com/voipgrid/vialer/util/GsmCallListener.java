package com.voipgrid.vialer.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

import java.lang.reflect.Method;

import com.android.internal.telephony.ITelephony;
import com.voipgrid.vialer.sip.CallInteraction;

import org.pjsip.pjsua2.Call;

/**
 * Calls that handles incoming GSM calls during a sip call.
 *
 * NOTE: We use a hack to end a incoming GSM call. Removing the hack requires 3 steps:
 *
 * 1: Delete com.android.internal.telephony.ITelephony package and files in the project.
 * 2: Remove the `if (!couldEndGsmCall)` check from onRecieve.
 * 3: Remove the couldEndGsmCall function from this class.
 */
public class GsmCallListener extends BroadcastReceiver {

    private CallInteraction mCallInteraction;
    private Call mCurrentCall;

    private String mLastState = "";
    private boolean mIsOnHold = false;

    public GsmCallListener(Call call, CallInteraction callInteraction) {
        mCallInteraction = callInteraction;
        mCurrentCall = call;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // If, the received action is not a type of "Phone_State", ignore it.
        if (!intent.getAction().equals("android.intent.action.PHONE_STATE")) {
            return;
        }

        String state = intent.getExtras().getString(TelephonyManager.EXTRA_STATE, "");

        // State changes are often received twice.
        if (state.equals(mLastState)) {
            return;
        }

        // On GSM ringing try to send the GSM call to voicemail or put SIP call on hold.
        if (state.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
            if (!couldEndGsmCall(context)) {
                mCallInteraction.putOnHold(mCurrentCall);
                mIsOnHold = true;
            }
        }

        // When GSM goes idle check if we had to put the SIP call on hold en release the hold.
        if (state.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            if (mIsOnHold) {
                mCallInteraction.putOnHold(mCurrentCall);
                mIsOnHold = false;
            }
        }

        mLastState = state;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean couldEndGsmCall(Context context) {
        ITelephony telephonyService;
        TelephonyManager telephony = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        try {
            Class c = Class.forName(telephony.getClass().getName());
            Method m = c.getDeclaredMethod("getITelephony");
            m.setAccessible(true);
            telephonyService = (ITelephony) m.invoke(telephony);
            telephonyService.endCall();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
