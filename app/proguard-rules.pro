# MTKClient Native — release shrinking rules.
#
# The protocol layer is reached only by reflection-free Kotlin calls, so nothing
# there needs keeping. These rules exist to protect the few things R8 cannot see.

# ViewBinding classes are instantiated reflectively by the generated bind() calls.
-keep class dev.cocyce.mtknative.databinding.** { *; }

# androidx.lifecycle builds MtkViewModel reflectively via AndroidViewModelFactory,
# and the support library re-creates Fragments by name after a process restart.
# Both need their constructors intact; keep them explicitly rather than relying
# on consumer rules we cannot verify here.
-keepclassmembers class dev.cocyce.mtknative.ui.MtkViewModel {
    public <init>(android.app.Application);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    public <init>(...);
}

# The generated chip table is data, not behaviour; keep the accessors so
# diagnostics can still print chip names after shrinking.
-keepclassmembers class dev.cocyce.mtknative.chip.ChipConfig { *; }
-keepclassmembers class dev.cocyce.mtknative.chip.ChipDatabase { *; }

# Enum values() is used when rendering log severities.
-keepclassmembers enum dev.cocyce.mtknative.engine.LogLevel { *; }

# Keep line numbers so crash reports from the field are actionable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AndroidX / Material ship their own consumer rules; nothing extra required.
-dontwarn org.jetbrains.annotations.**
