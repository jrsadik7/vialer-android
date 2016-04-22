package com.voipgrid.vialer.sip;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.voipgrid.vialer.BuildConfig;
import com.voipgrid.vialer.CallActivity;
import com.voipgrid.vialer.R;
import com.voipgrid.vialer.VialerGcmListenerService;
import com.voipgrid.vialer.analytics.AnalyticsApplication;
import com.voipgrid.vialer.analytics.AnalyticsHelper;
import com.voipgrid.vialer.api.Registration;
import com.voipgrid.vialer.api.ServiceGenerator;
import com.voipgrid.vialer.api.models.PhoneAccount;
import com.voipgrid.vialer.util.GsmCallListener;
import com.voipgrid.vialer.util.JsonStorage;
import com.voipgrid.vialer.util.PhoneNumberUtils;
import com.voipgrid.vialer.util.PhonePermission;

import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AudDevManager;
import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.AuthCredInfo;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.CallMediaInfoVector;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.CallSetting;
import org.pjsip.pjsua2.CodecInfo;
import org.pjsip.pjsua2.CodecInfoVector;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.EpConfig;
import org.pjsip.pjsua2.LogConfig;
import org.pjsip.pjsua2.MediaConfig;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.UaConfig;
import org.pjsip.pjsua2.pj_log_decoration;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;
import org.pjsip.pjsua2.pjsua_call_flag;

import java.util.HashMap;
import java.util.Map;

import okhttp3.ResponseBody;
import retrofit2.Callback;
import retrofit2.Response;

import static com.voipgrid.vialer.api.ServiceGenerator.getUserAgentHeader;

/**
 * SipService ensures proper lifecycle management for the PJSUA2 library and
 * provides a persistent interface to SIP services throughout the app.
 *
 * Goals of this class:
 * - initiate calls
 * - handle call actions (on speaker, mute, show dialpad, put on hold).
 * - Handle call status (CONNECTED, DISCONNECTED, MEDIA_AVAILABLE, MEDIA_UNAVAILABLE, RINGING_IN,
 *   RINGING_OUT).
 */
