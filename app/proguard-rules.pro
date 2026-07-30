# PdfBox-Android reflects over its own resources.
-keep class com.tom_roush.pdfbox.** { *; }
# Bouncy Castle's lightweight API is reached reflectively in places.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# PdfBox-Android references a JPEG 2000 codec it does not depend on: JPXFilter calls
# com.gemalto.jp2, which is an optional library this app does not ship. R8 refuses to
# finish with a dangling reference, so it is declared expected here rather than pulling in
# a decoder for a format no ticket vendor uses.
#
# The consequence, stated rather than hidden: a PDF whose images are JPEG 2000 encoded
# will fail to rasterise. Ingestion reports it as a damaged file, which is the same thing
# a debug build does — this rule changes what R8 accepts, not what the app can read.
-dontwarn com.gemalto.jp2.**
