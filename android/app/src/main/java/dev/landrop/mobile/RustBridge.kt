package dev.landrop.mobile

object RustBridge {
    init {
        System.loadLibrary("landrop_mobile_bridge")
    }

    external fun probePeer(ip: String, port: Int): String

    external fun sendOffer(ip: String, port: Int, senderName: String, payloadJson: String): String
}