public class SipService extends Service implements
        AccountStatus,
        CallStatus,
        CallInteraction {

    private final static String TAG = SipService.class.getSimpleName(); // TAG used for debug Logs
    private final IBinder mBinder = new SipServiceBinder();

    private Handler mHandler;
    private LocalBroadcastManager mBroadcastManager;
    private ToneGenerator mToneGenerator;

    private GsmCallListener mGsmCallListener;
    private Endpoint mEndpoint;
    private SipAccount mSipAccount;
    private String mToken;
    private String mUrl;
    private String mNumber;
    private String mCallerId;

    private boolean mHasActiveCall = false;
    private String mCallType;
    private Call mCall;
    private boolean mHasHold = false;
    private SIPLogWriter mSIPLogWriter;
    // Message throughput logging timestamp.
    private String mMessageStartTime;

    private static Map<String, Short> sCodecPrioMapping;

    static {
        sCodecPrioMapping = new HashMap<>();
        sCodecPrioMapping.put("PCMA/8000/1", (short) 210);
        sCodecPrioMapping.put("G722/16000/1", (short) 209);
        sCodecPrioMapping.put("iLBC/8000/1", (short) 208);
        sCodecPrioMapping.put("PCMU/8000/1", (short) 0);
        sCodecPrioMapping.put("speex/8000/1", (short) 0);
        sCodecPrioMapping.put("speex/16000/1", (short) 0);
        sCodecPrioMapping.put("speex/32000/1", (short) 0);
        sCodecPrioMapping.put("GSM/8000/1", (short) 0);
    }

    private BroadcastReceiver mCallInteractionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Call call = getCurrentCall();
            if(call != null) {
                handleCallInteraction(call, intent);
            }
        }
    };

    private BroadcastReceiver mKeyPadInteractionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleKeyPadInteraction(getCurrentCall(), intent);
        }
    };

    public class SipServiceBinder extends Binder {
        public SipService getService() {
            // Return this instance of SipService so clients can call public methods.
            return SipService.this;
        }
    }

    /**
     * SIP does not present Media by default.
     * Use Android's ToneGenerator to play a dial tone at certain required times.
     * @see # for usage of delayed "mRingbackRunnable" callback.
     */
    private Runnable mRingbackRunnable = new Runnable() {
        @Override
        public void run() {
            // Play a ringback tone to update a user that setup is ongoing.
            mToneGenerator.startTone(ToneGenerator.TONE_SUP_DIAL, 1000);
            mHandler.postDelayed(mRingbackRunnable, 4000);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mHandler = new Handler();

        mToneGenerator = new ToneGenerator(
                AudioManager.STREAM_VOICE_CALL,
                SipConstants.RINGING_VOLUME);

        mBroadcastManager = LocalBroadcastManager.getInstance(this);

        PhoneAccount phoneAccount = new JsonStorage<PhoneAccount>(this).get(PhoneAccount.class);
        if (phoneAccount != null) {
            /* Try to load PJSIP library */
            loadPjsip();

            pjsip_transport_type_e transportType = pjsip_transport_type_e.PJSIP_TRANSPORT_UDP;
            String sipTransport = this.getString(R.string.sip_transport_type);
            String tcp = "";

            if (sipTransport.equals("tcp")) {
                transportType = pjsip_transport_type_e.PJSIP_TRANSPORT_TCP;
                tcp = ";transport=tcp";
            }
            mEndpoint = createEndpoint(
                    transportType,
                    createTransportConfig(getResources().getInteger(R.integer.sip_port))
            );

            if (mEndpoint != null) {
                setCodecPrio();

                AuthCredInfo credInfo = new AuthCredInfo(
                        this.getString(R.string.sip_auth_scheme),
                        this.getString(R.string.sip_auth_realm),
                        phoneAccount.getAccountId(),
                        0,
                        phoneAccount.getPassword()
                );

                String sipAccountRegId = SipUri.sipAddress(this, phoneAccount.getAccountId()) + tcp;
                String sipRegistratUri = SipUri.prependSIPUri(this, this.getString(R.string.sip_host)) + tcp;

                AccountConfig accountConfig = createAccountConfig(
                        sipAccountRegId,
                        sipRegistratUri,
                        credInfo
                );
                mSipAccount = createSipAccount(accountConfig, this, this);

                setupCallInteractionReceiver();
                setupKeyPadInteractionReceiver();
            } else {
                Log.e(TAG, "There is no PJSIP endpoint!");
            }
        } else {
            /* User has no Sip Account so service has no function at all. We stop the service */
            broadcast(SipConstants.SIP_SERVICE_HAS_NO_ACCOUNT);
            stopSelf();
        }

    }

    private void setCodecPrio() {
        try {
            CodecInfoVector codecList = mEndpoint.codecEnum();
            String codecId;
            CodecInfo info;
            Short prio;

            for (int i = 1; i < codecList.size(); i++) {
                info = codecList.get(i);
                codecId = info.getCodecId();
                prio = sCodecPrioMapping.get(codecId);
                if (prio != null) {
                    mEndpoint.codecSetPriority(codecId, prio);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        mBroadcastManager.unregisterReceiver(mCallInteractionReceiver);
        mBroadcastManager.unregisterReceiver(mKeyPadInteractionReceiver);

        /* Cleanup SipAccount */
        if(mSipAccount != null) {
            mSipAccount.delete();
            mSipAccount = null;
        }

        /* Cleanup endpoint */
        if(mEndpoint != null) {
            try {
                mEndpoint.libDestroy();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                mEndpoint.delete();
                mEndpoint = null;
            }
        }

        broadcast(SipConstants.SERVICE_STOPPED);

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mCallType = intent.getAction();
        Uri number = intent.getData();
        switch (mCallType) {
            case SipConstants.ACTION_VIALER_INCOMING :
                mUrl = intent.getStringExtra(SipConstants.EXTRA_RESPONSE_URL);
                mToken = intent.getStringExtra(SipConstants.EXTRA_REQUEST_TOKEN);
                mNumber = intent.getStringExtra(SipConstants.EXTRA_PHONE_NUMBER);
                mCallerId = intent.getStringExtra(SipConstants.EXTRA_CONTACT_NAME);
                mMessageStartTime = intent.getStringExtra(
                        VialerGcmListenerService.MESSAGE_START_TIME);
                break;
            case SipConstants.ACTION_VIALER_OUTGOING :
                SipCall call = new SipCall(mSipAccount, this);
                call.setPhoneNumberUri(number);
                call.setCallerId(intent.getStringExtra(SipConstants.EXTRA_CONTACT_NAME));
                call.setPhoneNumber(intent.getStringExtra(SipConstants.EXTRA_PHONE_NUMBER));
                onCallOutgoing(call, number);
                break;
            default: stopSelf(); //no valid action found. No need to keep the service active
        }

        return START_NOT_STICKY;
    }

    private void broadcast(String status) {
        Intent intent = new Intent(SipConstants.ACTION_BROADCAST_CALL_STATUS);
        intent.putExtra(SipConstants.CALL_STATUS_KEY, status);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void setupCallInteractionReceiver() {
        IntentFilter intentFilter = new IntentFilter(
                SipConstants.ACTION_BROADCAST_CALL_INTERACTION);
        mBroadcastManager.registerReceiver(mCallInteractionReceiver, intentFilter);
    }

    private void setupKeyPadInteractionReceiver() {
        IntentFilter intentFilter = new IntentFilter(
                SipConstants.ACTION_BROADCAST_KEY_PAD_INTERACTION);
        mBroadcastManager.registerReceiver(mKeyPadInteractionReceiver, intentFilter);
    }

    /** AccountStatus **/
    @Override
    public void onAccountRegistered(Account account, OnRegStateParam param) {
        if(mCallType.equals(SipConstants.ACTION_VIALER_INCOMING)) {

            AnalyticsHelper analyticsHelper = new AnalyticsHelper(
                    ((AnalyticsApplication) getApplication()).getDefaultTracker()
            );

            Registration registrationApi = ServiceGenerator.createService(
                    this,
                    Registration.class,
                    mUrl
            );

            // Accepted event.
            analyticsHelper.sendEvent(
                    getString(R.string.analytics_event_category_middleware),
                    getString(R.string.analytics_event_action_acceptance),
                    getString(R.string.analytics_event_label_middleware_accepted)
            );

            long startTime = (long) (Double.parseDouble(mMessageStartTime) * 1000);  // To ms.
            long startUpTime = System.currentTimeMillis() - startTime;

            // Response timing.
            analyticsHelper.sendTiming(
                    getString(R.string.analytics_event_category_middleware),
                    getString(R.string.analytics_event_name_call_response),
                    startUpTime
            );

            retrofit2.Call<ResponseBody> call = registrationApi.reply(mToken, true, mMessageStartTime);
            call.enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(retrofit2.Call<ResponseBody> call, Response<ResponseBody> response) {
                    if (!response.isSuccess()) {
                        // Too late or middleware failure so stop the service.
                        broadcast(SipConstants.SIP_SERVICE_ACCOUNT_REGISTRATION_FAILED);
                        stopSelf();
                    }
                }

                @Override
                public void onFailure(retrofit2.Call<ResponseBody> call, Throwable t) {
                    broadcast(SipConstants.SIP_SERVICE_ACCOUNT_REGISTRATION_FAILED);
                    stopSelf();
                }
            });
        }
    }

    /** AccountStatus **/
    @Override
    public void onAccountUnregistered(Account account, OnRegStateParam param) {

    }

    /** AccountStatus **/
    @Override
    public void onAccountInvalidState(Account account, Throwable fault) {

    }

    /**
     * Try to load PJSIP library. If the app can not load PJSIP the service stops since it can
     * not use SIP to setup phonecalls.
     *
     * @throws UnsatisfiedLinkError
     */
    private void loadPjsip() {
        /* Try to load PJSIP library */
        try {
            System.loadLibrary("pjsua2");
        } catch (UnsatisfiedLinkError error) { /* Can not load PJSIP library */
            Log.e(TAG, error.getMessage());
            /* Notify app */
            broadcast(SipConstants.SIP_SERVICE_CAN_NOT_LOAD_PJSIP);
            /* Stop the service since the app can not use SIP */
            stopSelf();
        }
    }

    private Endpoint createEndpoint(pjsip_transport_type_e transportType,
                                    TransportConfig transportConfig) {
        Endpoint endpoint = new Endpoint();
        EpConfig endpointConfig = new EpConfig();

        // Set echo cancellation options for endpoint.
        MediaConfig mediaConfig = endpointConfig.getMedConfig();
        mediaConfig.setEcOptions(SipConstants.WEBRTC_ECHO_CANCELLATION);
        mediaConfig.setEcTailLen(SipConstants.ECHO_CANCELLATION_TAIL_LENGTH);
        endpointConfig.setMedConfig(mediaConfig);

        try {
            endpoint.libCreate();
        } catch (Exception e) {
            Log.e(TAG, "Unable to create the PJSIP library");
            e.printStackTrace();
            broadcast(SipConstants.SIP_SERVICE_CAN_NOT_START_PJSIP);
            stopSelf();
            return null;
        }

        if (BuildConfig.DEBUG) {
            endpointConfig.getLogConfig().setLevel(SipConstants.SIP_LOG_LEVEL);
            endpointConfig.getLogConfig().setConsoleLevel(SipConstants.SIP_CONSOLE_LOG_LEVEL);
            LogConfig logConfig = endpointConfig.getLogConfig();
            mSIPLogWriter = new SIPLogWriter();
            logConfig.setWriter(mSIPLogWriter);
            logConfig.setDecor(logConfig.getDecor() &
                            ~(pj_log_decoration.PJ_LOG_HAS_CR.swigValue() |
                                    pj_log_decoration.PJ_LOG_HAS_NEWLINE.swigValue())
            );
        }

        UaConfig uaConfig = endpointConfig.getUaConfig();
        uaConfig.setUserAgent(getUserAgentHeader(this));

        try {
            endpoint.libInit(endpointConfig);
        } catch (Exception e) {
            Log.e(TAG, "Unable to init the PJSIP library");
            e.printStackTrace();
            broadcast(SipConstants.SIP_SERVICE_CAN_NOT_START_PJSIP);
            stopSelf();
            return null;
        }

        try {
            endpoint.transportCreate(transportType, transportConfig);
            endpoint.libStart();
        } catch (Exception exception) {
            Log.e(TAG, "Unable to start the PJSIP library");
            broadcast(SipConstants.SIP_SERVICE_CAN_NOT_START_PJSIP);
            stopSelf();
            return null;
        }
        return endpoint;
    }

    private TransportConfig createTransportConfig(long port) {
        TransportConfig config = new TransportConfig();

        config.setPort(port);
        return config;
    }

    private SipAccount createSipAccount(AccountConfig accountConfig, AccountStatus accountStatus,
                                        CallStatus callStatus) {
        SipAccount sipAccount = null;
        try {
            sipAccount = new SipAccount(accountConfig, accountStatus, callStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sipAccount;
    }

    private AccountConfig createAccountConfig(String idUri, String registrarUri, AuthCredInfo credInfo) {
        AccountConfig config = new AccountConfig();
        config.setIdUri(idUri);
        config.getRegConfig().setRegistrarUri(registrarUri);
        config.getSipConfig().getAuthCreds().add(credInfo);
        config.getSipConfig().getProxies().add(registrarUri);
        return config;
    }

    @Override
    public void onCallIncoming(Call call) {
        CallOpParam callOpParam = new CallOpParam();
        callOpParam.setStatusCode(
                mHasActiveCall ?
                        pjsip_status_code.PJSIP_SC_BUSY_HERE :
                        pjsip_status_code.PJSIP_SC_RINGING
        );
        setCurrentCall(call);
        try {
            call.answer(callOpParam);
            callVisibleForUser(call, CallActivity.TYPE_INCOMING_CALL, mNumber, mCallerId);
        } catch (Exception e) {
            onCallInvalidState(call, e);
        }
    }

    @Override
    public void onCallOutgoing(Call call, Uri number) {
        CallOpParam callOpParam = new CallOpParam();
        callOpParam.setStatusCode(pjsip_status_code.PJSIP_SC_RINGING);
        try {
            call.makeCall(number.toString(), callOpParam);

            setCurrentCall(call);
            callVisibleForUser(call, CallActivity.TYPE_OUTGOING_CALL, number);
        } catch (Exception e) {
            onCallInvalidState(call, e);
        }
    }

    @Override
    public void onCallConnected(Call call) {
        broadcast(SipConstants.CALL_CONNECTED_MESSAGE);

        if (PhonePermission.hasPermission(getApplicationContext())) {
            mGsmCallListener = new GsmCallListener(call, this);
            registerReceiver(mGsmCallListener, new IntentFilter("android.intent.action.PHONE_STATE"));
        }
    }

    @Override
    public void onCallDisconnected(final Call call) {
        try {
            // Try to unregister the sip account with the sipproxy
            mSipAccount.setRegistration(false);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Cleanup the call
        setCurrentCall(null);
        if (PhonePermission.hasPermission(getApplicationContext())) {
            unregisterReceiver(mGsmCallListener);
        }
        broadcast(SipConstants.CALL_DISCONNECTED_MESSAGE);
        stopSelf();
    }

    @Override
    public void onCallInvalidState(Call call, Throwable fault) {
        broadcast(SipConstants.CALL_INVALID_STATE);
        stopSelf();
    }

    @Override
    public void onCallMediaAvailable(Call call, AudioMedia media) {
        try {
            AudDevManager audDevManager = mEndpoint.audDevManager();
            media.startTransmit(audDevManager.getPlaybackDevMedia());
            audDevManager.getCaptureDevMedia().startTransmit(media);

            broadcast(SipConstants.CALL_MEDIA_AVAILABLE_MESSAGE);
        } catch (Exception e) {
            broadcast(SipConstants.CALL_MEDIA_FAILED);
            e.printStackTrace();
        }
    }

    @Override
    public void onCallMediaUnavailable(Call call) {

    }

    @Override
    public void onCallStartRingback() {
        mHandler.postDelayed(mRingbackRunnable, 2000);
    }

    @Override
    public void onCallStopRingback() {
        mHandler.removeCallbacks(mRingbackRunnable);
    }

    private void callVisibleForUser(Call call, String type, Uri number) {
        Intent intent = new Intent(this, CallActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setDataAndType(number, type);
        if(call instanceof SipCall) {
            SipCall sipCall = (SipCall) call;
            intent.putExtra(CallActivity.CONTACT_NAME, sipCall.getCallerId());
            intent.putExtra(CallActivity.PHONE_NUMBER, sipCall.getPhoneNumber());
        }
        startActivity(intent);
    }

    private void callVisibleForUser(Call call, String type, String number, String callerId) {
        Intent intent = new Intent(this, CallActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri sipAddressUri = SipUri.sipAddressUri(
                this,
                PhoneNumberUtils.format(number)
        );
        intent.setDataAndType(sipAddressUri, type);
        intent.putExtra(CallActivity.CONTACT_NAME, callerId);
        intent.putExtra(CallActivity.PHONE_NUMBER, number);
        startActivity(intent);
    }

    private void handleCallInteraction(Call call, Intent intent) {
        String type = intent.getStringExtra(SipConstants.CALL_STATUS_ACTION);
        switch (type) {
            case SipConstants.CALL_UPDATE_MICROPHONE_VOLUME_ACTION :
                updateMicrophoneVolume(call,
                        intent.getLongExtra(SipConstants.MICROPHONE_VOLUME_KEY, 1));
                break;
            case SipConstants.CALL_PUT_ON_HOLD_ACTION : putOnHold(call); break;
            case SipConstants.CALL_HANG_UP_ACTION : hangUp(call); break;
            case SipConstants.CALL_PICK_UP_ACTION : answer(call); break;
            case SipConstants.CALL_DECLINE_ACTION : decline(call); break;
            case SipConstants.CALL_XFER_ACTION : xFer(call); break;
        }
    }

    private void handleKeyPadInteraction(Call call, Intent intent) {
        try {
            call.dialDtmf(intent.getStringExtra(SipConstants.KEY_PAD_DTMF_TONE));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Function for doing a SIP hangup with the given status code.
     * @param call
     * @param statusCode
     */
    private void hangUpWithStatusCode(Call call, pjsip_status_code statusCode) {
        try {
            CallOpParam callOpParam = new CallOpParam(true);
            callOpParam.setStatusCode(statusCode);
            call.hangup(callOpParam);
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public void hangUp(Call call) {
        hangUpWithStatusCode(call, pjsip_status_code.PJSIP_SC_DECLINE);
    }

    @Override
    public void decline(Call call) {
        hangUpWithStatusCode(call, pjsip_status_code.PJSIP_SC_BUSY_HERE);
    }

    @Override
    public void answer(Call call) {
        CallOpParam callOpParam = new CallOpParam(true);
        callOpParam.setStatusCode(pjsip_status_code.PJSIP_SC_ACCEPTED);
        try {
            if(call != null) {
                call.answer(callOpParam);
            }
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public void updateMicrophoneVolume(Call call, long newVolume) {
        try {
            CallMediaInfoVector callMediaInfoVector = call.getInfo().getMedia();
            long size=callMediaInfoVector.size();
            for(int i = 0; i < size; i++) {
                CallMediaInfo callMediaInfo = callMediaInfoVector.get(i);
                if(callMediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO) {
                    AudioMedia audioMedia = AudioMedia.typecastFromMedia(call.getMedia(i));
                    audioMedia.adjustRxLevel(newVolume);
                }
            }
        } catch (Exception e) {
            broadcast(SipConstants.CALL_UPDATE_MICROPHONE_VOLUME_FAILED);
            e.printStackTrace();
        }
    }

    @Override
    public void putOnHold(Call call) {
        try {
            CallOpParam callOpParam = new CallOpParam(true);
            if(!mHasHold) {
                call.setHold(callOpParam);
                broadcast(SipConstants.CALL_PUT_ON_HOLD_ACTION);
            } else {
                CallSetting callSetting = callOpParam.getOpt();
                callSetting.setFlag(pjsua_call_flag.PJSUA_CALL_UNHOLD.swigValue());
                call.reinvite(callOpParam);
                broadcast(SipConstants.CALL_UNHOLD_ACTION);
            }
            mHasHold = !mHasHold;
        } catch (Exception e) {
            broadcast(SipConstants.CALL_PUT_ON_HOLD_FAILED);
            e.printStackTrace();
        }
    }

    @Override
    public void xFer(Call call) {

    }

    private void setCurrentCall(Call call) {
        mCall = call;
        mHasActiveCall = (call != null);
    }

    public Call getCurrentCall() {
        return mCall;
    }
}