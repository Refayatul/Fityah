package com.refayatul.fityah.utils

import java.security.MessageDigest

/**
 * Handles the Guardian PIN verification for trusted-contact unlocks.
 * PINs are never stored in plain text; only salted SHA-256 hashes are used.
 */
object ApprovalUtils {
    
    private const val SALT = "fityah_approval_salt_2026"

    /** 
     * Verifies if the provided PIN matches the stored hash.
     */
    fun verifyPin(inputPin: String, pinHash: String?): Boolean {
        if (inputPin.isEmpty() || pinHash == null) return false
        return hashPin(inputPin) == pinHash
    }

    /**
     * Generates a salted SHA-256 hash of the PIN.
     */
    fun hashPin(pin: String): String {
        val input = pin + SALT
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
