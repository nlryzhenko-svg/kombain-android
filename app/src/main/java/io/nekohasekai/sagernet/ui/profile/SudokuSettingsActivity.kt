package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

/** Kombain: Sudoku profile editor. */
class SudokuSettingsActivity : ProfileSettingsActivity<SudokuBean>() {

    override fun createEntity() = SudokuBean().applyDefaultValues()

    override fun SudokuBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverPassword = key
        DataStore.serverProtocol = ascii
        DataStore.serverEncryption = aead
        DataStore.serverNetwork = httpMask.ifBlank { "off" }
        DataStore.serverHeaders = customTables
    }

    override fun SudokuBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        key = DataStore.serverPassword.trim()
        ascii = DataStore.serverProtocol.ifBlank { "prefer_entropy" }
        aead = DataStore.serverEncryption.ifBlank { "chacha20-poly1305" }
        httpMask = DataStore.serverNetwork.let { if (it == "off") "" else it.ifBlank { "ws" } }
        customTables = DataStore.serverHeaders.trim()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.sudoku_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}
