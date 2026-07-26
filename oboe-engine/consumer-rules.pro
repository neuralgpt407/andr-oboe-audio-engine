# JNI methods are registered by name from JNI_OnLoad.
-keepclasseswithmembernames,includedescriptorclasses class com.neuralsound.audio.internal.NativeMixerController {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class com.neuralsound.audio.internal.NativeRecorderSession {
    native <methods>;
}
-keep class com.neuralsound.audio.RecorderTelemetry { *; }
