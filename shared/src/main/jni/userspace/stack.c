/* SPDX-License-Identifier: GPL-3.0-only */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include "lwip/tcpip.h"
#include "lwip/netif.h"
#include "lwip/ip6_addr.h"
#include "lwip/ethip6.h"
#include "lwip/sockets.h"
#include "netif/ethernet.h"

#define JNI_METHOD(name) Java_com_shilapi_xcertplay_network_userspace_UserSpaceNative_##name
#define FRAME_CAPACITY 512
#define FRAME_BYTES 1600
static struct netif interface;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static pthread_mutex_t frames_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned char frames[FRAME_CAPACITY][FRAME_BYTES];
static unsigned short lengths[FRAME_CAPACITY];
static unsigned head, count;
static int active, overflow;
static sys_sem_t ready;
static void initialized(void *p) { sys_sem_signal((sys_sem_t *)p); }
static void boot(void) { sys_sem_new(&ready, 0); tcpip_init(initialized, &ready); sys_sem_wait(&ready); sys_sem_free(&ready); }
static err_t output(struct netif *n, struct pbuf *p) {
    (void)n; pthread_mutex_lock(&frames_lock);
    if (!active) { pthread_mutex_unlock(&frames_lock); return ERR_IF; }
    if (count == FRAME_CAPACITY || p->tot_len > FRAME_BYTES) { overflow = 1; pthread_mutex_unlock(&frames_lock); return ERR_MEM; }
    unsigned tail = (head + count) % FRAME_CAPACITY;
    lengths[tail] = p->tot_len; pbuf_copy_partial(p, frames[tail], p->tot_len, 0); count++;
    pthread_mutex_unlock(&frames_lock); return ERR_OK;
}
static err_t usb_netif_init(struct netif *n) { n->name[0] = 'u'; n->name[1] = 's'; n->mtu = 1500; n->hwaddr_len = 6; n->flags = NETIF_FLAG_BROADCAST | NETIF_FLAG_ETHERNET; n->output_ip6 = ethip6_output; n->linkoutput = output; return ERR_OK; }
struct setup { unsigned char mac[6]; ip6_addr_t address; sys_sem_t done; int ok; };
static void add_interface(void *p) {
    struct setup *s = p;
    if (netif_add_noaddr(&interface, NULL, usb_netif_init, tcpip_input)) {
        memcpy(interface.hwaddr, s->mac, 6);
        netif_ip6_addr_set(&interface, 0, &s->address);
        netif_ip6_addr_set_state(&interface, 0, IP6_ADDR_PREFERRED);
        netif_set_default(&interface); netif_set_link_up(&interface); netif_set_up(&interface); s->ok = 1;
    }
    sys_sem_signal(&s->done);
}
static void remove_interface(void *p) { netif_set_down(&interface); netif_set_link_down(&interface); netif_remove(&interface); sys_sem_signal((sys_sem_t *)p); }
static void error(JNIEnv *env, const char *op) {
    int code = errno; char message[128]; snprintf(message, sizeof(message), "%s failed (errno=%d)", op, code);
    const char *type = code == EWOULDBLOCK || code == EAGAIN || code == ETIMEDOUT ? "java/net/SocketTimeoutException" : "java/net/SocketException";
    (*env)->ThrowNew(env, (*env)->FindClass(env, type), message);
}
static void io_error(JNIEnv *env, const char *message) { (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), message); }
static int address(JNIEnv *env, jbyteArray bytes, int port, struct sockaddr_in6 *a) {
    if (!bytes || (*env)->GetArrayLength(env, bytes) != 16 || port < 0 || port > 65535) { io_error(env, "IPv6 address or port invalid"); return 0; }
    memset(a, 0, sizeof(*a)); a->sin6_family = AF_INET6; a->sin6_port = lwip_htons(port);
    (*env)->GetByteArrayRegion(env, bytes, 0, 16, (jbyte *)&a->sin6_addr);
    a->sin6_scope_id = netif_get_index(&interface); return 1;
}
JNIEXPORT void JNICALL JNI_METHOD(start)(JNIEnv *env, jobject self, jbyteArray ip, jbyteArray mac) {
    (void)self; pthread_once(&once, boot);
    if ((*env)->GetArrayLength(env, ip) != 16 || (*env)->GetArrayLength(env, mac) != 6) { io_error(env, "Invalid USB network address"); return; }
    pthread_mutex_lock(&frames_lock);
    if (active) { pthread_mutex_unlock(&frames_lock); io_error(env, "USB network stack already attached"); return; }
    active = 1; overflow = 0; head = count = 0; pthread_mutex_unlock(&frames_lock);
    struct setup s; memset(&s, 0, sizeof(s)); unsigned char bytes[16];
    (*env)->GetByteArrayRegion(env, ip, 0, 16, (jbyte *)bytes); memcpy(s.address.addr, bytes, 16);
    (*env)->GetByteArrayRegion(env, mac, 0, 6, (jbyte *)s.mac); sys_sem_new(&s.done, 0);
    err_t r = tcpip_callback(add_interface, &s); if (r == ERR_OK) sys_sem_wait(&s.done); sys_sem_free(&s.done);
    if (!s.ok) { pthread_mutex_lock(&frames_lock); active = 0; pthread_mutex_unlock(&frames_lock); io_error(env, "USB network interface creation failed"); }
}
JNIEXPORT void JNICALL JNI_METHOD(stop)(JNIEnv *env, jobject self) {
    (void)env; (void)self; pthread_mutex_lock(&frames_lock); int was_active = active; active = 0; pthread_mutex_unlock(&frames_lock); if (!was_active) return;
    sys_sem_t done; sys_sem_new(&done, 0); if (tcpip_callback(remove_interface, &done) == ERR_OK) sys_sem_wait(&done); sys_sem_free(&done);
    pthread_mutex_lock(&frames_lock); head = count = 0; pthread_mutex_unlock(&frames_lock);
}
JNIEXPORT void JNICALL JNI_METHOD(input)(JNIEnv *env, jobject self, jbyteArray frame) {
    (void)self; int size = (*env)->GetArrayLength(env, frame); if (size < 14 || size > FRAME_BYTES) return;
    pthread_mutex_lock(&frames_lock); int running = active; pthread_mutex_unlock(&frames_lock); if (!running) { io_error(env, "USB network stack detached"); return; }
    struct pbuf *p = pbuf_alloc(PBUF_RAW, size, PBUF_RAM); if (!p) { io_error(env, "USB input buffer allocation failed"); return; }
    (*env)->GetByteArrayRegion(env, frame, 0, size, p->payload);
    if (interface.input(p, &interface) != ERR_OK) { pbuf_free(p); io_error(env, "USB network input queue full"); }
}
JNIEXPORT jbyteArray JNICALL JNI_METHOD(output)(JNIEnv *env, jobject self) {
    (void)self; pthread_mutex_lock(&frames_lock);
    if (overflow) { pthread_mutex_unlock(&frames_lock); io_error(env, "USB output queue overflow; session stopped to prevent silent packet loss"); return NULL; }
    if (!count) { pthread_mutex_unlock(&frames_lock); return NULL; }
    int size = lengths[head]; jbyteArray result = (*env)->NewByteArray(env, size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, size, (jbyte *)frames[head]);
    head = (head + 1) % FRAME_CAPACITY; count--; pthread_mutex_unlock(&frames_lock); return result;
}
JNIEXPORT jint JNICALL JNI_METHOD(socket)(JNIEnv *env, jobject self, jboolean udp) { (void)self; int fd = lwip_socket(AF_INET6, udp ? SOCK_DGRAM : SOCK_STREAM, 0); if (fd < 0) error(env, "socket"); return fd; }
JNIEXPORT void JNICALL JNI_METHOD(bind)(JNIEnv *env, jobject self, jint fd, jbyteArray ip, jint port, jboolean listen) { (void)self; struct sockaddr_in6 a; if (!address(env, ip, port, &a)) return; if (lwip_bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0 || (listen && lwip_listen(fd, 8) < 0)) error(env, "bind/listen"); }
JNIEXPORT jint JNICALL JNI_METHOD(accept)(JNIEnv *env, jobject self, jint fd) { (void)self; int result = lwip_accept(fd, NULL, NULL); if (result < 0) error(env, "accept"); return result; }
JNIEXPORT jbyteArray JNICALL JNI_METHOD(endpoint)(JNIEnv *env, jobject self, jint fd, jboolean peer) { (void)self; struct sockaddr_in6 a; socklen_t size = sizeof(a); int r = peer ? lwip_getpeername(fd, (struct sockaddr *)&a, &size) : lwip_getsockname(fd, (struct sockaddr *)&a, &size); if (r < 0) { error(env, "endpoint"); return NULL; } jbyteArray result = (*env)->NewByteArray(env, 18); if (result) { (*env)->SetByteArrayRegion(env, result, 0, 16, (jbyte *)&a.sin6_addr); (*env)->SetByteArrayRegion(env, result, 16, 2, (jbyte *)&a.sin6_port); } return result; }
JNIEXPORT jint JNICALL JNI_METHOD(read)(JNIEnv *env, jobject self, jint fd, jbyteArray buffer, jint offset, jint length) {
    (void)self; if (offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, buffer) - length) { io_error(env, "Invalid read buffer range"); return -1; }
    unsigned char *data = malloc(length ? length : 1); if (!data) { io_error(env, "Read allocation failed"); return -1; }
    int r = lwip_recv(fd, data, length, 0); if (r < 0) error(env, "read"); else if (r > 0) (*env)->SetByteArrayRegion(env, buffer, offset, r, (jbyte *)data); free(data); return r == 0 ? -1 : r;
}
JNIEXPORT void JNICALL JNI_METHOD(write)(JNIEnv *env, jobject self, jint fd, jbyteArray buffer, jint offset, jint length) {
    (void)self; if (offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, buffer) - length) { io_error(env, "Invalid write buffer range"); return; }
    unsigned char *data = malloc(length ? length : 1); if (!data) { io_error(env, "Write allocation failed"); return; } (*env)->GetByteArrayRegion(env, buffer, offset, length, (jbyte *)data);
    int sent = 0; while (sent < length) { int r = lwip_send(fd, data + sent, length - sent, 0); if (r <= 0) { error(env, "write"); break; } sent += r; } free(data);
}
JNIEXPORT jbyteArray JNICALL JNI_METHOD(receive)(JNIEnv *env, jobject self, jint fd, jint capacity) {
    (void)self; if (capacity < 0 || capacity > 65535) { io_error(env, "Invalid UDP buffer capacity"); return NULL; }
    unsigned char *data = malloc(capacity ? capacity : 1); struct sockaddr_in6 a; socklen_t size = sizeof(a); if (!data) { io_error(env, "UDP allocation failed"); return NULL; }
    int r = lwip_recvfrom(fd, data, capacity, 0, (struct sockaddr *)&a, &size); jbyteArray result = NULL;
    if (r < 0) error(env, "receive"); else { result = (*env)->NewByteArray(env, r + 18); if (result) { (*env)->SetByteArrayRegion(env, result, 0, 16, (jbyte *)&a.sin6_addr); (*env)->SetByteArrayRegion(env, result, 16, 2, (jbyte *)&a.sin6_port); (*env)->SetByteArrayRegion(env, result, 18, r, (jbyte *)data); } } free(data); return result;
}
JNIEXPORT void JNICALL JNI_METHOD(send)(JNIEnv *env, jobject self, jint fd, jbyteArray ip, jint port, jbyteArray buffer, jint offset, jint length) {
    (void)self; struct sockaddr_in6 a; if (!address(env, ip, port, &a)) return;
    if (offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, buffer) - length || length > 65507) { io_error(env, "Invalid UDP buffer range"); return; }
    unsigned char *data = malloc(length ? length : 1); if (!data) { io_error(env, "UDP allocation failed"); return; } (*env)->GetByteArrayRegion(env, buffer, offset, length, (jbyte *)data);
    if (lwip_sendto(fd, data, length, 0, (struct sockaddr *)&a, sizeof(a)) < 0) error(env, "send"); free(data);
}
JNIEXPORT void JNICALL JNI_METHOD(option)(JNIEnv *env, jobject self, jint fd, jint option, jint value) {
    (void)self; int r = -1;
    switch (option) {
    case 0: { struct timeval t = { value / 1000, (value % 1000) * 1000 }; r = lwip_setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &t, sizeof(t)); break; }
    case 1: r = lwip_setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &value, sizeof(value)); break;
    case 2: r = lwip_setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &value, sizeof(value)); break;
    case 3: r = lwip_setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &value, sizeof(value)); break;
    case 4: { struct linger l = { value >= 0, value < 0 ? 0 : value }; r = lwip_setsockopt(fd, SOL_SOCKET, SO_LINGER, &l, sizeof(l)); break; }
    }
    if (r < 0) error(env, "socket option");
}
JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv *env, jobject self, jint fd) { (void)env; (void)self; lwip_close(fd); }
