#!/bin/bash -e

BUILD="./buildscripts"

. $BUILD/include/path.sh
. $BUILD/include/depinfo.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf {app,.}/build app/src/main/{libs,obj}
	exit 0
else
	exit 255
fi

nativeprefix () {
	if [ -f $BUILD/prefix/$1/lib/libmpv.so ]; then
		echo "$(realpath "$BUILD/prefix/$1")"
	else
		echo >&2 "Warning: libmpv.so not found in native prefix for $1, support will be omitted"
	fi
}

prefix32=$(nativeprefix "armv7l")
prefix64=$(nativeprefix "arm64")
prefix_x64=$(nativeprefix "x86_64")
prefix_x86=$(nativeprefix "x86")

if [[ -z "$prefix32" && -z "$prefix64" && -z "$prefix_x64" && -z "$prefix_x86" ]]; then
	echo >&2 "Error: no mpv library detected."
	exit 1
fi

### Native parts
PREFIX32="$prefix32" PREFIX64="$prefix64" PREFIX_X64="$prefix_x64" PREFIX_X86="$prefix_x86" \
ndk-build -C app/src/main -j$cores

# Android installs executables from lib/<abi>/ when named like shared libraries.
# Run from nativeLibraryDir: executing files from writable app data is forbidden.
for pair in "armv7l:armeabi-v7a" "arm64:arm64-v8a" "x86:x86" "x86_64:x86_64"; do
	arch=${pair%%:*}
	abi=${pair#*:}
	if [ -d "app/src/main/libs/$abi" ]; then
		install -m 755 "$BUILD/prefix/$arch/bin/ffmpeg" "app/src/main/libs/$abi/libffmpeg.so"
	fi
done

### Java parts
# Android's gradle plugin needs both of these to correctly strip libraries.
# We could pass them directly to Gradle but by using this file it will persist
# inside Android Studio too.
printf '%s\n' \
	"# This file is automatically written by the build scripts, and read using Gradle" \
	"ndkVersion=$v_ndk_n" "ndkRoot=$ANDROID_NDK_ROOT" >ndk.properties


if [ -n "$DONT_BUILD_RELEASE" ]; then
	./gradlew assembleDebug
else
	if [ -n "$ANDROID_SIGNING_KEY" ]; then
		./gradlew assembleRelease
	else
		./gradlew assembleDebug assembleRelease
	fi
	if [ -n "$BUNDLE" ]; then
		# needs to be a separate invocation due to AGP bugs...
		./gradlew bundleRelease
	fi
fi

### Signing
if [ -n "$ANDROID_SIGNING_KEY" ] && [ -z "$DONT_BUILD_RELEASE" ]; then
	apk_signing_args=()
	jar_signing_args=()
	if [ -n "$ANDROID_SIGNING_ALIAS" ]; then
		apk_signing_args+=(--ks-key-alias "$ANDROID_SIGNING_ALIAS")
	fi
	if [ -n "$ANDROID_SIGNING_PASSWORD" ]; then
		apk_signing_args+=(--ks-pass env:ANDROID_SIGNING_PASSWORD --key-pass env:ANDROID_SIGNING_PASSWORD)
		jar_signing_args+=(-storepass:env ANDROID_SIGNING_PASSWORD -keypass:env ANDROID_SIGNING_PASSWORD)
	fi
	cd "app/build/outputs/apk"
	apksigner=${ANDROID_HOME}/build-tools/${v_sdk_build_tools}/apksigner
	for v in default allstorage; do
		pushd $v
		for apk in release/*-unsigned.apk; do
			"$apksigner" sign --ks "${ANDROID_SIGNING_KEY}" "${apk_signing_args[@]}" \
				--in "$apk" --out "${apk/-unsigned/-signed}"
			"$apksigner" verify "${apk/-unsigned/-signed}"
		done
		popd
	done
	# and the bundle
	if [ -n "$BUNDLE" ]; then
		cd ../bundle
		if [ -z "$ANDROID_SIGNING_ALIAS" ]; then
			echo >&2 "Error: ANDROID_SIGNING_ALIAS must be set to use jarsigner"
			exit 1
		fi
		pushd defaultRelease
		jarsigner -keystore "${ANDROID_SIGNING_KEY}" "${jar_signing_args[@]}" -signedjar \
			app-default-release-signed.aab app-default-release.aab \
			"${ANDROID_SIGNING_ALIAS}"
		popd
	fi
fi
