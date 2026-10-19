# Classes the manifest names (activities, services, receivers, the Application) are kept by
# AAPT's generated rules, and Room ships its own consumer rules, so nothing else needs keeping.
#
# Log calls are deliberately *not* stripped: the reel counter is debugged on the phone with
# `adb logcat -s ReelTracker`, and that has to work on the build that is actually installed.
