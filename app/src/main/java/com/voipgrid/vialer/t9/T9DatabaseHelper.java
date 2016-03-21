package com.voipgrid.vialer.t9;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.util.Log;

import com.voipgrid.vialer.contacts.SyncContact;
import com.voipgrid.vialer.contacts.SyncUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for accessing the t9 contact database.
 */
public class T9DatabaseHelper extends SQLiteOpenHelper {
    public static final int DATABASE_VERSION = 2;
    public static final String DATABASE_NAME = "t9.db";

    private static final int MAX_RESULTS = 20;

    private Context mContext;

    public T9DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }

    /**
     * Expandable interface if we need more tables in the future.
     */
    public interface Tables {
        String T9_CONTACT = "t9_contact";
    }

    /**
     * Interface for the columns required in the t9_contact table.
     */
    public interface T9ContactColumns extends BaseColumns {
        String T9_QUERY = "t9_query";
        String CONTACT_ID = "contact_id";
        String LOOKUP_KEY = "lookup_key";
        String LAST_UPDATED = "last_updated";
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        setupTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Data is re-creatable so it is safe to drop the db on an upgrade.
        dropTables(db);
        onCreate(db);
        SyncUtils.setRequiresFullContactSync(mContext, true);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }

    /**
     * Function to setup the database tables.
     * @param db
     */
    private void setupTables(SQLiteDatabase db) {
        // Create t9 contact table.
        db.execSQL("CREATE TABLE " + Tables.T9_CONTACT + " (" +
                T9ContactColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                T9ContactColumns.T9_QUERY + " TEXT COLLATE NOCASE, " +
                T9ContactColumns.LOOKUP_KEY + " TEXT, " +
                T9ContactColumns.CONTACT_ID + " INTEGER," +
                T9ContactColumns.LAST_UPDATED + " LONG " +
                ");");

        // Set indexes.
        db.execSQL("CREATE INDEX IF NOT EXISTS t9_query_index ON " +
                Tables.T9_CONTACT + " (" + T9ContactColumns.T9_QUERY + ");");

        db.execSQL("CREATE INDEX IF NOT EXISTS t9_lookup_key_index ON " +
                Tables.T9_CONTACT + " (" + T9ContactColumns.LOOKUP_KEY + ");");

        db.execSQL("CREATE INDEX IF NOT EXISTS t9_contact_id_index ON " +
                Tables.T9_CONTACT + " (" + T9ContactColumns.CONTACT_ID + ");");
    }

    /**
     * Function to drop the database tables.
     * @param db
     */
    private void dropTables(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + Tables.T9_CONTACT);
    }

    /**
     * Function to optimize the indexes and table after a sync.
     */
    public void afterSyncCleanup() {
        SQLiteDatabase db = getReadableDatabase();
        analyzeDB(db);
        db.close();
    }

    /**
     * Analyze tables and indexes.
     * @param db
     */
    private void analyzeDB(SQLiteDatabase db) {
        db.execSQL("ANALYZE " + Tables.T9_CONTACT);
        db.execSQL("ANALYZE t9_query_index");
        db.execSQL("ANALYZE t9_contact_id_index");
    }

    /**
     * Function to insert a contact in the t9 contact table.
     * @param syncContact Contact to be synced.
     */
    public void insertT9Contact(SyncContact syncContact) {
        SQLiteDatabase db = getReadableDatabase();
        insertDisplayNameQuery(db, syncContact);
        insertPhoneNumberQueries(db, syncContact);
        db.close();
    }

    /**
     * Function to update a t9 contact. This deletes all old entries and creates new ones with
     * the new data.
     * @param syncContact Contact to be synced.
     */
    public void updateT9Contact(SyncContact syncContact) {
        SQLiteDatabase db = getReadableDatabase();
        removeContactEntries(db, syncContact.getContactId());
        db.close();
        insertT9Contact(syncContact);
    }

    /**
     * Function to remove contact entries for a contact.
     * @param db
     * @param contactId Id of the contact to remove.
     */
    private void removeContactEntries(SQLiteDatabase db, long contactId) {
        db.delete(Tables.T9_CONTACT, T9ContactColumns.CONTACT_ID + "=" +
                        contactId, null);
    }

    /**
     * Function that inserts the t9 queries for the phone numbers.
     * @param db
     * @param contactId Id of the contact to insert for.
     * @param phoneNumbers List of phone numbers to insert queries for.
     */
    private void insertPhoneNumberQueries(SQLiteDatabase db, SyncContact syncContact) {
        try {
            final String numberSqlInsert = "INSERT INTO " + Tables.T9_CONTACT + " (" +
                    T9ContactColumns.CONTACT_ID + ", " +
                    T9ContactColumns.LOOKUP_KEY + ", " +
                    T9ContactColumns.T9_QUERY  + ") " +
                    " VALUES (?, ?, ?)";
            final SQLiteStatement numberInsert = db.compileStatement(numberSqlInsert);

            List<String> phoneNumbers = syncContact.getPhoneNumbers();

            for (int i = 0; i < phoneNumbers.size(); i++) {
                numberInsert.bindLong(1, syncContact.getContactId());
                numberInsert.bindString(2, syncContact.getLookupKey());
                numberInsert.bindString(3, phoneNumbers.get(i));
                numberInsert.executeInsert();
                numberInsert.clearBindings();
            }
        } finally {

        }
    }

    /**
     * Function to insert t9 queries for the display name of a contact.
     * @param db
     * @param contactId Id of the contact to insert for.
     * @param displayName The display name to insert t9 queries for.
     */
    private void insertDisplayNameQuery(SQLiteDatabase db, SyncContact syncContact) {
        try {
            final String sqlInsert = "INSERT INTO " + Tables.T9_CONTACT + " (" +
                    T9ContactColumns.CONTACT_ID + ", " +
                    T9ContactColumns.LOOKUP_KEY + ", " +
                    T9ContactColumns.T9_QUERY  + ") " +
                    " VALUES (?, ?, ?)";
            final SQLiteStatement insert = db.compileStatement(sqlInsert);

            // Computes a list of prefixes of a given contact name.
            ArrayList<String> T9NameQueries = T9Query.generateT9NameQueries(syncContact.getDisplayName());
            for (String T9NameQuery : T9NameQueries) {
                insert.bindLong(1, syncContact.getContactId());
                insert.bindString(2, syncContact.getLookupKey());
                insert.bindString(3, T9NameQuery);
                insert.executeInsert();
                insert.clearBindings();
            }
        } finally {

        }
    }

    /**
     * Function that tries to find contacts that match the t9 query.
     * @param T9Query
     * @return
     */
    public ArrayList<T9DatabaseMatch> getT9ContactIdMatches(String T9Query) {
        ArrayList<T9DatabaseMatch> matches = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        if (db == null) {
            // Database not ready yet.
            return matches;
        }

        // Match as 'starts with'.
        String prefixQuery = T9Query + "%";

        // Query to select contact id's that have a query that starts with prefixQuery.
        Cursor cursor = db.rawQuery("SELECT " +
                T9ContactColumns.CONTACT_ID + ", " + T9ContactColumns.LOOKUP_KEY +
                " FROM " +  Tables.T9_CONTACT +
                " WHERE " + Tables.T9_CONTACT + "." + T9ContactColumns.T9_QUERY +
                " LIKE '" + prefixQuery + "'", null);

        if (cursor == null) {
            return matches;
        }

        // Loop results and add them to matches.
        while ((cursor.moveToNext()) && (matches.size() < MAX_RESULTS)) {
            long contactId = cursor.getLong(0);  // we only select 1 column so get the first one
            String lookupKey = cursor.getString(1);

            T9DatabaseMatch match = new T9DatabaseMatch(contactId, lookupKey);

            // We do not want duplicates.
            if (matches.contains(match)) {
                continue;
            }
            matches.add(match);
        }

        // Close resources.
        cursor.close();
        db.close();

        return matches;
    }

}
