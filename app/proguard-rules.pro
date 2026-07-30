# PdfBox-Android reflects over its own resources.
-keep class com.tom_roush.pdfbox.** { *; }
# Argon2 binds through JNA.
-keep class de.mkammerer.argon2.** { *; }
-keep class com.sun.jna.** { *; }
