package com.example.qriticalinfo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes

val DEFAULT_GOOGLE_ACCOUNT = GoogleSignInAccount.createDefault()!!

val JSON_FACTORY = GsonFactory()
val HTTP_TRANSPORT by lazy { NetHttpTransport() }

val ID_REGEX = Regex("/[-\\w]{25,}/")

// { return DocumentsContract.getDocumentId(url).split("doc=encoded=")[1] }

fun getDriveForAccount(
    value: GoogleSignInAccount,
    context: Context
): Drive? {
    if (value.account == null) {
        Log.e(context.getString(R.string.logTag), "GoogleSignInAccount $value has no Account")
        return null
    }
    val credential =
        GoogleAccountCredential.usingOAuth2(context,
            listOf(DriveScopes.DRIVE))
    credential.selectedAccount = value.account
    return Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
        .setApplicationName(context.getString(R.string.app_name))
        .build()
}

fun getFileUri(
    sharedPreferences: SharedPreferences,
    account: GoogleSignInAccount?
): String? {
    return if (account != null) {
        sharedPreferences.getString(account.id, null)
    } else null
}

fun filterDefault(account: GoogleSignInAccount?) = if (DEFAULT_GOOGLE_ACCOUNT == account) null else account