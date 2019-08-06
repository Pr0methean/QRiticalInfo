package com.example.qriticalinfo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.qriticalinfo.QriticalInfoWallpaper.Companion.ACTION_SET_GOOGLE_ACCOUNT
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import java.util.concurrent.atomic.AtomicReference
import androidx.core.app.ActivityCompat.startActivityForResult
import android.app.WallpaperManager
import android.content.ComponentName
import android.os.Build
import android.provider.DocumentsContract
import com.example.qriticalinfo.QriticalInfoWallpaper.Companion.getDriveForAccount


class MainActivity : AppCompatActivity(), View.OnClickListener {
    override fun onClick(view: View?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    lateinit var gso : GoogleSignInOptions
    lateinit var client : GoogleSignInClient

    private val defaultFilePrefs by lazy {
        getSharedPreferences(getString(R.string.chosen_file_key), Context.MODE_PRIVATE)
    }

    companion object {
        const val RC_SIGN_IN = 1
        const val RC_PICK_FILE = 2
        val DEFAULT_GOOGLE_ACCOUNT = GoogleSignInAccount.createDefault()!!
    }

    val account : AtomicReference<GoogleSignInAccount?> = AtomicReference(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        client = GoogleSignIn.getClient(applicationContext, gso)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        val loginFragment = this.loginFragment as ChecklistItemFragment
        loginFragment.nameRes = R.string.log_in
        loginFragment.checked = false
        loginFragment.onClickListener = View.OnClickListener {
            if (account.get() != null) {
                Log.d(getString(R.string.logTag), "Signing out")
                loginFragment.enabled = false
                val signOutTask = client.signOut()
                signOutTask.addOnCompleteListener {
                    setAccount(null)
                    loginFragment.nameRes = R.string.log_in
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
        val chooseFileFragment = this.chooseFileFragment as ChecklistItemFragment
        chooseFileFragment.nameRes = R.string.edit_file
        chooseFileFragment.checked = false
        chooseFileFragment.onClickListener = View.OnClickListener {
            val currentAccount = account.get()
            if (currentAccount == null) {
                Toast.makeText(it.context, R.string.log_in_first, Toast.LENGTH_LONG).show()
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    getDriveForAccount(currentAccount, applicationContext).baseUrl)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                intent.type = "text/plain"
                startActivityForResult(intent, RC_PICK_FILE)
            }
        }
        val wallpaperPickerFragment = wallpaperPickerFragment as ChecklistItemFragment
        wallpaperPickerFragment.nameRes = R.string.set_wallpaper
        wallpaperPickerFragment.checked = wallpaperEnabled()
        wallpaperPickerFragment.onClickListener = View.OnClickListener {
            val i = Intent()

            if (Build.VERSION.SDK_INT > 15) {
                i.action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER

                val p = QriticalInfoWallpaper::class.java!!.getPackage()!!.getName()
                i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(p, wallpaperName))
            } else {
                i.action = WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER
            }
            startActivityForResult(i, 0)
        }
    }

    private val wallpaperName = QriticalInfoWallpaper::class.java!!.canonicalName!!

    private fun wallpaperEnabled(): Boolean {
        return wallpaperName.equals(WallpaperManager.getInstance(applicationContext)?.wallpaperInfo?.serviceName)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RC_SIGN_IN -> {
                val signInTask = GoogleSignIn.getSignedInAccountFromIntent(data)
                if (!signInTask.isSuccessful) {
                    Log.e(getString(R.string.logTag), "signInTask failed", signInTask.exception)
                } else {
                    setAccount(signInTask.result)
                }
            }
            RC_PICK_FILE -> {
                if (resultCode != Activity.RESULT_OK) {
                    Log.e(getString(R.string.logTag), "File picking failed with result code $resultCode")
                }
                val currentAccount = account.get() ?: return
                val uri = data?.data
                // TODO: Check that it's actually in Google Drive
                defaultFilePrefs.edit().putString(currentAccount.idToken,
                    uri?.toString()).apply()
                val intent = Intent(applicationContext!!, QriticalInfoWallpaper::class.java)
                intent.action = Context.WALLPAPER_SERVICE
                startService(intent)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setAccount(newAccount: GoogleSignInAccount?) {
        val realNewAccount = if (DEFAULT_GOOGLE_ACCOUNT == newAccount) null else newAccount
        (loginFragment as ChecklistItemFragment).nameRes =
            if (realNewAccount == null) R.string.log_in else R.string.change_account
        account.set(realNewAccount)
        val intent = Intent(applicationContext!!, QriticalInfoWallpaper::class.java)
        intent.action = Context.WALLPAPER_SERVICE
        intent.putExtra("account", realNewAccount)
        startService(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }
}
