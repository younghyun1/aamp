# Media3 and kotlinx-coroutines ship their own consumer rules, and aamp itself uses no reflection.

# Lucene, behind the phone screen's search, finds its codecs and formats by name through
# META-INF/services files named after their base classes, and each attribute's implementation as
# the attribute's class name plus "Impl". Those classes keep their names.
-keepnames class org.apache.lucene.codecs.Codec
-keepnames class org.apache.lucene.codecs.PostingsFormat
-keepnames class org.apache.lucene.codecs.DocValuesFormat
-keep class * extends org.apache.lucene.codecs.Codec { <init>(); }
-keep class * extends org.apache.lucene.codecs.PostingsFormat { <init>(); }
-keep class * extends org.apache.lucene.codecs.DocValuesFormat { <init>(); }
-keep interface * extends org.apache.lucene.util.Attribute
-keep class * extends org.apache.lucene.util.AttributeImpl { <init>(); }
