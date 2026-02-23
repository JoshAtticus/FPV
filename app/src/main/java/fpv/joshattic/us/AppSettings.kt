package fpv.joshattic.us

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("fpv_settings", Context.MODE_PRIVATE)

    var videoAspectRatio: String
        get() = prefs.getString("video_aspect_ratio", "1:1") ?: "1:1"
        set(value) = prefs.edit().putString("video_aspect_ratio", value).apply()

    var videoResolutionIndex: Int
        get() = prefs.getInt("video_resolution_index", 0)
        set(value) = prefs.edit().putInt("video_resolution_index", value).apply()

    var videoBitrate: Int
        get() = prefs.getInt("video_bitrate", 14)
        set(value) = prefs.edit().putInt("video_bitrate", value).apply()

    var videoFps: Int
        get() = prefs.getInt("video_fps", 30)
        set(value) = prefs.edit().putInt("video_fps", value).apply()

    var audioChannels: Int
        get() = prefs.getInt("audio_channels", 2)
        set(value) = prefs.edit().putInt("audio_channels", value).apply()

    var audioSampleRate: Int
        get() = prefs.getInt("audio_sample_rate", 48000)
        set(value) = prefs.edit().putInt("audio_sample_rate", value).apply()

    var audioBitrate: Int
        get() = prefs.getInt("audio_bitrate", 256000)
        set(value) = prefs.edit().putInt("audio_bitrate", value).apply()

    var photoAspectRatio: String
        get() = prefs.getString("photo_aspect_ratio", "1:1") ?: "1:1"
        set(value) = prefs.edit().putString("photo_aspect_ratio", value).apply()

    var photoResolutionIndex: Int
        get() = prefs.getInt("photo_resolution_index", 0)
        set(value) = prefs.edit().putInt("photo_resolution_index", value).apply()

    var defaultCamera: String
        get() = prefs.getString("default_camera", "50") ?: "50"
        set(value) = prefs.edit().putString("default_camera", value).apply()

    var rememberMode: Boolean
        get() = prefs.getBoolean("remember_mode", false)
        set(value) = prefs.edit().putBoolean("remember_mode", value).apply()

    var lastMode: String
        get() = prefs.getString("last_mode", CameraMode.PHOTO.name) ?: CameraMode.PHOTO.name
        set(value) = prefs.edit().putString("last_mode", value).apply()

    var hasSeenSpatialWarning: Boolean
        get() = prefs.getBoolean("has_seen_spatial_warning", false)
        set(value) = prefs.edit().putBoolean("has_seen_spatial_warning", value).apply()
}
