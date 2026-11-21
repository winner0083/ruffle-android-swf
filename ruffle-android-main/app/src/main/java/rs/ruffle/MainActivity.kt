package rs.ruffle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import rs.ruffle.ui.theme.RuffleTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            RuffleTheme {
                RuffleNavHost(openSwf = { openSwf(it) })
            }
        }
    }

    /**
     * Content URIs cannot serve relative asset loads, so library games are played from
     * a private copy; plain file URIs are opened where they are.
     */
    private fun openSwf(uri: Uri) {
        if (uri.scheme != "content") {
            startPlayer(uri)
            return
        }
        Toast.makeText(this, "正在载入…", Toast.LENGTH_SHORT).show()
        Thread {
            val local = try {
                SwfLibrary.copyLocal(this, uri, displayName(uri) ?: "game.swf")
            } catch (e: Exception) {
                Log.e("ruffle", "Could not copy $uri", e)
                null
            }
            runOnUiThread {
                if (local != null) {
                    startPlayer(Uri.fromFile(local))
                } else {
                    Toast.makeText(this, "无法读取该文件", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun startPlayer(uri: Uri) {
        val intent = Intent(
            this@MainActivity,
            PlayerActivity::class.java
        ).apply {
            data = uri
        }
        startActivity(intent)
    }
}
