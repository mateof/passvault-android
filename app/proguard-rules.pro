# PdfBox-Android reflects over its own resources.
-keep class com.tom_roush.pdfbox.** { *; }
# Bouncy Castle's lightweight API is reached reflectively in places.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
