# Prebuilt native libraries

- `arm64-v8a/libhev-socks5-tunnel.so` – [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT),
  commit `4702e9a` (submodules: hev-task-system 5.12.0, lwIP 2.2.1.12, libyaml 0.2.5.4). Built with NDK 28.2:

      ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk NDK_APPLICATION_MK=./Application.mk \
        APP_ABI=arm64-v8a APP_PLATFORM=android-30 \
        "APP_CFLAGS=-O3 -DPKGNAME=com/nhowka/autobridge/share -DCLSNAME=Tun2Socks"

  JNI class: `com.nhowka.autobridge.share.Tun2Socks`. Licences in `assets/licenses/`.
