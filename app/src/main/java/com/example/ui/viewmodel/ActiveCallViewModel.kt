package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.CallSession
import com.example.data.repository.CallRepository
import com.example.webrtc.CallForegroundService
import com.example.webrtc.NetworkQuality
import com.example.webrtc.WebRtcCallClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer

class ActiveCallViewModel(
    private val callId: String,
    private val currentUserId: String,
    private val lowDataMode: Boolean,
    private val callRepository: CallRepository
) : ViewModel() {

    val callSession: StateFlow<CallSession?> = callRepository.observeCall(callId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isCameraOn = MutableStateFlow(true)
    val isCameraOn: StateFlow<Boolean> = _isCameraOn.asStateFlow()

    private val _isBluetoothActive = MutableStateFlow(false)
    val isBluetoothActive: StateFlow<Boolean> = _isBluetoothActive.asStateFlow()

    private val _hasBluetooth = MutableStateFlow(false)
    val hasBluetooth: StateFlow<Boolean> = _hasBluetooth.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _networkQuality = MutableStateFlow(NetworkQuality.CONNECTING)
    val networkQuality: StateFlow<NetworkQuality> = _networkQuality.asStateFlow()

    private val _connectionLabel = MutableStateFlow("Connecting...")
    val connectionLabel: StateFlow<String> = _connectionLabel.asStateFlow()

    private var rtcClient: WebRtcCallClient? = null
    private var offerSent = false
    private var answerSent = false
    private var remoteAnswerApplied = false
    private val seenIceIds = mutableSetOf<String>()
    private var timerJob: Job? = null
    private var startedService = false

    fun initializeCallIfNeeded(context: Context, isVideo: Boolean) {
        if (rtcClient != null) return
        _isSpeakerOn.value = isVideo
        val client = WebRtcCallClient(
            context = context,
            isVideoCall = isVideo,
            onLocalIceCandidate = { ice ->
                val session = callSession.value ?: return@WebRtcCallClient
                viewModelScope.launch {
                    callRepository.addIceCandidate(
                        callId = callId,
                        participantIds = session.participantIds,
                        sdpMid = ice.sdpMid ?: "",
                        sdpMLineIndex = ice.sdpMLineIndex,
                        candidate = ice.sdp
                    )
                }
            },
            onConnectionStateChanged = { state ->
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        _connectionLabel.value = "Connected"
                        startCallTimer()
                    }
                    PeerConnection.PeerConnectionState.CONNECTING -> _connectionLabel.value = "Connecting..."
                    PeerConnection.PeerConnectionState.DISCONNECTED -> _connectionLabel.value = "Reconnecting..."
                    PeerConnection.PeerConnectionState.FAILED -> _connectionLabel.value = "Connection unstable"
                    else -> {}
                }
            },
            onRenegotiationOffer = { offerSdp ->
                viewModelScope.launch {
                    callRepository.updateOfferSdp(callId, offerSdp)
                }
            }
        )
        client.setSpeakerphone(isVideo)
        client.setLowBandwidthMode(lowDataMode)
        rtcClient = client

        viewModelScope.launch {
            client.networkQuality.collect { _networkQuality.value = it }
        }
        viewModelScope.launch {
            client.hasBluetoothHeadset.collect { _hasBluetooth.value = it }
        }

        // Observe ICE candidates from peer
        viewModelScope.launch {
            callRepository.observeIceCandidates(callId, currentUserId).collect { list ->
                for (cand in list) {
                    if (seenIceIds.add(cand.id)) {
                        client.addRemoteIceCandidate(
                            sdpMid = cand.sdpMid,
                            sdpMLineIndex = cand.sdpMLineIndex.toInt(),
                            candidate = cand.candidate
                        )
                    }
                }
            }
        }

        // Observe signaling state
        viewModelScope.launch {
            callSession.collect { session ->
                if (session == null) return@collect
                if (!startedService && session.status in listOf("ringing", "connected")) {
                    startedService = true
                    val peerName = if (session.callerId == currentUserId) session.calleeName else session.callerName
                    CallForegroundService.start(context, callId, peerName, session.callType)
                }
                val isCaller = session.callerId == currentUserId
                if (isCaller && session.offerSdp.isBlank() && !offerSent) {
                    offerSent = true
                    _connectionLabel.value = "Ringing..."
                    client.createOffer { offerSdp ->
                        viewModelScope.launch {
                            callRepository.updateOfferSdp(callId, offerSdp)
                        }
                    }
                }
                if (isCaller && session.answerSdp.isNotBlank() && !remoteAnswerApplied) {
                    remoteAnswerApplied = true
                    client.handleRemoteAnswer(session.answerSdp)
                    startCallTimer()
                }
                if (!isCaller && session.status == "connected" && session.offerSdp.isNotBlank() && !answerSent) {
                    answerSent = true
                    client.handleRemoteOfferAndCreateAnswer(session.offerSdp) { answerSdp ->
                        viewModelScope.launch {
                            callRepository.answerCall(callId, answerSdp)
                        }
                    }
                    startCallTimer()
                }
                if (session.status in listOf("ended", "rejected", "missed")) {
                    CallForegroundService.stop(context)
                    timerJob?.cancel()
                }
            }
        }
    }

    fun acceptIncomingCall() {
        val session = callSession.value ?: return
        val client = rtcClient ?: return
        if (session.offerSdp.isNotBlank() && !answerSent) {
            answerSent = true
            client.handleRemoteOfferAndCreateAnswer(session.offerSdp) { answerSdp ->
                viewModelScope.launch {
                    callRepository.answerCall(callId, answerSdp)
                }
            }
            startCallTimer()
        } else {
            viewModelScope.launch {
                callRepository.updateCallStatus(callId, "connected")
            }
        }
    }

    fun rejectOrEndCall(context: Context) {
        val session = callSession.value
        val nextStatus = if (session?.status == "ringing" && session.calleeId == currentUserId) {
            "rejected"
        } else if (session?.status == "ringing" && session.callerId == currentUserId) {
            "missed"
        } else {
            "ended"
        }
        viewModelScope.launch {
            callRepository.updateCallStatus(callId, nextStatus, _elapsedSeconds.value)
        }
        CallForegroundService.stop(context)
        rtcClient?.dispose()
        rtcClient = null
        timerJob?.cancel()
    }

    fun attachVideoRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        rtcClient?.attachRenderers(local, remote)
    }

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        rtcClient?.setMicMuted(next)
    }

    fun toggleSpeaker() {
        val next = !_isSpeakerOn.value
        _isSpeakerOn.value = next
        _isBluetoothActive.value = false
        rtcClient?.setSpeakerphone(next)
    }

    fun toggleBluetooth() {
        val next = !_isBluetoothActive.value
        _isBluetoothActive.value = next
        if (next) _isSpeakerOn.value = false
        rtcClient?.routeBluetoothAudio(next)
    }

    fun toggleCamera() {
        val next = !_isCameraOn.value
        _isCameraOn.value = next
        rtcClient?.setCameraEnabled(next)
    }

    fun flipCamera() {
        rtcClient?.switchCamera()
    }

    fun isTurnConfigured(): Boolean = rtcClient?.isTurnConfigured == true

    private fun startCallTimer() {
        if (timerJob != null) return
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _elapsedSeconds.value += 1L
                if (_elapsedSeconds.value % 3L == 0L) {
                    rtcClient?.pollNetworkStats()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        rtcClient?.dispose()
        rtcClient = null
    }
}
