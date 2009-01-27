/*
 * Copyright 2008 (c) ralfoide gmail com, 2008
 * Project: timeriffic
 * License: GPL version 3 or any later version
 */

package com.alfray.timeriffic.profiles;

import java.security.InvalidParameterException;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

//-----------------------------------------------

/*
 * Debug Tip; to view content of database, use:
 * $ cd /cygdrive/c/.../android-sdk_..._windows/tools
 * $ ./adb shell 'echo ".dump" | sqlite3 data/data/com.alfray.timeriffic/databases/games.db'
 */

/**
 * Helper to access the profiles database.
 * <p/>
 * The interface is similar to a {@link ContentProvider}, which should make it
 * easy to use only later.
 */
public class ProfilesDB {
    
    private static final String TAG = "ProfilesDB";

    private static final String PROFILES_TABLE = "profiles";
    private static final String DB_NAME = "profiles.db";
    private static final int DB_VERSION = 1 * 100 + 1; // major*100 + minor

    private SQLiteDatabase mDb;
    private DatabaseHelper mDbHelper;

    private Cursor mProfilesCountCursor;

    // ----------------------------------

    /** Call this after creating this object. */
    public boolean onCreate(Context context) {
        mDbHelper = new DatabaseHelper(context, DB_NAME, DB_VERSION);
        mDb = mDbHelper.getWritableDatabase();
        boolean created = mDb != null;
        return created;
    }

    /** Call this when the database is no longer needed. */
    public void onDestroy() {
        if (mProfilesCountCursor != null) {
            mProfilesCountCursor.close();
            mProfilesCountCursor = null;
        }
        if (mDbHelper != null) {
            mDbHelper.close();
            mDbHelper = null;
        }
    }

    // ----------------------------------

    /**
     * @see SQLiteDatabase#beginTransaction()
     */
    public void beginTransaction() {
        mDb.beginTransaction();
    }

    /**
     * @see SQLiteDatabase#setTransactionSuccessful()
     */
    public void setTransactionSuccessful() {
        mDb.setTransactionSuccessful();
    }

    /**
     * @see SQLiteDatabase#endTransaction()
     */
    public void endTransaction() {
        mDb.endTransaction();
    }

    // ----------------------------------
    
    public long getProfileIdForRowId(long row_id) {
        try {
            SQLiteStatement sql = mDb.compileStatement(
                    String.format("SELECT %s FROM %s WHERE %s=%d;",
                            Columns.PROFILE_ID,
                            PROFILES_TABLE,
                            Columns._ID, row_id));
            return sql.simpleQueryForLong();
        } catch (SQLiteDoneException e) {
            // no profiles
            return 0;
        }
    }
    
    public long getMaxProfileIndex(long maxProfileIndex) {
        try {
            String testMaxProfId = "";
            if (maxProfileIndex > 0) {
                testMaxProfId = String.format("AND %s<%d",
                        Columns.PROFILE_ID, maxProfileIndex << Columns.PROFILE_SHIFT);
            }
            // e.g. SELECT MAX(prof_id) FROM profiles WHERE type=1 [ AND prof_id < 65536 ]
            SQLiteStatement sql = mDb.compileStatement(
                    String.format("SELECT MAX(%s) FROM %s WHERE %s=%d %s;",
                            Columns.PROFILE_ID,
                            PROFILES_TABLE,
                            Columns.TYPE, Columns.TYPE_IS_PROFILE,
                            testMaxProfId));

            return sql.simpleQueryForLong() >> Columns.PROFILE_SHIFT;
        } catch (SQLiteDoneException e) {
            // no profiles
            return 0;
        }
    }

    public long getMaxActionIndex(long profileIndex, long maxActionIndex) {
        try {
            long pid = profileIndex << Columns.PROFILE_SHIFT;

            String testMaxActionId = "";
            if (maxActionIndex > 0) {
                testMaxActionId = String.format("AND %s<%d",
                        Columns.PROFILE_ID, pid + maxActionIndex);
            }
            // e.g. SELECT MAX(prof_id) FROM profiles WHERE type=2 AND prof_id > 65536 [ AND prof_id < 65536+256 ]
            SQLiteStatement sql = mDb.compileStatement(
                    String.format("SELECT MAX(%s) FROM %s WHERE %s=%d AND %s>%d %s;",
                            Columns.PROFILE_ID,
                            PROFILES_TABLE,
                            Columns.TYPE, Columns.TYPE_IS_TIMED_ACTION,
                            Columns.PROFILE_ID, pid, 
                            testMaxActionId));

            return sql.simpleQueryForLong() & Columns.ACTION_MASK;
        } catch (SQLiteDoneException e) {
            // no actions
            return 0;
        }
    }

    // ----------------------------------

