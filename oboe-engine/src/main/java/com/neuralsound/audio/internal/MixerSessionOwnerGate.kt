package com.neuralsound.audio.internal

internal class MixerSessionOwnerGate {
    private var activeOwnerToken: String? = null
    private var activeClaimId: Long = 0L

    @Synchronized
    fun claim(ownerToken: String?): Long {
        activeOwnerToken = ownerToken
        activeClaimId += 1
        return activeClaimId
    }

    @Synchronized
    fun canRelease(ownerToken: String?): Boolean {
        return activeOwnerToken == ownerToken
    }

    @Synchronized
    fun currentClaimId(ownerToken: String?): Long? {
        return activeClaimId.takeIf { activeOwnerToken == ownerToken }
    }

    @Synchronized
    fun clearIfOwner(ownerToken: String?, claimId: Long? = null) {
        if (activeOwnerToken == ownerToken && (claimId == null || activeClaimId == claimId)) {
            activeOwnerToken = null
        }
    }
}
