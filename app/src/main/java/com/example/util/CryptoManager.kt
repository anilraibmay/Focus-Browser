package com.example.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoManager {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "SecureBiometricPasswordKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Retorna a chave do Keystore. Se não existir, tenta criar.
         * Pode lançar UserNotAuthenticatedException (nas operações de Crypto) se o limite expirar.
         */
        fun getSecretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                try {
                    val builder = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        // Proteção biométrica rigorosa exigida
                        .setUserAuthenticationRequired(true)
                        
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        builder.setUserAuthenticationParameters(
                            10,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        builder.setUserAuthenticationValidityDurationSeconds(10)
                    }
                        
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        builder.setUnlockedDeviceRequired(true)
                    }
                    
                    keyGenerator.init(builder.build())
                    keyGenerator.generateKey()
                } catch (e: Exception) {
                    // Fallback para dispositivos ou emuladores sem bloqueio de ecrã/PIN configurado
                    val fallbackBuilder = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        
                    keyGenerator.init(fallbackBuilder.build())
                    keyGenerator.generateKey()
                }
            }

            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }

        fun deleteKey() {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                keyStore.load(null)
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun encrypt(plainText: String): Pair<ByteArray, ByteArray> {
            if (plainText.isEmpty()) return Pair(ByteArray(0), ByteArray(0))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val key = try {
                getSecretKey()
            } catch (e: Exception) {
                deleteKey()
                getSecretKey()
            }
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key)
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                deleteKey()
                cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            }
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return Pair(encryptedBytes, iv)
        }

        fun decrypt(encryptedBytes: ByteArray, iv: ByteArray): String {
            if (encryptedBytes.isEmpty() || iv.isEmpty()) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            val key = try {
                getSecretKey()
            } catch (e: Exception) {
                return ""
            }
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        }
    }
}
