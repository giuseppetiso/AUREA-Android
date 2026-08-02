package it.creativemaker3d.aurea;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Inizializzatore interno del layout responsive.
 *
 * Non espone dati e non accetta chiamate esterne: serve esclusivamente a
 * registrare il gestore delle schermate prima dell'apertura delle Activity.
 */
public final class AureaResponsiveProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null
                && context.getApplicationContext() instanceof Application) {
            AureaResponsiveController.install(
                (Application) context.getApplicationContext()
            );
        }
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        return 0;
    }
}
