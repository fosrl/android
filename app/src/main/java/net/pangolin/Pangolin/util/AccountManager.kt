package net.pangolin.Pangolin.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AccountManager(private val context: Context) {
    private val tag = "AccountManager"
    
    private val _store = MutableStateFlow(AccountStore())
    val store: StateFlow<AccountStore> = _store.asStateFlow()

    val activeAccount: Account?
        get() {
            val currentStore = _store.value
            if (currentStore.activeUserId.isEmpty()) {
                return null
            }
            return currentStore.accounts[currentStore.activeUserId]
        }

    val accounts: Map<String, Account>
        get() = _store.value.accounts

    val activeUserId: String
        get() = _store.value.activeUserId

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    init {
        load()
    }

    fun load() {
        val file = getAccountStoreFile()

        if (!file.exists()) {
            _store.value = AccountStore()
            return
        }

        try {
            val data = file.readText()
            _store.value = json.decodeFromString(data)
        } catch (e: Exception) {
            Log.e(tag, "Error loading account store: ${e.message}", e)
            _store.value = AccountStore()
        }
    }

    fun save(): Boolean {
        val file = getAccountStoreFile()

        return try {
            val data = json.encodeToString(_store.value)
            file.writeText(data)
            true
        } catch (e: Exception) {
            Log.e(tag, "Error saving account store: ${e.message}", e)
            false
        }
    }

    fun addAccount(account: Account, makeActive: Boolean = false) {
        val currentStore = _store.value
        val updatedAccounts = currentStore.accounts.toMutableMap()
        updatedAccounts[account.userId] = account

        val updatedActiveUserId = if (makeActive) {
            account.userId
        } else {
            currentStore.activeUserId
        }

        _store.value = currentStore.copy(
            accounts = updatedAccounts,
            activeUserId = updatedActiveUserId
        )

        save()
    }

    fun setActiveUser(userId: String) {
        val currentStore = _store.value
        if (!currentStore.accounts.containsKey(userId)) {
            return
        }

        _store.value = currentStore.copy(activeUserId = userId)
        save()
    }

    fun setUserOrganization(userId: String, orgId: String) {
        val currentStore = _store.value
        val account = currentStore.accounts[userId]

        if (account != null) {
            val updatedAccount = account.copy(orgId = orgId)
            val updatedAccounts = currentStore.accounts.toMutableMap()
            updatedAccounts[userId] = updatedAccount

            _store.value = currentStore.copy(accounts = updatedAccounts)
            save()
        }
    }

    fun activateAccount(userId: String) {
        val currentStore = _store.value
        if (!currentStore.accounts.containsKey(userId)) {
            Log.e(tag, "Selected account $userId does not exist")
            return
        }

        _store.value = currentStore.copy(activeUserId = userId)
        save()
    }

    fun removeAccount(userId: String) {
        val currentStore = _store.value
        val updatedAccounts = currentStore.accounts.toMutableMap()
        updatedAccounts.remove(userId)

        val updatedActiveUserId = if (currentStore.activeUserId == userId) {
            ""
        } else {
            currentStore.activeUserId
        }

        _store.value = currentStore.copy(
            accounts = updatedAccounts,
            activeUserId = updatedActiveUserId
        )

        save()
    }

    private fun getAccountStoreFile(): File {
        val dir = File(context.filesDir, "Pangolin")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "accounts.json")
    }
}