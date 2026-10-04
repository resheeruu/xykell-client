package dev.xykell.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import dev.xykell.client.R
import dev.xykell.client.runtime.accounts.AccountStore

/** Account surface: shows signed-in state, provides official
 *  Microsoft auth handoff via external browser. No passwords
 *  or tokens are ever entered in-app — flow delegates to
 *  system browser with official Microsoft auth URL. */
class AccountsFragment : Fragment(R.layout.fragment_accounts) {

    private var clientId = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.accounts_title).text =
            getString(R.string.accounts_title)
        view.findViewById<TextView>(R.id.accounts_body).text =
            getString(R.string.accounts_body)

        val clientIdField = view.findViewById<EditText>(R.id.accounts_client_id)
        clientIdField.setText(AccountStore.getClientId(requireContext()) ?: "")

        view.findViewById<Button>(R.id.accounts_save_client).setOnClickListener {
            val id = clientIdField.text.toString().trim()
            if (id.isNotBlank()) {
                AccountStore.setClientId(requireContext(), id)
                clientId = id
                status(view, getString(R.string.accounts_client_saved))
            } else {
                status(view, getString(R.string.accounts_client_empty))
            }
        }

        view.findViewById<Button>(R.id.accounts_sign_in).setOnClickListener {
            val id = clientIdField.text.toString().trim()
            if (id.isBlank()) {
                status(view, getString(R.string.accounts_client_empty))
                return@setOnClickListener
            }
            clientId = id
            AccountStore.setClientId(requireContext(), id)
            val redirectUri = "xykell://auth"
            val url = AccountStore.buildAuthUrl(id, redirectUri)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            status(view, getString(R.string.accounts_launched_browser))
        }

        view.findViewById<Button>(R.id.accounts_sign_out).setOnClickListener {
            AccountStore.signOut(requireContext())
            status(view, getString(R.string.accounts_signed_out))
            refresh()
        }

        view.findViewById<Button>(R.id.accounts_clear_client).setOnClickListener {
            val prefs = requireContext().getSharedPreferences(
                "xykell_accounts", Context.MODE_PRIVATE)
            prefs.edit().remove("client_id").apply()
            clientIdField.text?.clear()
            clientId = ""
            status(view, getString(R.string.accounts_client_cleared))
        }

        refresh()
    }

    private fun refresh() {
        val view = view ?: return
        val name = AccountStore.getAccountName(requireContext())
        val signedIn = AccountStore.isSignedIn(requireContext())
        val clientId = AccountStore.getClientId(requireContext()) ?: ""
        view.findViewById<TextView>(R.id.accounts_status).text =
            if (signedIn) {
                getString(R.string.accounts_signed_in, name)
            } else {
                getString(R.string.accounts_not_signed_in)
            }
        view.findViewById<EditText>(R.id.accounts_client_id).setText(
            if (clientId.isNotBlank()) clientId else ""
        )
        view.findViewById<Button>(R.id.accounts_sign_out).visibility =
            if (signedIn) View.VISIBLE else View.GONE
    }

    private fun status(view: View, text: String) {
        view.findViewById<TextView>(R.id.accounts_status).text = text
    }
}