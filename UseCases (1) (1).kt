package com.glassremote.domain.usecase

import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import javax.inject.Inject

class DiscoverTvsUseCase @Inject constructor(private val repo: TvRepository) {
    operator fun invoke() = repo.discover()
}

class PairTvUseCase @Inject constructor(private val repo: TvRepository) {
    /** Step 1: the TV shows a 6-character code. */
    suspend fun begin(device: TvDevice) = repo.beginPairing(device)

    /** Step 2: verify the code, then open the control channel. */
    suspend fun submit(device: TvDevice, code: String) {
        repo.submitPairingCode(device, code)
        repo.connect(device)
    }
}

class SendKeyUseCase @Inject constructor(private val repo: TvRepository) {
    suspend operator fun invoke(keyCode: Int, direction: KeyDirection = KeyDirection.Short) =
        repo.sendKey(keyCode, direction)
}
