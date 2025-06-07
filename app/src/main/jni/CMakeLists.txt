# Sets the minimum version of CMake required to build the native
# library. You should either keep the default value or only pass a
# value of 3.4.0 or higher.
cmake_minimum_required(VERSION 3.18.1)

# Declares the project name.
project("custom_media_input_project")

# Add our custom JNI C++ source file
add_library(
        custom_media_input
        SHARED
        custom_media_input.cpp)

# Link against the Android log library
find_library(
        log-lib
        log)

# Link against LibVLC.
# The LibVLC AAR should provide the necessary .so files and headers.
# We need to tell CMake where to find them. The exact path might depend on
# how the AAR is structured and expanded during the build.
# This typically involves finding the package installed by the Gradle dependency.

# Assuming libvlc includes are available globally or via a path set by gradle
# For headers (e.g., vlc/vlc.h):
# You might need to find the include path for libvlc headers.
# This path is usually something like:
# ${CMAKE_CURRENT_SOURCE_DIR}/../../../build/intermediates/exploded-aar/org.videolan.android/libvlc-all/<version>/jni/include
# However, directly depending on such paths is fragile.
# A better way is if LibVLC's AAR provides a CMake config file, or if we define an imported library.

# For this subtask, we'll assume the LibVLC headers are correctly found by the build system
# once the dependency is in Gradle. If not, a more complex CMake setup for find_package(LibVLC)
# or manually setting include_directories and link_directories would be needed.
# The `libvlc-all` AAR should place headers and libs in a way that NDK build system can pick them up.
# For now, we rely on the NDK's default search paths or that Gradle makes them available.
# target_include_directories(custom_media_input PRIVATE ${LIBVLC_INCLUDE_DIRS}) # Placeholder

# For linking .so files:
# Similar to headers, the .so files from libvlc-all AAR need to be linked.
# target_link_libraries(custom_media_input PRIVATE ${LIBVLC_LIBRARIES}) # Placeholder

# For now, let's just link the log library. The LibVLC linking might implicitly work
# if the .so files are correctly placed by the AAR for the NDK to find, or it might require
# explicit -L flags or find_library for libvlc.so itself.
# The `libvlc.c` files mentioned in the docs are for libvlc *core*, not the bindings.
# The JNI code uses <vlc/vlc.h>, which should be provided by the libvlc-all AAR.

target_link_libraries(custom_media_input PUBLIC ${log-lib})

# If linking LibVLC requires more specific paths, those would be added here.
# For example, if libvlc.so is in a known path within the build structure:
# find_library(libvlc-lib libvlc PATHS <path_to_libvlc_abi_specific_libs>)
# target_link_libraries(custom_media_input PUBLIC ${libvlc-lib})

# The most common way this works is that the libvlc-all AAR includes a prefab package
# or CMake scripts that `find_package(libvlc)` would use.
# If that's not the case, manual setup is harder.
# We are proceeding with a minimal CMakeLists.txt assuming Gradle and the AAR handle the heavy lifting
# of exposing LibVLC to the native build. If unresolved symbols for libvlc functions occur,
# this CMakeLists.txt will need to be augmented with paths to LibVLC's headers and shared libraries.
