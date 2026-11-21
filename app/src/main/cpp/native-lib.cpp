#include <jni.h>
#include <cerrno>
#include <cstring>
#include <sys/socket.h>

// Binds a socket to a network interface (SO_BINDTODEVICE), e.g. "wlan0".
// Needed on the phone when wireless Android Auto holds the car hotspot as a private network that
// other apps can't see through ConnectivityManager (verified working from Termux on the phone).
// Returns 0 on success, otherwise errno.
extern "C" JNIEXPORT jint JNICALL
Java_com_nhowka_autobridge_net_IfaceSocket_bindToDevice(JNIEnv *env, jclass, jint fd, jstring iface) {
    const char *name = env->GetStringUTFChars(iface, nullptr);
    int rc = setsockopt(fd, SOL_SOCKET, SO_BINDTODEVICE, name, (socklen_t) (strlen(name) + 1));
    int err = rc == 0 ? 0 : errno;
    env->ReleaseStringUTFChars(iface, name);
    return err;
}
