package com.example.qriticalinfo

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.android.synthetic.main.content_main.*

class MainActivity : AppCompatActivity(), View.OnClickListener {
    override fun onClick(view: View?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private val gso by lazy {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
    }

    private val client by lazy {
        GoogleSignIn.getClient(applicationContext, gso)
    }

    private val defaultFilePrefs by lazy {
        getSharedPreferences(getString(R.string.chosen_file_key), Context.MODE_PRIVATE)
    }

    companion object {
        const val RC_SIGN_IN = 1
        const val RC_PICK_FILE = 2
        const val RC_SET_WALLPAPER = 3
    }

    private var account by AtomicReferenceObservable<GoogleSignInAccount?>(null) { _, new ->
        val loggedIn = (new != null)
        val loginFrag = loginFragment as ChecklistItemFragment
        loginFrag.nameRes =
            if (loggedIn) R.string.change_account else R.string.log_in
        loginFrag.checked = loggedIn
        val intent = Intent(applicationContext!!, QriticalInfoWallpaper::class.java)
        intent.action = Context.WALLPAPER_SERVICE
        intent.putExtra("account", new)
        if (BuildConfig.DEBUG) {
            Log.d(getString(R.string.logTag), "Sending intent for account $new")
        }
        startService(intent)
        drive = if (loggedIn) getDriveForAccount(new!!, applicationContext) else null
    }

    private var editUri by AtomicReferenceObservable<Uri?>(null) {_, new ->
        val chooseFileFrag = chooseFileFragment as ChecklistItemFragment
        chooseFileFrag.checked = (new != null)
        buttonEdit.isEnabled = (new != null)
    }

    private var drive by AtomicReferenceObservable<Drive?>(null) {_, _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        buttonChoose.setOnClickListener { launchFilePicker(Intent.ACTION_OPEN_DOCUMENT, "text/*") }
        buttonNew.setOnClickListener { launchFilePicker(Intent.ACTION_CREATE_DOCUMENT, "text/plain") }
        buttonEdit.setOnClickListener {
            val currentAccount = account
            if (currentAccount == null) {
                Toast.makeText(this@MainActivity.applicationContext, R.string.log_in_first, Toast.LENGTH_LONG).show()
            } else {
                val uriString = getFileUri(defaultFilePrefs, currentAccount)
                if (uriString == null) {
                    Toast.makeText(this@MainActivity.applicationContext, R.string.choose_file_first, Toast.LENGTH_LONG).show()
                } else {
                    val uri = Uri.parse(uriString)
                    val i = Intent(Intent.ACTION_EDIT)
                    i.data = uri
                    try {
                        startActivity(i)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity.applicationContext, R.string.no_app_can_edit, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        val wallpaperPickerFragment = wallpaperPickerFragment as ChecklistItemFragment
        wallpaperPickerFragment.nameRes = R.string.set_wallpaper
        wallpaperPickerFragment.checked = wallpaperEnabled()
        wallpaperPickerFragment.onClickListener = View.OnClickListener {
            val i = Intent()
            i.action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER

            val p = QriticalInfoWallpaper::class.java.getPackage()!!.name
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(p, QriticalInfoWallpaper::class.java.name))
            startActivityForResult(i, RC_SET_WALLPAPER)
        }
    }

    private fun launchFilePicker(action: String, mimeType: String) {
        val currentAccount = account
        if (currentAccount == null) {
            Toast.makeText(this@MainActivity.applicationContext, R.string.log_in_first, Toast.LENGTH_LONG).show()
        } else {
            val intent = Intent(action)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            if (Build.VERSION.SDK_INT >= 26) {
                intent.putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    getDriveForAccount(currentAccount, applicationContext).baseUrl
                )
            }
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            intent.type = mimeType
            startActivityForResult(intent, RC_PICK_FILE)
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
                if (resultCode != Activity.RESULT_OK) {
                    Log.e(getString(R.string.logTag), "File picking failed with result code $resultCode")
                }
                val uri = data?.data ?: return
                if ("com.google.android.apps.docs.storage" != uri.authority) {
                    Log.e(getString(R.string.logTag), "$uri isn't on Google Drive")
                    val alert = AlertDialog.Builder(applicationContext)
                    alert.setMessage(R.string.not_on_drive)
                    alert.create().show()
                    return
                }
                editUri = uri
                val currentDrive = drive
                if (currentDrive == null) {
                    Log.e(getString(R.string.logTag), "Unable to get sharing link")
                    defaultFilePrefs.edit().remove("edit").remove("share").apply()
                } else {
                    val sharingUri = currentDrive.Files().get(DocumentsContract.getDocumentId(uri))["webViewLink"]
                        .toString()
                    defaultFilePrefs.edit()
                        .putString("edit", uri.toString())
                        .putString("share", sharingUri)
                        .apply()
                }
                val intent = Intent(applicationContext!!, QriticalInfoWallpaper::class.java)
                intent.action = Context.WALLPAPER_SERVICE
                startService(intent)
            }
            RC_SET_WALLPAPER -> {
                if (resultCode != Activity.RESULT_OK) {
                    Log.e(getString(R.string.logTag), "Wallpaper picking failed: $resultCode: $data")
                }
                (wallpaperPickerFragment as ChecklistItemFragment).checked = wallpaperEnabled()
            }
        }
    }
}
