package com.hestalang.dashlauncher.private_storage

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.hestalang.dashlauncher.MainActivity
import com.hestalang.dashlauncher.security.GitHubSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives the one-time PKCE callback from the system browser and exchanges it for a session. */
class GitHubAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        completeAuthorization(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        completeAuthorization(intent)
    }

    private fun completeAuthorization(source: Intent?) {
        val uri = source?.data
        val code = uri?.getQueryParameter("code")
        if (uri?.scheme != "dashlauncher" || uri.host != "github-auth" || code.isNullOrBlank()) {
            Toast.makeText(this, "La respuesta de inicio de sesión no es válida.", Toast.LENGTH_LONG).show()
            returnToLauncher()
            return
        }

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val store = GitHubSessionStore(applicationContext)
                    val verifier = store.readPendingVerifier()
                        ?: throw IllegalStateException("No se encontró el verificador de inicio de sesión. Vuelve a conectar GitHub.")
                    val repository = GitHubPrivateStorageRepository(applicationContext)
                    val session = repository.exchangeLoginCode(code, verifier)
                    store.saveSession(session)
                    store.clearPendingVerifier()
                }
            }
            result.onSuccess {
                Toast.makeText(this@GitHubAuthCallbackActivity, "Almacenamiento privado conectado.", Toast.LENGTH_LONG).show()
            }.onFailure {
                GitHubSessionStore(applicationContext).clearPendingVerifier()
                Toast.makeText(
                    this@GitHubAuthCallbackActivity,
                    it.message ?: "No se pudo completar el inicio de sesión.",
                    Toast.LENGTH_LONG
                ).show()
            }
            returnToLauncher()
        }
    }

    private fun returnToLauncher() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }
}
