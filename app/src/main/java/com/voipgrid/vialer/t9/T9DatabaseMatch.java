package com.voipgrid.vialer.t9;

/**
 * Created by marcov on 17-3-16.
 */
public class T9DatabaseMatch {
    private long mContactId;
    private String mLookupKey;

    public T9DatabaseMatch(long contactId, String lookupKey) {
        setContactId(contactId);
        setLookupKey(lookupKey);
    }

    public long getContactId() {
        return mContactId;
    }

    public void setContactId(long mContactId) {
        this.mContactId = mContactId;
    }

    public String getLookupKey() {
        return mLookupKey;
    }

    public void setLookupKey(String mLookupKey) {
        this.mLookupKey = mLookupKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        T9DatabaseMatch that = (T9DatabaseMatch) o;

        if (mContactId != that.mContactId) return false;
        return mLookupKey.equals(that.mLookupKey);

    }

    @Override
    public int hashCode() {
        int result = (int) (mContactId ^ (mContactId >>> 32));
        result = 31 * result + mLookupKey.hashCode();
        return result;
    }
}
