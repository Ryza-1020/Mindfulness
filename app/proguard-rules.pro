# Room / DataStore / Compose ship their own consumer rules.
# Keep the device-admin receiver (referenced only from the manifest + dpm command line).
-keep class com.focuslock.app.block.FocusDeviceAdminReceiver { *; }
