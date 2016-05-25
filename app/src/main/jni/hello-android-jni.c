#include <jni.h>

JNIEXPORT jstring JNICALL
Java_com_example_chueh_SKYNETDJI_MainActivity_getMsgFromJni(JNIEnv *env, jobject instance) {

    // TODO


    return (*env)->NewStringUTF(env, "Hello from JNI");
}