    /**
     * Inserts a new profile before the given profile index.
     * If beforeProfileIndex is <= 0, insert at the end.
     * 
     * @return the profile index (not the row id)
     */
    public long insertProfile(int beforeProfileIndex,
            String title, boolean isEnabled) {

        beginTransaction();
        try {
            long index = getMaxProfileIndex(beforeProfileIndex);
            if (beforeProfileIndex <= 0) {
                long max = Long.MAX_VALUE >> Columns.PROFILE_SHIFT;
                if (index >= max - 1) {
                    throw new UnsupportedOperationException("Profile index at maximum.");
                } else if (index < max - Columns.PROFILE_GAP) {
                    index += Columns.PROFILE_GAP;
                } else {
                    index += (max - index) / 2;
                }
            } else {
                if (index == beforeProfileIndex - 1) {
                    throw new UnsupportedOperationException("No space left to insert profile before profile.");
                } else {
                    index = (index + beforeProfileIndex) / 2; // get middle offset
                }
            }
            
            long id = index << Columns.PROFILE_SHIFT;
            
            ContentValues values = new ContentValues(2);
            values.put(Columns.PROFILE_ID, id);
            values.put(Columns.TYPE, Columns.TYPE_IS_PROFILE);
            values.put(Columns.DESCRIPTION, title);
            values.put(Columns.IS_ENABLED, isEnabled);
            
            id = mDb.insert(PROFILES_TABLE, Columns.TYPE, values);

            Log.d(TAG, String.format("Insert profile: %d => row %d", index, id));

            if (id < 0) throw new SQLException("insert profile row failed");
            setTransactionSuccessful();
            return index;
        } finally {
            endTransaction();
        }
    }

    /**
     * Inserts a new action for the given profile index.
     * If beforeActionIndex is <= 0, insert at the end of these actions
     * 
     * @return the action index (not the row id)
     */
    public long insertTimedAction(long profileIndex,
            int beforeActionIndex,
            String description,
            boolean isActive,
            int hourMin,
            int days,
            String actions,
            long nextMs) {

        beginTransaction();
        try {
            long pid = profileIndex << Columns.PROFILE_SHIFT;

            long index = getMaxActionIndex(profileIndex, beforeActionIndex);
            if (beforeActionIndex <= 0) {
                int max = Columns.ACTION_MASK;
                if (index >= max - 1) {
                    throw new UnsupportedOperationException("Action index at maximum.");
                } else if (index < max - Columns.PROFILE_GAP) {
                    index += Columns.PROFILE_GAP;
                } else {
                    index += (max - index) / 2;
                }
            } else {
                if (index == beforeActionIndex - 1) {
                    throw new UnsupportedOperationException("No space left to insert action before action.");
                } else {
                    index = (index + beforeActionIndex) / 2; // get middle offset
                }
            }
            
            pid += index;

            ContentValues values = new ContentValues(2);

            values.put(Columns.TYPE, Columns.TYPE_IS_TIMED_ACTION);
            values.put(Columns.PROFILE_ID, pid);
            values.put(Columns.DESCRIPTION, description);
            values.put(Columns.IS_ENABLED, isActive);
            values.put(Columns.HOUR_MIN, hourMin);
            values.put(Columns.DAYS, days);
            values.put(Columns.ACTIONS, actions);
            values.put(Columns.NEXT_MS, nextMs);
            
            long id = mDb.insert(PROFILES_TABLE, Columns.TYPE, values);

            Log.d(TAG, String.format("Insert profile %d, action: %d => row %d", profileIndex, index, id));

            if (id < 0) throw new SQLException("insert action row failed");
            setTransactionSuccessful();
            return index;
        } finally {
            endTransaction();
        }
    }

    // ----------------------------------

    /** id is used if >= 0 */
    public Cursor query(long id,
            String[] projection,
            String selection, 
            String[] selectionArgs, 
            String sortOrder) {
    	
    	SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
    	qb.setTables(PROFILES_TABLE);

    	if (id >= 0) {
        	qb.appendWhere(String.format("%s=%d", Columns._ID, id));
        }
        
        if (sortOrder == null || sortOrder.length() == 0) sortOrder = Columns.DEFAULT_SORT_ORDER;
        
        Cursor c = qb.query(mDb, projection, selection, selectionArgs,
        		null, // groupBy
        		null, // having,
        		sortOrder);
        return c;
    }

    // ----------------------------------

    /** id is used if >= 0
     *  
     * @return The number of updated rows
     */
    public int update(long id, ContentValues values, String whereClause, String[] whereArgs) {
        if (id >= 0) {
        	whereClause = addWhereId(id, whereClause);
        }
    	int count = mDb.update(PROFILES_TABLE, values, whereClause, whereArgs);
        return count;
    }

    // ----------------------------------

    /** 
     * @return The number of deleted rows
     */
    public int deleteProfile(long row_id) {
        
        beginTransaction();
        try {
            long pid = getProfileIdForRowId(row_id);
            if (pid == 0) throw new InvalidParameterException("No profile id for this row id.");

            // DELETE FROM profiles WHERE prof_id >= 65536 AND prof_id < 65536+65535
            String where = String.format("%s>=%d AND %s<%d",
                    Columns.PROFILE_ID, pid,
                    Columns.PROFILE_ID, pid + Columns.ACTION_MASK);
            
            int count = mDb.delete(PROFILES_TABLE, where, null);
            setTransactionSuccessful();
            return count;
        } finally {
            endTransaction();
        }
    }

