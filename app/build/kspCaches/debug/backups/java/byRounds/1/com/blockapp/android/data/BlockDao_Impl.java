package com.blockapp.android.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class BlockDao_Impl implements BlockDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<BlockedAppEntity> __insertionAdapterOfBlockedAppEntity;

  private final EntityInsertionAdapter<UsedNonceEntity> __insertionAdapterOfUsedNonceEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeactivate;

  private final SharedSQLiteStatement __preparedStmtOfDeactivateAll;

  private final SharedSQLiteStatement __preparedStmtOfUpdateBlockUntil;

  public BlockDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfBlockedAppEntity = new EntityInsertionAdapter<BlockedAppEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `blocked_apps` (`packageName`,`appLabel`,`blockedAt`,`blockUntil`,`active`) VALUES (?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final BlockedAppEntity entity) {
        statement.bindString(1, entity.getPackageName());
        statement.bindString(2, entity.getAppLabel());
        statement.bindLong(3, entity.getBlockedAt());
        statement.bindLong(4, entity.getBlockUntil());
        final int _tmp = entity.getActive() ? 1 : 0;
        statement.bindLong(5, _tmp);
      }
    };
    this.__insertionAdapterOfUsedNonceEntity = new EntityInsertionAdapter<UsedNonceEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `used_nonces` (`nonce`,`usedAt`) VALUES (?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final UsedNonceEntity entity) {
        statement.bindString(1, entity.getNonce());
        statement.bindLong(2, entity.getUsedAt());
      }
    };
    this.__preparedStmtOfDeactivate = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE blocked_apps SET active = 0 WHERE packageName = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeactivateAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE blocked_apps SET active = 0 WHERE active = 1";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateBlockUntil = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE blocked_apps SET blockUntil = ? WHERE packageName = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final BlockedAppEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfBlockedAppEntity.insert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object markNonceUsed(final UsedNonceEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfUsedNonceEntity.insert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deactivate(final String packageName, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeactivate.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, packageName);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeactivate.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deactivateAll(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeactivateAll.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeactivateAll.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateBlockUntil(final String packageName, final long newUntil,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateBlockUntil.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, newUntil);
        _argIndex = 2;
        _stmt.bindString(_argIndex, packageName);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateBlockUntil.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<BlockedAppEntity>> observeActive() {
    final String _sql = "SELECT * FROM blocked_apps WHERE active = 1 ORDER BY blockUntil ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"blocked_apps"}, new Callable<List<BlockedAppEntity>>() {
      @Override
      @NonNull
      public List<BlockedAppEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPackageName = CursorUtil.getColumnIndexOrThrow(_cursor, "packageName");
          final int _cursorIndexOfAppLabel = CursorUtil.getColumnIndexOrThrow(_cursor, "appLabel");
          final int _cursorIndexOfBlockedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "blockedAt");
          final int _cursorIndexOfBlockUntil = CursorUtil.getColumnIndexOrThrow(_cursor, "blockUntil");
          final int _cursorIndexOfActive = CursorUtil.getColumnIndexOrThrow(_cursor, "active");
          final List<BlockedAppEntity> _result = new ArrayList<BlockedAppEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final BlockedAppEntity _item;
            final String _tmpPackageName;
            _tmpPackageName = _cursor.getString(_cursorIndexOfPackageName);
            final String _tmpAppLabel;
            _tmpAppLabel = _cursor.getString(_cursorIndexOfAppLabel);
            final long _tmpBlockedAt;
            _tmpBlockedAt = _cursor.getLong(_cursorIndexOfBlockedAt);
            final long _tmpBlockUntil;
            _tmpBlockUntil = _cursor.getLong(_cursorIndexOfBlockUntil);
            final boolean _tmpActive;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfActive);
            _tmpActive = _tmp != 0;
            _item = new BlockedAppEntity(_tmpPackageName,_tmpAppLabel,_tmpBlockedAt,_tmpBlockUntil,_tmpActive);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getActiveOnce(final Continuation<? super List<BlockedAppEntity>> $completion) {
    final String _sql = "SELECT * FROM blocked_apps WHERE active = 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<BlockedAppEntity>>() {
      @Override
      @NonNull
      public List<BlockedAppEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPackageName = CursorUtil.getColumnIndexOrThrow(_cursor, "packageName");
          final int _cursorIndexOfAppLabel = CursorUtil.getColumnIndexOrThrow(_cursor, "appLabel");
          final int _cursorIndexOfBlockedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "blockedAt");
          final int _cursorIndexOfBlockUntil = CursorUtil.getColumnIndexOrThrow(_cursor, "blockUntil");
          final int _cursorIndexOfActive = CursorUtil.getColumnIndexOrThrow(_cursor, "active");
          final List<BlockedAppEntity> _result = new ArrayList<BlockedAppEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final BlockedAppEntity _item;
            final String _tmpPackageName;
            _tmpPackageName = _cursor.getString(_cursorIndexOfPackageName);
            final String _tmpAppLabel;
            _tmpAppLabel = _cursor.getString(_cursorIndexOfAppLabel);
            final long _tmpBlockedAt;
            _tmpBlockedAt = _cursor.getLong(_cursorIndexOfBlockedAt);
            final long _tmpBlockUntil;
            _tmpBlockUntil = _cursor.getLong(_cursorIndexOfBlockUntil);
            final boolean _tmpActive;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfActive);
            _tmpActive = _tmp != 0;
            _item = new BlockedAppEntity(_tmpPackageName,_tmpAppLabel,_tmpBlockedAt,_tmpBlockUntil,_tmpActive);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getActiveLock(final String packageName,
      final Continuation<? super BlockedAppEntity> $completion) {
    final String _sql = "SELECT * FROM blocked_apps WHERE packageName = ? AND active = 1 LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, packageName);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<BlockedAppEntity>() {
      @Override
      @Nullable
      public BlockedAppEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPackageName = CursorUtil.getColumnIndexOrThrow(_cursor, "packageName");
          final int _cursorIndexOfAppLabel = CursorUtil.getColumnIndexOrThrow(_cursor, "appLabel");
          final int _cursorIndexOfBlockedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "blockedAt");
          final int _cursorIndexOfBlockUntil = CursorUtil.getColumnIndexOrThrow(_cursor, "blockUntil");
          final int _cursorIndexOfActive = CursorUtil.getColumnIndexOrThrow(_cursor, "active");
          final BlockedAppEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpPackageName;
            _tmpPackageName = _cursor.getString(_cursorIndexOfPackageName);
            final String _tmpAppLabel;
            _tmpAppLabel = _cursor.getString(_cursorIndexOfAppLabel);
            final long _tmpBlockedAt;
            _tmpBlockedAt = _cursor.getLong(_cursorIndexOfBlockedAt);
            final long _tmpBlockUntil;
            _tmpBlockUntil = _cursor.getLong(_cursorIndexOfBlockUntil);
            final boolean _tmpActive;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfActive);
            _tmpActive = _tmp != 0;
            _result = new BlockedAppEntity(_tmpPackageName,_tmpAppLabel,_tmpBlockedAt,_tmpBlockUntil,_tmpActive);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object isNonceUsed(final String nonce, final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM used_nonces WHERE nonce = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, nonce);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
