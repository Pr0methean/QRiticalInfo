package com.example.qrhealth

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Permission
import kotlinx.android.synthetic.main.content_main.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), View.OnClickListener {
    override fun onClick(view: View?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private val sharingPermission by lazy {
        val permission = Permission()
        permission.type = "anyone"
        permission.role = "reader"
        permission
    }

    private val gso by lazy {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_METADATA_READONLY))
            .requestId()
            .requestEmail() // to fix https://stackoverflow.com/a/41870126/833771
            .build()
    }

    private val client by lazy {
        GoogleSignIn.getClient(applicationContext, gso)
    }

    private val threadPool by lazy {
        Executors.newSingleThreadExecutor()
    }

    internal val defaultFilePrefs by lazy {
        getSharedPreferences(getString(R.string.chosen_file_key), Context.MODE_PRIVATE)
    }

    companion object {
        const val RC_SIGN_IN = 1
        const val RC_PICK_FILE = 2
        const val RC_SET_WALLPAPER = 3
        const val RC_PICK_NEW_FILE = 4
        const val RC_PERMISSION = 5
    }

    private var account by AtomicReferenceObservable<GoogleSignInAccount?>(null) { _, new ->
        val loginFrag = loginFragment as ChecklistItemFragment
        loginFrag.nameRes =
            if (new != null) R.string.change_account else R.string.log_in
        loginFrag.checked = (new != null)
        if (new != null) {
            val accountId = new.id
            val newEditUrlString = accountId?.let {defaultFilePrefs.getString("$it:edit", null)}
            val newShareUrlString = accountId?.let {defaultFilePrefs.getString("$it:share", null)}
            editUri = newEditUrlString?.let {Uri.parse(it)}
            Log.d(getString(R.string.logTag), "URIs after login: $newEditUrlString, $newShareUrlString")
            defaultFilePrefs.edit()
                .putString("edit", newEditUrlString)
                .putString("share", newShareUrlString)
                .apply()
        } else {
            editUri = null
        }
        val intent = Intent(applicationContext!!, QriticalInfoWallpaper::class.java)
        intent.action = Context.WALLPAPER_SERVICE
        intent.putExtra("account", new)
        if (BuildConfig.DEBUG) {
            Log.d(getString(R.string.logTag), "Sending intent for account $new")
        }
        startService(intent)
        drive = if (new != null) getDriveForAccount(new, applicationContext) else null
    }

    private var editUri by AtomicReferenceObservable<Uri?>(null) {_, new ->
        val chooseFileFrag = chooseFileFragment as ChecklistItemFragment
        chooseFileFrag.checked = (new != null)
        buttonEdit.isEnabled = (new != null)
    }

    private var drive by AtomicReferenceObservable<Drive?>(null) {_, _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionIfNeeded()
        val initialAccount = filterDefault(GoogleSignIn.getLastSignedInAccount(applicationContext))
        setContentView(R.layout.activity_main)
        //setSupportActionBar(toolbar)
        account = initialAccount
        val loginFragment = this.loginFragment as ChecklistItemFragment
        loginFragment.nameRes = R.string.log_in
        loginFragment.checked = initialAccount != null
        loginFragment.onClickListener = View.OnClickListener {
            if (account != null) {
                Log.d(getString(R.string.logTag), "Signing out")
                loginFragment.enabled = false
                val signOutTask = client.signOut()
                signOutTask.addOnCompleteListener {
                    account = null
                    loginFragment.enabled = true
                }
                signOutTask.addOnFailureListener {
                    Log.wtf(getString(R.string.logTag), it)
                    loginFragment.enabled = true
                }
            } else {
                Log.d(getString(R.string.logTag), "Signing in")
                startActivityForResult(client.signInIntent, RC_SIGN_IN)
            }
        }
        editUri = defaultFilePrefs.getString("edit", null)?.let { Uri.parse(it) }
        val chooseFileFragment = this.chooseFileFragment as ChecklistItemFragment
        chooseFileFragment.nameRes = R.string.edit_file
        buttonChoose.setOnClickListener { launchFilePicker(Intent.ACTION_OPEN_DOCUMENT, "*/*", RC_PICK_FILE) }
        buttonNew.setOnClickListener { launchFilePicker(Intent.ACTION_CREATE_DOCUMENT, "text/plain", RC_PICK_NEW_FILE) }
        buttonEdit.setOnClickListener {
            openFileForEditing(false)
        }
        val wallpaperPickerFragment = this.wallpaperPickerFragment as ChecklistItemFragment
        wallpaperPickerFragment.enabled = true
        wallpaperPickerFragment.nameRes = R.string.set_wallpaper
        wallpaperPickerFragment.checked = wallpaperEnabled()
        wallpaperPickerFragment.onClickListener = View.OnClickListener {
            Log.d(getString(R.string.logTag), "wallpaperPickerFragment.OnClickListener starting")
            val i = Intent()
            i.action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER

            val p = QriticalInfoWallpaper::class.java.getPackage()!!.name
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(p, QriticalInfoWallpaper::class.java.name))
            startActivityForResult(i, RC_SET_WALLPAPER)
            Log.d(getString(R.string.logTag), "wallpaperPickerFragment.OnClickListener done")
        }
        if (BuildConfig.DEBUG) {
            val currentPrefs = defaultFilePrefs.all
            if (currentPrefs.isEmpty()) {
                Log.d(getString(R.string.logTag), "Shared prefs are empty in MainActivity")
            }
            for ((key, value) in currentPrefs) {
                Log.d(getString(R.string.logTag), "Shared pref in MainActivity: $key = $value")
            }
        }
    }

    private fun requestPermissionIfNeeded(): Boolean {
        if (!(ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS)
                == PackageManager.PERMISSION_GRANTED)
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.GET_ACCOUNTS
                )
            ) {
                showAlert(R.string.permission_explanation) { requestPermission() }
            } else {
                requestPermission()
            }
            return false
        } else {
            return true
        }
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.GET_ACCOUNTS), RC_PERMISSION
        )
    }

    private fun openFileForEditing(isPlainText: Boolean) {
        val currentAccount = account
        if (currentAccount == null) {
            Toast.makeText(this@MainActivity.applicationContext, R.string.log_in_first, Toast.LENGTH_LONG).show()
        } else {
            val uriString = getFileUri(defaultFilePrefs, currentAccount)
            if (uriString == null) {
                Toast.makeText(this@MainActivity.applicationContext, R.string.choose_file_first, Toast.LENGTH_LONG)
                    .show()
            } else {
                val uri = Uri.parse(uriString)
                val i = Intent(Intent.ACTION_EDIT)
                i.data = uri
                try {
                    startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity.applicationContext,
                        if (isPlainText) {R.string.no_app_can_edit_text} else {R.string.no_app_can_edit},
                        Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun launchFilePicker(action: String, mimeType: String, requestCode: Int) {
        val currentAccount = account
        if (currentAccount == null) {
            Toast.makeText(this@MainActivity.applicationContext, R.string.log_in_first, Toast.LENGTH_LONG).show()
        } else {
            val intent = Intent(action)
            // intent.addCategory(Intent.CATEGORY_OPENABLE)
            if (Build.VERSION.SDK_INT >= 26) {
                drive?.baseUrl?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
            }
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            intent.type = mimeType
            startActivityForResult(intent, requestCode)
        }
    }

    private fun wallpaperEnabled(): Boolean {
        val installedPackage = WallpaperManager.getInstance(applicationContext)?.wallpaperInfo?.packageName
        return packageName == installedPackage
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RC_SIGN_IN -> {
                val signInTask = GoogleSignIn.getSignedInAccountFromIntent(data)
                if (!signInTask.isSuccessful) {
                    Log.e(getString(R.string.logTag), "signInTask failed", signInTask.exception)
                } else {
                    account = filterDefault(signInTask.result)
                }
            }
            RC_PICK_FILE -> {
                onFileChosen(resultCode, data)
            }
            RC_PICK_NEW_FILE -> {
                if (onFileChosen(resultCode, data)) {
                    openFileForEditing(true)
                }
            }
            RC_SET_WALLPAPER -> {
                if (resultCode != Activity.RESULT_OK) {
                    Log.e(getString(R.string.logTag), "Wallpaper picking failed: $resultCode: $data")
                }
                (wallpaperPickerFragment as ChecklistItemFragment).checked = wallpaperEnabled()
            }
        }
    }

    private fun onFileChosen(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            Log.e(getString(R.string.logTag), "File picking failed with result code $resultCode")
            return false
        }
        val uri = data?.data
        if (uri == null) {
            Log.e(getString(R.string.logTag), "File picker didn't return a URI")
            return false
        }
        if ("com.google.android.apps.docs.storage" != uri.authority) {
            Log.e(getString(R.string.logTag), "$uri isn't on Google Drive")
            showAlert(R.string.not_on_drive) { /* TODO: Save launchFilePicker params and use them to retry */ }
            return false
        }
        editUri = uri
        val currentDrive = drive
        if (currentDrive == null) {
            Log.e(getString(R.string.logTag), "Unable to get sharing link")
            defaultFilePrefs.edit().remove("edit").remove("share").apply()
            return false
        }
        val uriString = uri.toString()
        val documentId = getIdFromUrl(uri, applicationContext)
        if (documentId == null) {
            Log.e(getString(R.string.logTag), "getIdFromUrl returned null for $uriString")
            return false
        }
        val accountId = account?.id
        threadPool.submit {
            val sharingUri = lookupSharingUri(currentDrive, documentId)
            if (sharingUri == null) {
                if (!requestPermissionIfNeeded()) {
                    Log.e(
                        getString(R.string.logTag),
                        "Can't get sharing URI; still waiting for permission"
                    )
                }
                val batch = currentDrive.batch()
                val permissionTask =
                    currentDrive.permissions().create(documentId, sharingPermission)
                permissionTask.queue(batch, object : JsonBatchCallback<Permission>() {
                    override fun onSuccess(t: Permission?, responseHeaders: HttpHeaders?) {
                        threadPool.submit {
                            val sharingUriAfterPerm = lookupSharingUri(currentDrive, documentId)
                            if (sharingUriAfterPerm == null) {
                                Log.e(
                                    getString(R.string.logTag),
                                    "sharing URI still null after getting permission"
                                )
                            } else {
                                saveFileChoice(uriString, sharingUriAfterPerm, accountId)
                            }
                        }
                    }

                    override fun onFailure(e: GoogleJsonError?, responseHeaders: HttpHeaders?) {
                        Log.e(
                            getString(R.string.logTag),
                            "Error sharing file: $e (HTTP response: $responseHeaders)"
                        )
                    }
                })
                // BatchRequest.execute() complains: "Calling this from your main thread can lead to deadlock"
                threadPool.submit() {
                    Log.d(getString(R.string.logTag), "Batch started")
                    try {
                        batch.execute()
                    } catch (t: Throwable) {
                        Log.e(getString(R.string.logTag), "Error obtaining permission", t)
                    }
                    Log.d(getString(R.string.logTag), "Batch finished")
                }
            } else {
                saveFileChoice(uriString, sharingUri, accountId)
            }
        }
        return true
    }

    private fun showAlert(message: Int, onDismissed: () -> Unit) {
        val alert = AlertDialog.Builder(this@MainActivity)
        alert.setMessage(message)
        alert.setOnDismissListener {_ -> onDismissed()}
        alert.create().show()
    }

    internal fun saveFileChoice(
        editingUri: String,
        sharingUri: String,
        accountId: String?
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(getString(R.string.logTag), "saveFileChoice($editingUri, $sharingUri, $accountId")
        }
        val prefUpdate = defaultFilePrefs.edit()
            .putString("edit", editingUri)
            .putString("share", sharingUri)
        if (accountId != null) {
            prefUpdate.putString("$accountId:edit", editingUri)
            prefUpdate.putString("$accountId:share", sharingUri)
            Log.d(
                getString(R.string.logTag),
                "URIs of newly chosen file: $editingUri, $sharingUri"
            )
        }
        prefUpdate.apply()
        wallpaperIntent()
    }

    @WorkerThread
    internal fun lookupSharingUri(
        currentDrive: Drive,
        documentId: String): String? {
        val request = currentDrive.Files().list()
        request.corpora = "user"
        request.pageSize = 1000
        var nextPageToken: String? = null
        do {
            request.pageToken = nextPageToken
            val response = request.execute()
            nextPageToken = response.nextPageToken
            val list = response.files
            Log.d(getString(R.string.logTag), "response: ${response}.")
            for ((header, value) in response) {
                Log.d(getString(R.string.logTag), "$header: $value")
            }
            Log.d(
                getString(R.string.logTag),
                "response.incompleteSearch: ${response.incompleteSearch}."
            )
            Log.d(getString(R.string.logTag), "Got a list of ${list.size} files")
            for (file in list) {
                Log.d(getString(R.string.logTag), "File $file has id ${file.id}")
                if (file.id == documentId) {
                    return file["webViewLink"]?.toString()
                }
            }
        } while (nextPageToken != null)
        return null
    }

    private fun wallpaperIntent() {
        val intent = Intent(applicationContext!!, QriticalInfoWallpaper::class.java)
        intent.action = Context.WALLPAPER_SERVICE
        startService(intent)
    }
}
