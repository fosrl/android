/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern char *initOlm(char *configJSON);
extern char *startTunnel(int fd, char *configJSON);
extern char *addDevice(int fd);
extern char *stopTunnel();
extern long getNetworkSettingsVersion();
extern char *getNetworkSettings();
extern char *enableFileLogging(char *filePath);
extern char *disableFileLogging();

JNIEXPORT jstring JNICALL Java_net_pangolin_Pangolin_PacketTunnel_GoBackend_initOlm(JNIEnv *env, jclass c, jstring configJSON)
{
	const char *config_str = (*env)->GetStringUTFChars(env, configJSON, 0);
	char *result = initOlm((char *)config_str);
	(*env)->ReleaseStringUTFChars(env, configJSON, config_str);
	if (!result)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, result);
	free(result);
	return ret;
}

JNIEXPORT jstring JNICALL Java_net_pangolin_Pangolin_PacketTunnel_GoBackend_startTunnel(JNIEnv *env, jclass c, jint fd, jstring configJSON)
{
	const char *config_str = (*env)->GetStringUTFChars(env, configJSON, 0);
	char *result = startTunnel(fd, (char *)config_str);
	(*env)->ReleaseStringUTFChars(env, configJSON, config_str);
	if (!result)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, result);
	free(result);
	return ret;
}

JNIEXPORT jstring JNICALL Java_net_pangolin_Pangolin_PacketTunnel_GoBackend_addDevice(JNIEnv *env, jclass c, jint fd)
{
	char *result = addDevice(fd);
	if (!result)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, result);
	free(result);
	return ret;
}

JNIEXPORT jstring JNICALL Java_net_pangolin_Pangolin_PacketTunnel_GoBackend_stopTunnel(JNIEnv *env, jclass c)
{
	char *result = stopTunnel();
	if (!result)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, result);
	free(result);
	return ret;
}

JNIEXPORT jlong JNICALL Java_net_pangolin_Pangolin_PacketTunnel_GoBackend_getNetworkSettingsVersion(JNIEnv *env, jclass c)
{
	return (jlong)getNetworkSettingsVersion();
}

JNIEXPORT jstring JNICALL Java_net_pangolin_Pangolin_PacketTunnel_GoBackend_getNetworkSettings(JNIEnv *env, jclass c)
{
	char *result = getNetworkSettings();
	if (!result)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, result);
	free(result);
	return ret;
}

JNIEXPORT jstring JNICALL Java_net_pangolin_Pangolin_PacketTunnel_GoBackend_enableFileLogging(JNIEnv *env, jclass c, jstring filePath)
{
	const char *path_str = (*env)->GetStringUTFChars(env, filePath, 0);
	char *result = enableFileLogging((char *)path_str);
	(*env)->ReleaseStringUTFChars(env, filePath, path_str);
	if (!result)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, result);
	free(result);
	return ret;
}

JNIEXPORT jstring JNICALL Java_net_pangolin_Pangolin_PacketTunnel_GoBackend_disableFileLogging(JNIEnv *env, jclass c)
{
	char *result = disableFileLogging();
	if (!result)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, result);
	free(result);
	return ret;
}
