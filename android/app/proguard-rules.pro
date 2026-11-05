# R8 rules for the release build.
#
# Activities, services (ShareService, NotificationMirror, ClipboardTile), receivers and the FileProvider are named in
# the manifest, and AAPT keeps every class the manifest names. The protocol module is plain Java with no reflection.
# org.json is part of Android. What's left to say:

# Stack traces in Copy diagnostics and crash reports stay readable: keep line numbers, hide the source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Enum names are written to preferences and diagnostics (Look ids are their own strings, but Cable/Via use name()).
-keepclassmembers enum org.openbridge.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
