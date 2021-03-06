package com.voipgrid.vialer.test;

import android.app.Activity;
import android.widget.EditText;

import com.voipgrid.vialer.AccountActivity;
import com.voipgrid.vialer.BuildConfig;
import com.voipgrid.vialer.R;
import com.voipgrid.vialer.api.models.PhoneAccount;
import com.voipgrid.vialer.api.models.SystemUser;
import com.voipgrid.vialer.util.JsonStorage;

import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertTrue;

/**
 * Test AccountActivity behaviour.
 */
public class AccountActivityTest extends RobolectricAbstractTest {

    /**
     * Setup dependencies for tests.
     */
    @Before
    public void setUp() {
        JsonStorage jsonStorage = new JsonStorage(RuntimeEnvironment.application);
        SystemUser systemUser = new SystemUser();
        PhoneAccount phoneAccount = new PhoneAccount();

        systemUser.setMobileNumber("0508009000");
        systemUser.setOutgoingCli("0508009000");

        phoneAccount.setAccountId("123456789");

        jsonStorage.save(systemUser);
        jsonStorage.save(phoneAccount);
    }

    /**
     * Test if the mobile number EditText is filled.
     */
    @Test
    public void mobileNumberFilledTest() {
        Activity activity = Robolectric.setupActivity(AccountActivity.class);

        EditText editText = (EditText) activity.findViewById(R.id.account_mobile_number_edit_text);

        String filledText = editText.getText().toString();

        assertTrue(filledText.equals("0508009000"));
    }

    /**
     * Test if the outgoing cli EditText is filled.
     */
    @Test
    public void outgoingCliFilledTest() {
        Activity activity = Robolectric.setupActivity(AccountActivity.class);

        EditText editText = (EditText) activity.findViewById(R.id.account_outgoing_number_edit_text);

        String filledText = editText.getText().toString();

        assertTrue(filledText.equals("0508009000"));
    }
}
