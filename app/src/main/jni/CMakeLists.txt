# Sets the minimum version of CMake required to build the native
# library. You should either keep the default value or only pass a
# value of 3.4.0 or higher.
cmake_minimum_required(VERSION 3.18.1)

# Declares the project name.
project("custom_media_input_project")

# Find LibVLC package using Prefab (CONFIG mode)
# This command tells CMake to search for LibVLC package configuration files.
# If found, it defines imported targets (e.g., libvlc::libvlc) and/or variables.
# REQUIRED ensures CMake will fail if the package is not found.
# CONFIG mode is suitable for packages that provide a CMake configuration file (e.g., via Prefab).
find_package(libvlc REQUIRED CONFIG)

# Add our custom JNI C++ source file
add_library(
        custom_media_input
        SHARED
        custom_media_input.cpp)

# Find the Android log library (for __android_log_print)
find_library(
        log-lib # Sets the variable log-lib
        log)    # The name of the library to find

# Link custom_media_input library against LibVLC and the Android log library.
# libvlc::libvlc is an example of an "imported target" that should be defined by
# LibVLC's package configuration found by find_package.
# Linking against an imported target typically handles both the library linking
# and setting up the necessary include directories.
# PUBLIC specifies that targets linking against custom_media_input will also inherit
# the link dependency on libvlc::libvlc and log-lib.
target_link_libraries(custom_media_input
        PUBLIC
            libvlc::libvlc # Use the imported target from find_package
            ${log-lib}     # Link against the Android log library
        )

# Unnecessary comments removed for clarity.
# The find_package(libvlc REQUIRED CONFIG) should handle finding headers and libs.
# If libvlc::libvlc is not the correct target name, the build will fail at CMake config,
# and inspection of Prefab-generated files or LibVLC documentation would be needed.
