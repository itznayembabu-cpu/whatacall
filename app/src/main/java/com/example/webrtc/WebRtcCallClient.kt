package com.example.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

enum class NetworkQuality {
    EXCELLENT, GOOD, FAIR, POOR, CONNECTING
}

class WebRtcCallClient(
    private val context: Context,
    private val isVideoCall: Boolean,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit,
    private val onRenegotiationOffer: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val eglBase: EglBase = EglBase.create()

    private val _networkQuality = MutableStateFlow(NetworkQuality.CONNECTING)
    val networkQuality: StateFlow<NetworkQuality> = _networkQuality.asStateFlow()

    private val _hasBluetoothHeadset = MutableStateFlow(false)
    val hasBluetoothHeadset: StateFlow<Boolean> = _hasBluetoothHeadset.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var isDisposed = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var hasRemoteDescription = false

    val isTurnConfigured: Boolean
        get() {
            val url = BuildConfig.TURN_SERVER_URL.trim()
            return url.isNotEmpty() && !url.startsWith("UNCONFIGURED_")
        }

    init {
        initializeFactory()
        createPeerConnection()
        refreshBluetoothAvailability()
    }

    private fun initializeFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
        val turnUrl = BuildConfig.TURN_SERVER_URL.trim()
        val turnUser = BuildConfig.TURN_USERNAME.trim()
        val turnCred = BuildConfig.TURN_CREDENTIAL.trim()
        if (turnUrl.isNotEmpty() && !turnUrl.startsWith("UNCONFIGURED_")) {
            val builder = PeerConnection.IceServer.builder(turnUrl)
            if (turnUser.isNotEmpty() && !turnUser.startsWith("UNCONFIGURED_")) {
                builder.setUsername(turnUser)
            }
            if (turnCred.isNotEmpty() && !turnCred.startsWith("UNCONFIGURED_")) {
                builder.setPassword(turnCred)
            }
            servers.add(builder.createIceServer())
        }
        return servers
    }

    private fun createPeerConnection() {
        val f = factory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> _networkQuality.value = NetworkQuality.GOOD
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        _networkQuality.value = NetworkQuality.POOR
                        attemptIceRestart()
                    }
                    PeerConnection.IceConnectionState.FAILED -> _networkQuality.value = NetworkQuality.POOR
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null && !isDisposed) {
                    onLocalIceCandidate(candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState != null && !isDisposed) {
                    onConnectionStateChanged(newState)
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                }
            }
        })

        // Local audio track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val audioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = f.createAudioTrack("WHATACALL_AUDIO", audioSource).apply {
            setEnabled(true)
        }
        peerConnection?.addTrack(localAudioTrack, listOf("WHATACALL_STREAM"))

        if (isVideoCall) {
            setupLocalVideo(f)
        }
    }

    private fun setupLocalVideo(f: PeerConnectionFactory) {
        val enumerator = Camera2Enumerator(appContext)
        val deviceNames = enumerator.deviceNames
        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val chosenCamera = frontCamera ?: deviceNames.firstOrNull() ?: return

        val capturer = enumerator.createCapturer(chosenCamera, null) ?: return
        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("WhatacallCaptureThread", eglBase.eglBaseContext)
        val videoSource = f.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, appContext, videoSource.capturerObserver)
        try {
            capturer.startCapture(720, 1280, 24)
        } catch (e: Exception) {
            Log.w(TAG, "Camera startCapture failed: ${e.message}")
        }
        localVideoTrack = f.createVideoTrack("WHATACALL_VIDEO", videoSource).apply {
            setEnabled(true)
        }
        peerConnection?.addTrack(localVideoTrack, listOf("WHATACALL_STREAM"))
        localRenderer?.let { localVideoTrack?.addSink(it) }
    }

    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        local?.let { renderer ->
            try {
                renderer.init(eglBase.eglBaseContext, null)
                renderer.setMirror(true)
                renderer.setEnableHardwareScaler(true)
                localVideoTrack?.addSink(renderer)
            } catch (_: Exception) {
            }
        }
        remote?.let { renderer ->
            try {
                renderer.init(eglBase.eglBaseContext, null)
                renderer.setEnableHardwareScaler(true)
                remoteVideoTrack?.addSink(renderer)
            } catch (_: Exception) {
            }
        }
    }

    fun createOffer(onOfferCreated: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideoCall.toString()))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(simpleSdpObserver("setLocalOffer"), sdp)
                onOfferCreated(sdp.description)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createOffer failed: $err") }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun handleRemoteOfferAndCreateAnswer(offerSdp: String, onAnswerCreated: (String) -> Unit) {
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushPendingIce()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideoCall.toString()))
                }
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        peerConnection?.setLocalDescription(simpleSdpObserver("setLocalAnswer"), answer)
                        onAnswerCreated(answer.description)
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) { Log.e(TAG, "createAnswer failed: $err") }
                    override fun onSetFailure(err: String?) {}
                }, constraints)
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) { Log.e(TAG, "setRemoteOffer failed: $err") }
        }, offer)
    }

    fun handleRemoteAnswer(answerSdp: String) {
        if (answerSdp.isBlank()) return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushPendingIce()
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) { Log.e(TAG, "setRemoteAnswer failed: $err") }
        }, answer)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (!hasRemoteDescription) {
            pendingRemoteIce.add(ice)
        } else {
            peerConnection?.addIceCandidate(ice)
        }
    }

    private fun flushPendingIce() {
        for (ice in pendingRemoteIce) {
            peerConnection?.addIceCandidate(ice)
        }
        pendingRemoteIce.clear()
    }

    private fun attemptIceRestart() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(simpleSdpObserver("iceRestartOffer"), sdp)
                onRenegotiationOffer(sdp.description)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun setMicMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun setSpeakerphone(enabled: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val targetType = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = devices.firstOrNull { it.type == targetType }
            if (device != null) {
                audioManager.setCommunicationDevice(device)
                return
            }
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = enabled
    }

    fun routeBluetoothAudio(enabled: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val bt = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (enabled && bt != null) {
                audioManager.setCommunicationDevice(bt)
            } else {
                setSpeakerphone(false)
            }
        } else {
            @Suppress("DEPRECATION")
            if (enabled) audioManager.startBluetoothSco() else audioManager.stopBluetoothSco()
        }
    }

    fun refreshBluetoothAvailability() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        _hasBluetoothHeadset.value = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    fun setLowBandwidthMode(lowData: Boolean) {
        val senders = peerConnection?.senders ?: return
        for (sender in senders) {
            if (sender.track()?.kind() == "video") {
                val params = sender.parameters
                for (enc in params.encodings) {
                    enc.maxBitrateBps = if (lowData) 350_000 else 1_500_000
                    enc.maxFramerate = if (lowData) 15 else 30
                }
                sender.parameters = params
            }
        }
    }

    fun pollNetworkStats() {
        peerConnection?.getStats { report ->
            var rttMs = 0.0
            var packetsLost = 0L
            for (stat in report.statsMap.values) {
                if (stat.type == "candidate-pair" && stat.members["state"] == "succeeded") {
                    val rtt = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()
                    if (rtt != null) rttMs = rtt * 1000.0
                }
                if (stat.type == "inbound-rtp") {
                    val lost = (stat.members["packetsLost"] as? Number)?.toLong() ?: 0L
                    packetsLost += lost
                }
            }
            _networkQuality.value = when {
                rttMs == 0.0 -> NetworkQuality.CONNECTING
                rttMs < 120 && packetsLost < 5 -> NetworkQuality.EXCELLENT
                rttMs < 260 && packetsLost < 20 -> NetworkQuality.GOOD
                rttMs < 450 -> NetworkQuality.FAIR
                else -> NetworkQuality.POOR
            }
        }
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        try {
            localRenderer?.let { localVideoTrack?.removeSink(it); it.release() }
            remoteRenderer?.let { remoteVideoTrack?.removeSink(it); it.release() }
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
            factory?.dispose()
            eglBase.release()
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing WebRtcCallClient: ${e.message}")
        }
    }

    private fun simpleSdpObserver(label: String) = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() { Log.d(TAG, "$label succeeded") }
        override fun onCreateFailure(err: String?) { Log.e(TAG, "$label create failure: $err") }
        override fun onSetFailure(err: String?) { Log.e(TAG, "$label set failure: $err") }
    }

    private companion object {
        const val TAG = "WhatacallWebRTC"
    }
}
