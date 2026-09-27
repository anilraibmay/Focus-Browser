package com.example.viewmodel

import android.app.Application
import android.security.keystore.UserNotAuthenticatedException
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.database.AppDatabase
import com.example.database.PasswordEntity
import com.example.database.PasswordRepository
import com.example.util.BiometricAuthHelper
import com.example.util.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel focado na segurança de credenciais.
 * Coordena a base de dados local com encriptação avançada e verificação biométrica.
 */
class CredentialViewModel(application: Application) : AndroidViewModel(application) {
    private val passwordRepository: PasswordRepository

    private val _passwordList = MutableStateFlow<List<PasswordEntity>>(emptyList())
    val passwordList = _passwordList.asStateFlow()

    // Estado temporário para armazenar a senha decifrada com segurança
    private val _decryptedPasswordCache = MutableStateFlow<Map<Int, String>>(emptyMap())
    val decryptedPasswordCache = _decryptedPasswordCache.asStateFlow()

    init {
        val db = AppDatabase.getDatabase(application)
        passwordRepository = PasswordRepository(db.passwordDao())
        
        viewModelScope.launch {
            passwordRepository.allPasswordsFlow.collect { list ->
                _passwordList.value = list
            }
        }
    }

    /**
     * Guarda uma senha de forma segura com encriptação AES-256-GCM.
     */
    fun savePassword(domain: String, username: String, plainPassword: String, activity: FragmentActivity? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Tenta encriptar. Pode falhar se a autenticação tiver expirado (10 segundos)
                val (encryptedBytes, iv) = CryptoManager.encrypt(plainPassword)
                val entity = PasswordEntity(
                    domain = domain,
                    username = username,
                    encryptedPassword = encryptedBytes,
                    initializationVector = iv
                )
                passwordRepository.insertPassword(entity)
            } catch (e: UserNotAuthenticatedException) {
                // Se necessitar de autenticação, pedimos
                if (activity != null) {
                    launch(Dispatchers.Main) {
                        BiometricAuthHelper.showBiometricPrompt(
                            activity = activity,
                            onSuccess = {
                                // Reconexão segura após biometria
                                savePassword(domain, username, plainPassword, null)
                            },
                            onError = {
                                // Tratar erro (snack bar na UI etc)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Lê a senha exigindo validação biométrica se não foi autenticado recentemente (últimos 10 segs).
     */
    fun getDecryptedPassword(password: PasswordEntity, activity: FragmentActivity, onSuccess: (String) -> Unit, onError: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val decrypted = if (password.initializationVector.isEmpty()) {
                    // Legacy plain-text password from version 4 migration. Let's self-heal and encrypt it!
                    val plainText = String(password.encryptedPassword, Charsets.UTF_8)
                    try {
                        val (encryptedBytes, iv) = CryptoManager.encrypt(plainText)
                        val upgradedNode = password.copy(
                            encryptedPassword = encryptedBytes,
                            initializationVector = iv
                        )
                        passwordRepository.insertPassword(upgradedNode)
                    } catch (tx: Throwable) {
                        tx.printStackTrace()
                    }
                    plainText
                } else {
                    CryptoManager.decrypt(password.encryptedPassword, password.initializationVector)
                }
                launch(Dispatchers.Main) {
                    onSuccess(decrypted)
                }
            } catch (e: UserNotAuthenticatedException) {
                // Faltou validação da impressão digital! Pedindo agora
                launch(Dispatchers.Main) {
                    BiometricAuthHelper.showBiometricPrompt(
                        activity = activity,
                        onSuccess = {
                            // Sucesso na impressão digital
                            getDecryptedPassword(password, activity, onSuccess, onError)
                        },
                        onError = {
                            onError()
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) { onError() }
            }
        }
    }

    fun deletePassword(password: PasswordEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            passwordRepository.deletePassword(password)
        }
    }
    
    fun clearDecryptedCache() {
        _decryptedPasswordCache.value = emptyMap()
    }
}
