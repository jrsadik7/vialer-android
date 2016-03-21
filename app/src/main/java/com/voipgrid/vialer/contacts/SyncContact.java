package com.voipgrid.vialer.contacts;

import java.util.List;

/**
 * Class for storing all information needed for the contact to be synced.
 */
public class SyncContact {
    private long mContactId;
    private String mLookupKey;
    private String mDisplayName;
    private List<String> mPhoneNumbers;

    public SyncContact(long contactId, String lookupKey, String displayName,
                       List<String> phoneNumbers) {
        setContactId(contactId);
        setLookupKey(lookupKey);
        setDisplayName(displayName);
        setPhoneNumbers(phoneNumbers);
    }

    public long getContactId() {
        return mContactId;
    }

    private void setContactId(long contactId) {
        this.mContactId = contactId;
    }

    public String getLookupKey() {
        return mLookupKey;
    }

    public void setLookupKey(String lookupKey) {
        this.mLookupKey = lookupKey;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    private void setDisplayName(String displayName) {
        this.mDisplayName = displayName;
    }

    public List<String> getPhoneNumbers() {
        return mPhoneNumbers;
    }

    private void setPhoneNumbers(List<String> phoneNumbers) {
        this.mPhoneNumbers = phoneNumbers;
    }
}