    /** 
     * @return The number of deleted rows
     */
    public int deleteAction(long row_id) {
        
        beginTransaction();
        try {
            // DELETE FROM profiles WHERE TYPE=2 AND _id=65537
            String where = String.format("%s=%d AND %s=%d",
                    Columns.TYPE, Columns.TYPE_IS_TIMED_ACTION,
                    Columns._ID, row_id);
            
            int count = mDb.delete(PROFILES_TABLE, where, null);
            setTransactionSuccessful();
            return count;
        } finally {
            endTransaction();
        }
    }

    // ----------------------------------
    
    /**
     * Helper that returns a where clause "_id=NN" where NN is the last segment of
     * the input URI. If there's an existing whereClause, it is rewritten using
     * "_id=NN AND ( whereClause )".
     */
    private String addWhereId(long id, String whereClause) {
        if (whereClause != null && whereClause.length() > 0) {
            whereClause = "AND (" + whereClause + ")";
        } else {
            whereClause = "";
        }
        whereClause = String.format("%s=%d %s",
                Columns._ID,
                id,
                whereClause);
        return whereClause;
    }

    
    // ----------------------------------    

    /** Convenience helper to open/create/update the database */
    private class DatabaseHelper extends SQLiteOpenHelper {

        public DatabaseHelper(Context context,
                String db_name,
                int version) {
			super(context, db_name, null /* cursor factory */, version);
		}

		@Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(String.format("CREATE TABLE %s "
                    + "(%s INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "%s INTEGER, "
                    + "%s TEXT, "
                    + "%s INTEGER, "
                    + "%s INTEGER, "
                    + "%s INTEGER, "
                    + "%s INTEGER, "
                    + "%s TEXT, "
                    + "%s INTEGER);" ,
                    PROFILES_TABLE,
                    Columns._ID,
                    Columns.TYPE,
                    Columns.DESCRIPTION,
                    Columns.IS_ENABLED,
                    Columns.PROFILE_ID,
                    Columns.HOUR_MIN,
                    Columns.DAYS,
                    Columns.ACTIONS,
                    Columns.NEXT_MS));
            
            SQLiteDatabase old_mDb = mDb;
            mDb = db;
            onInitializeProfiles();
            mDb = old_mDb;
        }
		
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, String.format("Upgrading database from version %1$d to %2$d.",
                    oldVersion, newVersion));
            db.execSQL("DROP TABLE IF EXISTS " + PROFILES_TABLE);
            onCreate(db);
        }
        
        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            // pass
        }        
    }

    /**
     * Called by {@link DatabaseHelper} when the database has just been
     * created to initialize it with initial data. It's safe to use
     * {@link ProfilesDB#insertProfile(String, boolean)} or
     * {@link ProfilesDB#insertTimedAction(String, boolean, int, int, String, long)}
     * at that point.
     */
    private void onInitializeProfiles() {
        long pindex = insertProfile(0, "Weekdaze", true /*isEnabled*/);
        insertTimedAction(pindex, 0,
                "7am Mon - Thu, Ringer on, Vibrate",
                true,               //isActive
                7*60+0,             //hourMin
                Columns.MONDAY + Columns.TUESDAY + Columns.WEDNESDAY + Columns.THURSDAY,
                "M0,V1",            //actions
                0                   //nextMs
                );
        insertTimedAction(pindex, 0,
                "8pm Mon - Thu, Mute, vibrate",
                false,              //isActive
                20*60+0,             //hourMin
                Columns.MONDAY + Columns.TUESDAY + Columns.WEDNESDAY + Columns.THURSDAY,
                "M1,V1",            //actions
                0                   //nextMs
                );

        pindex = insertProfile(0, "Party Time", true /*isEnabled*/);
        insertTimedAction(pindex, 0,
                "9am Fri - Sat, Ringer on",
                false,              //isActive
                9*60+0,             //hourMin
                Columns.FRIDAY + Columns.SATURDAY,
                "M0",               //actions
                0                   //nextMs
                );
        insertTimedAction(pindex, 0,
                "10pm Fri - Sat, Mute, vibrate",
                false,               //isActive
                22*60+0,             //hourMin
                Columns.FRIDAY + Columns.SATURDAY,
                "M1,V1",            //actions
                0                   //nextMs
                );

        pindex = insertProfile(0, "Sleeping-In", true /*isEnabled*/);
        insertTimedAction(pindex, 0,
                "10:30am Sun, Ringer on",
                false,               //isActive
                10*60+30,            //hourMin
                Columns.SUNDAY,
                "M0",               //actions
                0                   //nextMs
                );
        insertTimedAction(pindex, 0,
                "9pm Sun, Mute, vibrate",
                false,               //isActive
                21*60+0,             //hourMin
                Columns.SUNDAY,
                "M1,V1",            //actions
                0                   //nextMs
                );
    }
}
