# The app uses no reflection-based serialization and no dynamic class loading, so most default
# shrinking is safe. The manifest components (activity, services) are kept automatically by AGP.

# Keep the whole app and protocol-core surface. The core is a hand-written library consumed across
# a module boundary and threaded through lambdas and listeners; keeping it wholesale removes any
# risk of R8 stripping a path it cannot see, at a negligible size cost for a single-screen app.
-keep class app.castmirror.** { *; }

# Preserve source file names and line numbers in stack traces for field crash reports.
-keepattributes SourceFile,LineNumberTable
